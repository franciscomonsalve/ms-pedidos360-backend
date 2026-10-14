package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.config.RabbitMessagingProperties;
import cl.duoc.pedidos360.orders.entity.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publica comandos asincronos (RabbitMQ) cuando cambia el estado de un pedido:
 * notificacion al cliente, ticket de cocina y generacion de factura.
 *
 * - Email: exchange TOPIC, clave {@code cmd.email.<estado>} (permite enrutar
 *   por estado en el futuro sin tocar al publicador).
 * - Kitchen / invoice: exchange DIRECT, punto a punto.
 *
 * Los nombres salen de application.yml (RabbitMessagingProperties). Un fallo
 * del broker se registra en log pero NO se propaga: la mensajeria es
 * desacoplada y no debe revertir ni romper el cambio de estado del pedido.
 */
@Component
public class CommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommandPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMessagingProperties props;

    public CommandPublisher(RabbitTemplate rabbitTemplate, RabbitMessagingProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    public void publishEmailNotification(Order order) {
        var queue = props.queues().email();
        MessageEnvelope envelope = MessageEnvelope.of(
                queue.routingKey(),
                String.valueOf(order.getId()),
                Map.of(
                        "orderId", order.getId(),
                        "customerId", order.getCustomerId(),
                        "status", order.getStatus().name()
                ));
        String routingKey = queue.topicKey(order.getStatus().name().toLowerCase());
        send(props.exchanges().topic(), routingKey, envelope);
    }

    public void publishKitchenTicket(Order order) {
        var queue = props.queues().kitchen();
        MessageEnvelope envelope = MessageEnvelope.of(
                queue.routingKey(),
                String.valueOf(order.getId()),
                Map.of("orderId", order.getId(), "items", order.getItems().size()));
        send(props.exchanges().direct(), queue.routingKey(), envelope);
    }

    public void publishInvoiceGeneration(Order order) {
        var queue = props.queues().invoice();
        MessageEnvelope envelope = MessageEnvelope.of(
                queue.routingKey(),
                String.valueOf(order.getId()),
                Map.of("orderId", order.getId(), "totalAmount", order.getTotalAmount()));
        send(props.exchanges().direct(), queue.routingKey(), envelope);
    }

    private void send(String exchange, String routingKey, MessageEnvelope envelope) {
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, envelope);
            log.info("Comando publicado exchange={} routingKey={} eventId={}",
                    exchange, routingKey, envelope.eventId());
        } catch (AmqpException ex) {
            log.error("No se pudo publicar el comando exchange={} routingKey={} eventId={}: {}",
                    exchange, routingKey, envelope.eventId(), ex.getMessage());
        }
    }
}
