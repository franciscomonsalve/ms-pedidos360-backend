package cl.duoc.pedidos360.audit.messaging;

import cl.duoc.pedidos360.audit.service.AuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume {@code orders.events} en el grupo {@code pedidos360-audit}.
 *
 * ESTA CLASE ES UN ADAPTADOR, NO LOGICA DE NEGOCIO
 * Hace tres cosas y ninguna mas: valida la forma del mensaje, delega en
 * AuditEventService, y publica el estado consolidado. Las reglas de la
 * auditoria -que es un duplicado, como se describe un hecho- viven en el
 * servicio, que no sabe que existe Kafka.
 *
 * SOBRE EL GRUPO
 * El group-id va aqui y no solo en application.yml porque este servicio
 * consume dos topicos con dos grupos distintos. El grupo es lo que define el
 * offset: {@code pedidos360-audit} lleva su propia posicion en orders.events,
 * independiente de la de cualquier otro consumidor del mismo topico.
 *
 * ms-pedidos360-notify consume ESTE MISMO topico con otro grupo. Los dos
 * reciben todos los eventos y cada uno avanza a su ritmo: si notify se cae una
 * hora, este consumidor no se entera.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final AuditEventService service;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${pedidos360.messaging.topic-audit-timeline}")
    private String topicoTimeline;

    public OrderEventConsumer(AuditEventService service, KafkaTemplate<String, Object> kafkaTemplate) {
        this.service = service;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = "${pedidos360.messaging.topic-orders-events}",
            groupId = "pedidos360-audit",
            containerFactory = "orderEventListenerFactory")
    public void onOrderEvent(OrderEvent evento,
                             @Header(KafkaHeaders.RECEIVED_TOPIC) String topico,
                             @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
                             @Header(KafkaHeaders.OFFSET) long offset) {

        if (evento == null || !evento.esValido()) {
            // No reintentable: va directo a orders.events.DLT.
            throw new EventoNoProcesableException(
                    "evento incompleto en %s-%d offset %d".formatted(topico, particion, offset));
        }

        log.debug("[auditoria] recibido {} de {}-{} offset {}",
                evento.eventType(), topico, particion, offset);

        boolean registrado = service.registrar(evento, topico);

        // Solo se propaga al topico compactado lo que es nuevo. Reenviar un
        // duplicado no romperia nada -la compactacion lo colapsaria- pero
        // gastaria una escritura y ensuciaria el lag del siguiente consumidor.
        if (registrado) {
            publicarEstadoConsolidado(evento);
        }
    }

    /**
     * Publica en {@code audit.timeline} con el orderId como CLAVE.
     *
     * La clave no es decorativa: ese topico tiene cleanup.policy=compact, y la
     * compactacion trabaja por clave. Sin clave no habria nada que compactar y
     * el topico creceria para siempre.
     */
    private void publicarEstadoConsolidado(OrderEvent evento) {
        TimelineEvent timeline = TimelineEvent.desde(evento);
        String clave = String.valueOf(evento.orderId());

        kafkaTemplate.send(topicoTimeline, clave, timeline)
                .whenComplete((resultado, ex) -> {
                    if (ex != null) {
                        log.error("[timeline] no se pudo publicar el pedido {}: {}",
                                evento.orderId(), ex.getMessage());
                        return;
                    }
                    var meta = resultado.getRecordMetadata();
                    log.info("[timeline] publicado pedido={} estado={} particion={} offset={}",
                            evento.orderId(), evento.status(), meta.partition(), meta.offset());
                });
    }
}
