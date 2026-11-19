package cl.duoc.pedidos360.audit.messaging;

import cl.duoc.pedidos360.audit.service.AuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume {@code audit.timeline} en el grupo {@code pedidos360-timeline}.
 *
 * TERCER GRUPO DEL SISTEMA, Y EL MAS INTERESANTE DE EXPLICAR
 * Este consumidor lee el topico COMPACTADO. La diferencia practica con el otro
 * consumidor se ve al arrancar de cero: con auto-offset-reset=earliest,
 *
 *   en orders.events   leeria TODOS los cambios de estado de los ultimos 7
 *                      dias, incluidos los intermedios ya superados.
 *   en audit.timeline  lee UN mensaje por pedido, el ultimo estado conocido.
 *                      La compactacion ya descarto los intermedios.
 *
 * Por eso un topico compactado sirve para reconstruir estado y uno normal para
 * reconstruir historia. Son dos casos de uso distintos del mismo mecanismo, y
 * es la justificacion de tener dos topicos con dos politicas.
 */
@Component
public class TimelineEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TimelineEventConsumer.class);

    private final AuditEventService service;

    public TimelineEventConsumer(AuditEventService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "${pedidos360.messaging.topic-audit-timeline}",
            groupId = "pedidos360-timeline",
            containerFactory = "timelineEventListenerFactory")
    public void onTimelineEvent(TimelineEvent evento,
                                @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
                                @Header(KafkaHeaders.OFFSET) long offset) {

        if (evento == null || evento.orderId() == null || evento.estadoActual() == null) {
            throw new EventoNoProcesableException(
                    "estado consolidado incompleto en particion %d offset %d".formatted(particion, offset));
        }

        log.debug("[timeline] recibido pedido={} particion={} offset={}",
                evento.orderId(), particion, offset);

        service.procesarEstadoConsolidado(evento);
    }
}
