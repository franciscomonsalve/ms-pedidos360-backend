package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publica los eventos de negocio del pedido en el topico Kafka "orders.events",
 * fuente de verdad consumida por ms-pedidos360-report (KPIs) y ms-pedidos360-audit
 * (timeline de auditoria).
 */
@Component
public class OrderEventPublisher {

    private final KafkaTemplate<String, MessageEnvelope> kafkaTemplate;

    @Value("${pedidos360.messaging.topic-orders-events}")
    private String ordersEventsTopic;

    public OrderEventPublisher(KafkaTemplate<String, MessageEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Order order, OrderStatus previousStatus) {
        String eventType = mapEventType(order.getStatus());
        MessageEnvelope envelope = MessageEnvelope.of(
                eventType,
                String.valueOf(order.getId()),
                Map.of(
                        "orderId", order.getId(),
                        "customerId", order.getCustomerId(),
                        "previousStatus", previousStatus != null ? previousStatus.name() : "NONE",
                        "status", order.getStatus().name(),
                        "totalAmount", order.getTotalAmount()
                ));
        kafkaTemplate.send(ordersEventsTopic, String.valueOf(order.getId()), envelope);
    }

    private String mapEventType(OrderStatus status) {
        return switch (status) {
            case CREADO -> "OrderCreated";
            case ACEPTADO -> "OrderAccepted";
            case EN_PREPARACION -> "OrderPreparing";
            case DESPACHADO -> "OrderDispatched";
            case ENTREGADO -> "OrderDelivered";
            case CANCELADO -> "OrderCancelled";
        };
    }
}
