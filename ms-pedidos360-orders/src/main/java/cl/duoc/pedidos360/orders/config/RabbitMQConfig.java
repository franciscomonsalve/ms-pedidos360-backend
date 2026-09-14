package cl.duoc.pedidos360.orders.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declara la topologia de RabbitMQ usada por el dominio de pedidos para
 * publicar comandos asincronos (notificacion, cocina, facturacion).
 * Cada cola principal tiene su DLQ asociada segun el diseño del caso.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_DIRECT = "cmd.direct";
    public static final String EXCHANGE_TOPIC = "cmd.topic";
    public static final String EXCHANGE_DLX = "cmd.dead.dlx";

    public static final String Q_EMAIL = "q.cmd.email";
    public static final String Q_KITCHEN = "q.cmd.kitchen";
    public static final String Q_INVOICE = "q.cmd.invoice";

    @Bean
    public DirectExchange cmdDirectExchange() {
        return new DirectExchange(EXCHANGE_DIRECT);
    }

    @Bean
    public TopicExchange cmdTopicExchange() {
        return new TopicExchange(EXCHANGE_TOPIC);
    }

    @Bean
    public DirectExchange cmdDeadLetterExchange() {
        return new DirectExchange(EXCHANGE_DLX);
    }

    private Queue mainQueue(String name, String dlqRoutingKey) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
                .build();
    }

    @Bean
    public Queue emailQueue() { return mainQueue(Q_EMAIL, "email.send"); }

    @Bean
    public Queue kitchenQueue() { return mainQueue(Q_KITCHEN, "kitchen.ticket"); }

    @Bean
    public Queue invoiceQueue() { return mainQueue(Q_INVOICE, "invoice.gen"); }

    @Bean
    public Queue emailDlq() { return QueueBuilder.durable(Q_EMAIL + ".dlq").build(); }

    @Bean
    public Queue kitchenDlq() { return QueueBuilder.durable(Q_KITCHEN + ".dlq").build(); }

    @Bean
    public Queue invoiceDlq() { return QueueBuilder.durable(Q_INVOICE + ".dlq").build(); }

    @Bean
    public Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirectExchange()).with("email.send");
    }

    @Bean
    public Binding kitchenBinding() {
        return BindingBuilder.bind(kitchenQueue()).to(cmdDirectExchange()).with("kitchen.ticket");
    }

    @Bean
    public Binding invoiceBinding() {
        return BindingBuilder.bind(invoiceQueue()).to(cmdDirectExchange()).with("invoice.gen");
    }

    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(cmdDeadLetterExchange()).with("email.send");
    }

    @Bean
    public Binding kitchenDlqBinding() {
        return BindingBuilder.bind(kitchenDlq()).to(cmdDeadLetterExchange()).with("kitchen.ticket");
    }

    @Bean
    public Binding invoiceDlqBinding() {
        return BindingBuilder.bind(invoiceDlq()).to(cmdDeadLetterExchange()).with("invoice.gen");
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
