package cl.duoc.pedidos360.orders.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Serializacion JSON de los mensajes publicados en RabbitMQ (envelope comun). */
@Configuration
public class RabbitConverterConfig {

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
