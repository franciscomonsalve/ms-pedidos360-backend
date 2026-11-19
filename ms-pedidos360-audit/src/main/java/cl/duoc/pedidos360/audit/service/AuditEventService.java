package cl.duoc.pedidos360.audit.service;

import cl.duoc.pedidos360.audit.entity.AuditEvent;
import cl.duoc.pedidos360.audit.messaging.OrderEvent;
import cl.duoc.pedidos360.audit.messaging.TimelineEvent;
import cl.duoc.pedidos360.audit.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Logica de dominio de la auditoria.
 *
 * ESTA CLASE NO SABE QUE EXISTE KAFKA, y es a proposito: la pauta pide
 * "mantener la logica de procesamiento separada de la infraestructura de
 * mensajeria". Aqui no hay @KafkaListener, ni Acknowledgment, ni
 * ConsumerRecord. Recibe un objeto de dominio y lo registra.
 *
 * La consecuencia practica: estas reglas se pueden probar con una prueba
 * unitaria normal, sin levantar un broker. Y si manana la fuente dejara de ser
 * Kafka, esta clase no cambiaria.
 *
 * Es el mismo criterio que ya aplica CommandProcessor con los consumidores de
 * RabbitMQ.
 */
@Service
public class AuditEventService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventRepository repository;

    public AuditEventService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Registra un evento de pedido en la linea de tiempo.
     *
     * IDEMPOTENCIA POR eventId
     * Kafka garantiza entrega "al menos una vez": tras un rebalanceo de
     * particiones, o si el consumidor cae despues de procesar pero antes de
     * confirmar el offset, el mismo evento se vuelve a entregar. Sin este
     * control, la linea de tiempo mostraria el mismo hecho repetido.
     *
     * La columna EVENT_ID ya es unica en la tabla, asi que la base es la ultima
     * defensa; esta comprobacion evita llegar a ella y poder distinguir en el
     * registro un duplicado esperado de un error real.
     *
     * @return true si se registro, false si era un duplicado
     */
    @Transactional
    public boolean registrar(OrderEvent evento, String topicoOrigen) {
        if (repository.existsByEventId(evento.eventId())) {
            log.info("[auditoria] eventId={} ya registrado, se descarta el duplicado (idempotencia)",
                    evento.eventId());
            return false;
        }

        AuditEvent registro = AuditEvent.builder()
                .eventId(evento.eventId())
                .eventType(evento.eventType())
                .entityId(String.valueOf(evento.orderId()))
                .actor(evento.customerId())
                .sourceTopic(topicoOrigen)
                .occurredAt(evento.occurredAt())
                .details(describir(evento))
                .build();

        repository.save(registro);

        log.info("[auditoria] registrado tipo={} pedido={} traceId={} origen={}",
                evento.eventType(), evento.orderId(), evento.traceId(), topicoOrigen);
        return true;
    }

    /**
     * Procesa el estado consolidado que llega por el topico compactado.
     *
     * No escribe en la tabla: la linea de tiempo ya la alimenta
     * {@link #registrar}. Lo que hace es dejar constancia de que el estado
     * consolidado se recibio, que es lo que permite demostrar en la
     * presentacion que los dos topicos estan vivos y que el segundo consumidor
     * trabaja con su propio offset.
     */
    public void procesarEstadoConsolidado(TimelineEvent evento) {
        log.info("[timeline] pedido={} estado={} (anterior={}) traceId={}",
                evento.orderId(), evento.estadoActual(), evento.estadoAnterior(), evento.traceId());
    }

    private String describir(OrderEvent evento) {
        return "Pedido %d de %s: %s -> %s (monto %s)".formatted(
                evento.orderId(),
                evento.customerId(),
                evento.previousStatus(),
                evento.status(),
                evento.totalAmount());
    }
}
