package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica los eventos de dominio del pedido en el topico Kafka
 * {@code orders.events}, fuente de verdad para los consumidores de auditoria y
 * de notificaciones.
 *
 * LA CLAVE DEL MENSAJE ES EL orderId, Y ESO NO ES UN DETALLE
 * Kafka asigna particion por hash de la clave. Al usar el orderId:
 *   - todos los eventos de un mismo pedido caen en la MISMA particion, y Kafka
 *     garantiza el orden dentro de una particion. Un consumidor nunca vera
 *     "Entregado" antes que "Aceptado" para el mismo pedido.
 *   - pedidos distintos se reparten entre las 3 particiones, asi que tres
 *     consumidores del mismo grupo pueden trabajar en paralelo.
 * Sin clave, Kafka reparte round-robin y se pierde la garantia de orden.
 *
 * POR QUE EL FALLO NO TUMBA LA OPERACION
 * El envio es asincrono y el error se registra, no se propaga: si Kafka no
 * esta disponible, el pedido YA se guardo en la base y la respuesta HTTP ya
 * salio. Publicar el evento es una consecuencia del cambio de estado, no un
 * requisito para que ocurra.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    @Value("${pedidos360.messaging.topic-orders-events}")
    private String ordersEventsTopic;

    public OrderEventPublisher(KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Order order, OrderStatus previousStatus) {
        OrderEvent event = OrderEvent.from(order, previousStatus);
        String key = String.valueOf(order.getId());

        try {
            kafkaTemplate.send(ordersEventsTopic, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("No se pudo publicar {} del pedido {} en {}: {}",
                                    event.eventType(), event.orderId(), ordersEventsTopic,
                                    ex.getMessage());
                            return;
                        }
                        var meta = result.getRecordMetadata();
                        log.info("Evento publicado tipo={} eventId={} traceId={} topic={} particion={} offset={}",
                                event.eventType(), event.eventId(), event.traceId(),
                                meta.topic(), meta.partition(), meta.offset());
                    });
        } catch (Exception ex) {
            // Solo llega aqui si el productor no pudo ni encolar el envio
            // (por ejemplo, agotado max.block.ms sin metadatos del cluster).
            log.error("Kafka no disponible; se omite el evento {} del pedido {}: {}",
                    event.eventType(), event.orderId(), ex.getMessage());
        }
    }
}
