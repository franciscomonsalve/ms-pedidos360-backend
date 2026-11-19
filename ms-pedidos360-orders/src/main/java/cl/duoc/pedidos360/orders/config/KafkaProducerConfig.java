package cl.duoc.pedidos360.orders.config;

import cl.duoc.pedidos360.orders.messaging.OrderEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Cliente productor de Kafka para los eventos de dominio del pedido.
 *
 * GARANTIAS DE ENTREGA
 * acks=all + enable.idempotence=true es la combinacion que da "exactamente una
 * vez por particion" del lado del productor:
 *   - acks=all      el lider espera a que las replicas EN SINCRONIA tengan el
 *                   mensaje antes de confirmar. Con min.insync.replicas=2 en el
 *                   broker, eso significa que un broker puede caerse sin que se
 *                   pierda la escritura.
 *   - idempotence   si un reintento duplica un envio por un timeout de red, el
 *                   broker lo detecta por el numero de secuencia y lo descarta.
 *                   Sin esto, "reintentar" significa "arriesgarse a duplicar".
 *
 * SOBRE LOS TIMEOUTS
 * La version anterior tenia max.block=3s y delivery.timeout=5s con el comentario
 * "sin Kafka local, evita que el envio bloquee la creacion del pedido". Eso
 * estaba pensado para correr SIN Kafka. Con un cluster real esos valores son
 * demasiado agresivos: cortan los reintentos antes de que el productor pueda
 * recuperarse de un cambio de lider, que es un evento normal en un cluster.
 *
 * El compromiso de aqui: max.block 5s para que crear un pedido nunca se quede
 * colgado si el cluster no responde, pero delivery.timeout de 2 minutos para
 * que los reintentos tengan margen real. Son dos cosas distintas: una es
 * cuanto espera el hilo que llama, otra cuanto insiste el productor en segundo
 * plano.
 *
 * SOBRE LAS CABECERAS DE TIPO
 * ADD_TYPE_INFO_HEADERS=false es obligatorio aqui. Por defecto JsonSerializer
 * escribe el nombre completo de la clase Java en una cabecera, y el consumidor
 * intenta instanciar ESA clase. Como cada microservicio tiene su propia copia
 * del record en su propio paquete (cl.duoc.pedidos360.orders.messaging.OrderEvent
 * frente a ...audit.messaging.OrderEvent), el consumidor fallaria al
 * deserializar. Sin la cabecera, cada consumidor deserializa contra el tipo que
 * el declara, que es lo que queremos: el contrato es el JSON, no la clase.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, OrderEvent> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durabilidad
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // Tiempos: ver la nota de arriba.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        // 10 ms de espera agrupa los eventos de una rafaga en un solo lote sin
        // que se note la latencia.
        config.put(ProducerConfig.LINGER_MS_CONFIG, 10);

        // El consumidor declara su propio tipo; ver la nota de arriba.
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, OrderEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
