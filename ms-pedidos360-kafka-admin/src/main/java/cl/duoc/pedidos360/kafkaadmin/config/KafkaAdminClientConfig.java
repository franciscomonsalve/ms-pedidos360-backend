package cl.duoc.pedidos360.kafkaadmin.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Cliente de administracion de Kafka.
 *
 * El nombre de la clase lleva el prefijo Kafka- a proposito: llamarla
 * AdminClientConfig chocaria con org.apache.kafka.clients.admin.AdminClientConfig,
 * que es justo la clase de constantes que se usa aqui dentro.
 *
 * Se declara con destroyMethod para que Spring lo cierre al apagar el servicio:
 * AdminClient mantiene conexiones e hilos propios, y dejarlo abierto filtra
 * recursos en cada reinicio.
 *
 * Los timeouts son cortos a proposito. Este servicio es una API sincrona que
 * contesta a un humano o a un frontend: si el cluster no responde en unos
 * segundos, es mejor devolver un 502 claro que dejar la peticion colgada.
 */
@Configuration
public class KafkaAdminClientConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean(destroyMethod = "close")
    public AdminClient adminClient() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "pedidos360-kafka-admin");
        return AdminClient.create(config);
    }
}
