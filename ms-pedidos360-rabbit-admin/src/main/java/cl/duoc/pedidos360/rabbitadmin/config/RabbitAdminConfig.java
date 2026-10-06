package cl.duoc.pedidos360.rabbitadmin.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Beans de infraestructura: serializacion JSON AMQP y cliente HTTP hacia la API de gestion. */
@Configuration
public class RabbitAdminConfig {

    /** Los mensajes de prueba se publican como JSON (mismo formato que el resto del sistema). */
    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RestClient rabbitManagementRestClient(RabbitAdminProperties props) {
        var mgmt = props.management();
        String credentials = mgmt.username() + ":" + mgmt.password();
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(mgmt.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
    }
}
