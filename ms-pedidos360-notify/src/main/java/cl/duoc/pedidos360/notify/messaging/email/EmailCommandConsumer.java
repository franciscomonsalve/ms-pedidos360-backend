package cl.duoc.pedidos360.notify.messaging.email;

import cl.duoc.pedidos360.notify.dto.MessageEnvelope;
import cl.duoc.pedidos360.notify.messaging.support.CommandProcessor;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dominio "notificaciones por email": consume la cola de email (nombre en
 * application.yml) y envia el aviso de cambio de estado al cliente.
 */
@Component
public class EmailCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailCommandConsumer.class);

    private final CommandProcessor processor;

    public EmailCommandConsumer(CommandProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = "${pedidos360.messaging.rabbit.queues.email.name}")
    public void onMessage(Message message, Channel channel) throws IOException {
        processor.process("email", message, channel, this::sendNotification);
    }

    private void sendNotification(MessageEnvelope envelope) {
        Object orderId = envelope.require("orderId");
        Object customerId = envelope.require("customerId");
        Object status = envelope.require("status");
        // Aqui se integraria el proveedor real de email/push; se simula el envio.
        log.info("Notificando al cliente {} que su pedido {} paso a estado {}", customerId, orderId, status);
    }

}
