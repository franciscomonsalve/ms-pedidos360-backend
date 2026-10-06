package cl.duoc.pedidos360.notify.messaging.kitchen;

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
 * Dominio "cocina": consume la cola de tickets de cocina (nombre en
 * application.yml) y emite el ticket cuando un pedido es aceptado.
 */
@Component
public class KitchenCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(KitchenCommandConsumer.class);

    private final CommandProcessor processor;

    public KitchenCommandConsumer(CommandProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = "${pedidos360.messaging.rabbit.queues.kitchen.name}")
    public void onMessage(Message message, Channel channel) throws IOException {
        processor.process("kitchen", message, channel, this::printTicket);
    }

    private void printTicket(MessageEnvelope envelope) {
        Object orderId = envelope.require("orderId");
        Object items = envelope.require("items");
        // Aqui se integraria la impresora/pantalla de cocina; se simula la emision.
        log.info("Ticket de cocina emitido para el pedido {} ({} items)", orderId, items);
    }

}
