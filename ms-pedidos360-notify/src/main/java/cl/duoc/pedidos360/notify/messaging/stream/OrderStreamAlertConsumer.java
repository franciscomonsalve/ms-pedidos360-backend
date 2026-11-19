package cl.duoc.pedidos360.notify.messaging.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consume {@code orders.events} en el grupo {@code pedidos360-notify-stream}.
 *
 * EL SEGUNDO GRUPO SOBRE EL MISMO TOPICO, Y ES LA DEMOSTRACION CENTRAL
 * ms-pedidos360-audit lee ESTE MISMO topico con el grupo pedidos360-audit.
 * Los dos reciben TODOS los eventos, porque cada grupo lleva su propio offset.
 *
 * Que se puede demostrar con esto, y vale 15% en la presentacion:
 *   1. Bajar este servicio: docker stop pedidos360-notify
 *   2. Cambiar estados de pedidos desde la aplicacion
 *   3. En Kafka UI, el grupo pedidos360-notify-stream acumula LAG mientras
 *      pedidos360-audit sigue en lag 0. Son independientes.
 *   4. Levantarlo: consume lo pendiente y el lag vuelve a 0 sin perder nada.
 *
 * Si en vez de DIVIDIR responsabilidades quisieramos REPARTIR carga, se
 * levantarian dos instancias con el MISMO group-id y Kafka les asignaria
 * particiones distintas: cada evento lo procesaria una sola de las dos.
 */
@Component
public class OrderStreamAlertConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderStreamAlertConsumer.class);

    private final AlertService alertService;

    public OrderStreamAlertConsumer(AlertService alertService) {
        this.alertService = alertService;
    }

    @KafkaListener(
            topics = "${pedidos360.messaging.topic-orders-events}",
            groupId = "pedidos360-notify-stream",
            containerFactory = "orderStreamListenerFactory")
    public void onOrderEvent(OrderEvent evento,
                             @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
                             @Header(KafkaHeaders.OFFSET) long offset) {

        if (evento == null || !evento.esValido()) {
            throw new EventoNoProcesableException(
                    "evento incompleto en particion %d offset %d".formatted(particion, offset));
        }

        log.debug("[stream] recibido {} de particion {} offset {}",
                evento.eventType(), particion, offset);

        alertService.evaluar(evento);
    }
}
