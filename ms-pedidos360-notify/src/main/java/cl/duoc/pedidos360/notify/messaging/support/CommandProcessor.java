package cl.duoc.pedidos360.notify.messaging.support;

import cl.duoc.pedidos360.notify.dto.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ciclo de vida comun de un comando consumido de RabbitMQ (ack manual).
 * Cada consumidor de dominio solo aporta su logica de negocio; aqui se decide
 * SIEMPRE de forma explicita el destino del mensaje:
 *
 * <pre>
 *  mensaje malformado / sin eventId / sin type ........... basicReject(requeue=false) -> DLQ
 *  eventId ya procesado (duplicado) ....................... basicAck (idempotencia)
 *  procesado OK ........................................... basicAck
 *  TransientProcessingException, 1a vez ................... basicNack(requeue=true)  -> reintento
 *  TransientProcessingException, ya reentregado ........... basicReject(requeue=false) -> DLQ
 *  cualquier otro error (no recuperable) .................. basicReject(requeue=false) -> DLQ
 * </pre>
 *
 * Todo envio a la DLQ queda registrado en log con el motivo.
 */
@Component
public class CommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessor.class);

    /** Tope del cache de idempotencia en memoria (MVP; en produccion Redis/BD). */
    private static final int MAX_TRACKED_EVENTS = 10_000;

    private final ObjectMapper objectMapper;
    private final boolean simulateFailures;
    private final Set<String> processedEventIds = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRACKED_EVENTS;
                }
            }));

    public CommandProcessor(ObjectMapper objectMapper,
                            @Value("${pedidos360.messaging.simulate-failures:false}") boolean simulateFailures) {
        this.objectMapper = objectMapper;
        this.simulateFailures = simulateFailures;
    }

    public void process(String domain, Message message, Channel channel,
                        Consumer<MessageEnvelope> handler) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String queue = message.getMessageProperties().getConsumerQueue();

        MessageEnvelope envelope;
        try {
            envelope = parse(message);
        } catch (IllegalArgumentException ex) {
            rejectToDlq(domain, queue, null, "mensaje invalido: " + ex.getMessage(), deliveryTag, channel);
            return;
        }

        if (processedEventIds.contains(envelope.eventId())) {
            log.info("[{}] eventId={} ya procesado, se descarta el duplicado (idempotencia)", domain, envelope.eventId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            simulateFailureIfRequested(envelope);
            handler.accept(envelope);
            processedEventIds.add(envelope.eventId());
            channel.basicAck(deliveryTag, false);
            log.info("[{}] comando procesado OK eventId={} correlationId={}",
                    domain, envelope.eventId(), envelope.correlationId());
        } catch (TransientProcessingException ex) {
            if (!message.getMessageProperties().isRedelivered()) {
                log.warn("[{}] error transitorio eventId={}: {}. Se reintenta una vez (requeue)",
                        domain, envelope.eventId(), ex.getMessage());
                channel.basicNack(deliveryTag, false, true);
            } else {
                rejectToDlq(domain, queue, envelope, "reintentos agotados: " + ex.getMessage(), deliveryTag, channel);
            }
        } catch (Exception ex) {
            rejectToDlq(domain, queue, envelope, "error no recuperable: " + ex.getMessage(), deliveryTag, channel);
        }
    }

    private MessageEnvelope parse(Message message) {
        MessageEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getBody(), MessageEnvelope.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("el cuerpo no es un envelope JSON valido");
        }
        if (envelope == null || isBlank(envelope.eventId()) || isBlank(envelope.type())) {
            throw new IllegalArgumentException("faltan campos obligatorios (eventId, type)");
        }
        return envelope;
    }

    private void rejectToDlq(String domain, String queue, MessageEnvelope envelope, String reason,
                             long deliveryTag, Channel channel) throws IOException {
        // requeue=false: el broker re-enruta el mensaje al DLX configurado en la cola.
        channel.basicReject(deliveryTag, false);
        log.error("[{}] mensaje enviado a DLQ (queue={}, eventId={}, correlationId={}) motivo: {}",
                domain, queue,
                envelope == null ? "n/a" : envelope.eventId(),
                envelope == null ? "n/a" : envelope.correlationId(),
                reason);
    }

    /**
     * Gancho de demostracion (apagado por defecto): con
     * pedidos360.messaging.simulate-failures=true, un payload con
     * failMode=transient o failMode=poison fuerza el error correspondiente
     * para evidenciar el reintento y el paso a la DLQ.
     */
    private void simulateFailureIfRequested(MessageEnvelope envelope) {
        if (!simulateFailures || envelope.payload() == null) {
            return;
        }
        Object mode = envelope.payload().get("failMode");
        if ("transient".equals(mode)) {
            throw new TransientProcessingException("falla transitoria simulada");
        }
        if ("poison".equals(mode)) {
            throw new IllegalStateException("falla no recuperable simulada");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
