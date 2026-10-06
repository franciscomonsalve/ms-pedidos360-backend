package cl.duoc.pedidos360.orders.config;

import cl.duoc.pedidos360.orders.config.RabbitMessagingProperties.QueueSpec;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia de RabbitMQ del dominio de pedidos: un caso de uso por bloque
 * (email, kitchen, invoice), cada uno con su cola principal, su DLQ y sus
 * bindings. Es SOLO configuracion: la logica de negocio no declara colas.
 *
 * <pre>
 *  cmd.direct (direct) --email.send-----> q.cmd.email   --(rechazo)--> cmd.dead.dlx --> q.cmd.email.dlq
 *  cmd.topic  (topic)  --cmd.email.#----> q.cmd.email
 *  cmd.direct          --kitchen.ticket-> q.cmd.kitchen --(rechazo)--> cmd.dead.dlx --> q.cmd.kitchen.dlq
 *  cmd.topic           --cmd.kitchen.#--> q.cmd.kitchen
 *  cmd.direct          --invoice.gen----> q.cmd.invoice --(rechazo)--> cmd.dead.dlx --> q.cmd.invoice.dlq
 *  cmd.topic           --cmd.invoice.#--> q.cmd.invoice
 * </pre>
 *
 * ms-pedidos360-notify declara la misma topologia desde el mismo bloque de
 * application.yml (la redeclaracion identica es idempotente), de modo que no
 * importa cual de los dos servicios arranque primero.
 */
@Configuration
@EnableConfigurationProperties(RabbitMessagingProperties.class)
public class RabbitTopologyConfig {

    private final RabbitMessagingProperties props;

    public RabbitTopologyConfig(RabbitMessagingProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------ Exchanges

    @Bean
    public DirectExchange cmdDirectExchange() {
        return new DirectExchange(props.exchanges().direct());
    }

    @Bean
    public TopicExchange cmdTopicExchange() {
        return new TopicExchange(props.exchanges().topic());
    }

    @Bean
    public DirectExchange cmdDeadLetterExchange() {
        return new DirectExchange(props.exchanges().deadLetter());
    }

    // ---------------------------------------------------------------- Email

    @Bean
    public Queue emailQueue() {
        return mainQueue(props.queues().email());
    }

    @Bean
    public Queue emailDlq() {
        return dlq(props.queues().email());
    }

    @Bean
    public Binding emailDirectBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdDirectExchange()).with(props.queues().email().routingKey());
    }

    @Bean
    public Binding emailTopicBinding() {
        return BindingBuilder.bind(emailQueue()).to(cmdTopicExchange()).with(props.queues().email().topicPattern());
    }

    @Bean
    public Binding emailDlqBinding() {
        return BindingBuilder.bind(emailDlq()).to(cmdDeadLetterExchange()).with(props.queues().email().routingKey());
    }

    // -------------------------------------------------------------- Kitchen

    @Bean
    public Queue kitchenQueue() {
        return mainQueue(props.queues().kitchen());
    }

    @Bean
    public Queue kitchenDlq() {
        return dlq(props.queues().kitchen());
    }

    @Bean
    public Binding kitchenDirectBinding() {
        return BindingBuilder.bind(kitchenQueue()).to(cmdDirectExchange()).with(props.queues().kitchen().routingKey());
    }

    @Bean
    public Binding kitchenTopicBinding() {
        return BindingBuilder.bind(kitchenQueue()).to(cmdTopicExchange()).with(props.queues().kitchen().topicPattern());
    }

    @Bean
    public Binding kitchenDlqBinding() {
        return BindingBuilder.bind(kitchenDlq()).to(cmdDeadLetterExchange()).with(props.queues().kitchen().routingKey());
    }

    // -------------------------------------------------------------- Invoice

    @Bean
    public Queue invoiceQueue() {
        return mainQueue(props.queues().invoice());
    }

    @Bean
    public Queue invoiceDlq() {
        return dlq(props.queues().invoice());
    }

    @Bean
    public Binding invoiceDirectBinding() {
        return BindingBuilder.bind(invoiceQueue()).to(cmdDirectExchange()).with(props.queues().invoice().routingKey());
    }

    @Bean
    public Binding invoiceTopicBinding() {
        return BindingBuilder.bind(invoiceQueue()).to(cmdTopicExchange()).with(props.queues().invoice().topicPattern());
    }

    @Bean
    public Binding invoiceDlqBinding() {
        return BindingBuilder.bind(invoiceDlq()).to(cmdDeadLetterExchange()).with(props.queues().invoice().routingKey());
    }

    // ------------------------------------------------------------- Helpers

    /** Cola durable que, al ser rechazada, se re-enruta al DLX con la clave del caso de uso. */
    private Queue mainQueue(QueueSpec spec) {
        return QueueBuilder.durable(spec.name())
                .withArgument("x-dead-letter-exchange", props.exchanges().deadLetter())
                .withArgument("x-dead-letter-routing-key", spec.routingKey())
                .build();
    }

    private Queue dlq(QueueSpec spec) {
        return QueueBuilder.durable(spec.dlqName()).build();
    }
}
