package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.config.RabbitMQConfig;
import cl.duoc.pedidos360.orders.entity.Order;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publica comandos asincronos (RabbitMQ) cuando cambia el estado de un pedido:
 * envio de notificacion al cliente, ticket de cocina, generacion de factura.
 */
@Component
public class CommandPublisher {

    private final RabbitTemplate rabbitTemplate;

    public CommandPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishEmailNotification(Order order) {
        MessageEnvelope envelope = MessageEnvelope.of(
                "email.send",
                String.valueOf(order.getId()),
                Map.of(
                        "orderId", order.getId(),
                        "customerId", order.getCustomerId(),
                        "status", order.getStatus().name()
                ));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DIRECT, "email.send", envelope);
    }

    public void publishKitchenTicket(Order order) {
        MessageEnvelope envelope = MessageEnvelope.of(
                "kitchen.ticket",
                String.valueOf(order.getId()),
                Map.of("orderId", order.getId(), "items", order.getItems().size()));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DIRECT, "kitchen.ticket", envelope);
    }

    public void publishInvoiceGeneration(Order order) {
        MessageEnvelope envelope = MessageEnvelope.of(
                "invoice.gen",
                String.valueOf(order.getId()),
                Map.of("orderId", order.getId(), "totalAmount", order.getTotalAmount()));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_DIRECT, "invoice.gen", envelope);
    }
}
