package cl.duoc.pedidos360.rabbitadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parametros de la API HTTP de gestion de RabbitMQ (application.yml: pedidos360.rabbit-admin). */
@ConfigurationProperties(prefix = "pedidos360.rabbit-admin")
public record RabbitAdminProperties(Management management) {

    public record Management(String url, String username, String password, String vhost) {}
}
