package cl.duoc.pedidos360.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nombres de exchanges, colas y routing keys de RabbitMQ, leidos desde
 * application.yml (prefijo pedidos360.messaging.rabbit). Es la unica fuente
 * de nombres del microservicio: ni la topologia ni el publicador escriben
 * nombres "a mano".
 */
@ConfigurationProperties(prefix = "pedidos360.messaging.rabbit")
public record RabbitMessagingProperties(Exchanges exchanges, Queues queues) {

    public record Exchanges(String direct, String topic, String deadLetter) {}

    public record Queues(QueueSpec email, QueueSpec kitchen, QueueSpec invoice) {}

    /**
     * @param name        nombre de la cola principal
     * @param routingKey  clave de enrutamiento en el exchange direct (y en el DLX)
     * @param topicPrefix prefijo de las claves en el exchange topic ({@code <prefijo>.#})
     */
    public record QueueSpec(String name, String routingKey, String topicPrefix) {

        /** La DLQ asociada se deriva del nombre de la cola principal. */
        public String dlqName() {
            return name + ".dlq";
        }

        /** Patron con el que la cola se enlaza al exchange topic. */
        public String topicPattern() {
            return topicPrefix + ".#";
        }

        /** Clave concreta para publicar por topic, ej. cmd.email.aceptado. */
        public String topicKey(String suffix) {
            return topicPrefix + "." + suffix;
        }
    }
}
