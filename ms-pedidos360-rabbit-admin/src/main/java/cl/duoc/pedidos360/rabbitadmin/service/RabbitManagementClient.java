package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.config.RabbitAdminProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura (solo lectura) de la API HTTP de gestion de RabbitMQ. AMQP no permite
 * listar colas/exchanges/bindings, por eso los listados salen de aqui; las
 * altas y bajas siempre van por AMQP (RabbitAdminService).
 */
@Component
public class RabbitManagementClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String vhost;

    public RabbitManagementClient(RestClient rabbitManagementRestClient, RabbitAdminProperties props) {
        this.client = rabbitManagementRestClient;
        this.vhost = props.management().vhost();
    }

    public List<Map<String, Object>> listQueues() {
        return fetch("/api/queues", "name", "durable", "state", "messages", "consumers", "arguments");
    }

    public List<Map<String, Object>> listExchanges() {
        return fetch("/api/exchanges", "name", "type", "durable", "internal");
    }

    public List<Map<String, Object>> listBindings() {
        return fetch("/api/bindings", "source", "destination", "destination_type", "routing_key");
    }

    /** Pide todos los recursos, se queda con los del vhost configurado y recorta los campos. */
    private List<Map<String, Object>> fetch(String path, String... fields) {
        List<Map<String, Object>> raw = client.get().uri(path).retrieve().body(LIST_OF_MAPS);
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> vhost.equals(item.get("vhost")))
                .map(item -> {
                    Map<String, Object> slim = new LinkedHashMap<>();
                    for (String field : fields) {
                        slim.put(field, item.get(field));
                    }
                    return slim;
                })
                .toList();
    }
}
