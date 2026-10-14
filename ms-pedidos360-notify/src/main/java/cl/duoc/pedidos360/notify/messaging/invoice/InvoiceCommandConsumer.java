package cl.duoc.pedidos360.notify.messaging.invoice;

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
 * Dominio "facturacion": consume la cola de generacion de facturas (nombre
 * en application.yml) y genera la factura cuando un pedido es entregado.
 */
@Component
public class InvoiceCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCommandConsumer.class);

    private final CommandProcessor processor;

    public InvoiceCommandConsumer(CommandProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = "${pedidos360.messaging.rabbit.queues.invoice.name}")
    public void onMessage(Message message, Channel channel) throws IOException {
        processor.process("invoice", message, channel, this::generateInvoice);
    }

    private void generateInvoice(MessageEnvelope envelope) {
        Object orderId = envelope.require("orderId");
        Object totalAmount = envelope.require("totalAmount");
        // Aqui se integraria el sistema de facturacion real; se simula la generacion.
        log.info("Factura generada para el pedido {} por un total de {}", orderId, totalAmount);
    }

}
