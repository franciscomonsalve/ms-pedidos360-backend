package cl.duoc.pedidos360.notify.messaging;

import cl.duoc.pedidos360.notify.dto.MessageEnvelope;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consume los comandos publicados por ms-pedidos360-orders desde las colas
 * q.cmd.email, q.cmd.kitchen y q.cmd.invoice. Realiza ACK/NACK explicito y
 * aplica una verificacion basica de idempotencia por eventId para evitar
 * efectos secundarios duplicados (envios repetidos) segun buenas practicas
 * del diseño del caso.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    // Cache simple en memoria de eventId ya procesados (para un MVP; en produccion usar Redis/BD)
    private final Set<String> processedEventIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @RabbitListener(queues = "q.cmd.email")
    public void handleEmail(MessageEnvelope envelope, Message message, Channel channel) throws IOException {
        process("email", envelope, message, channel);
    }

    @RabbitListener(queues = "q.cmd.kitchen")
    public void handleKitchen(MessageEnvelope envelope, Message message, Channel channel) throws IOException {
        process("kitchen", envelope, message, channel);
    }

    @RabbitListener(queues = "q.cmd.invoice")
    public void handleInvoice(MessageEnvelope envelope, Message message, Channel channel) throws IOException {
        process("invoice", envelope, message, channel);
    }

    private void process(String kind, MessageEnvelope envelope, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            if (!processedEventIds.add(envelope.eventId())) {
                log.info("Evento {} ya procesado previamente, se descarta duplicado (idempotencia)", envelope.eventId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            log.info("Procesando comando [{}] eventId={} correlationId={} payload={}",
                    kind, envelope.eventId(), envelope.correlationId(), envelope.payload());

            // Aqui se integraria el proveedor real de email/push/impresion/facturacion.
            // Simulamos procesamiento exitoso.

            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("Error procesando comando [{}] eventId={}: {}", kind, envelope.eventId(), ex.getMessage());
            // requeue=false: el broker enruta a la DLQ configurada (x-dead-letter-exchange)
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
