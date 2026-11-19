package cl.duoc.pedidos360.notify.messaging.stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cliente Kafka de este servicio, para el grupo
 * {@code pedidos360-notify-stream} que vigila {@code orders.events}.
 *
 * MISMAS TRES DEFENSAS QUE EN ms-pedidos360-audit
 *   ErrorHandlingDeserializer    un JSON corrupto no deja al consumidor
 *                                girando en bucle sobre el mismo offset.
 *   FixedBackOff(1s, 2)          dos reintentos para fallos transitorios.
 *   DeadLetterPublishingRecoverer lo que agota reintentos va a orders.events.DLT.
 *
 * CONCURRENCIA 1, Y AQUI ESTA LA DIFERENCIA CON audit
 * audit usa concurrencia 3 (una por particion) porque persiste en base de
 * datos y le interesa el paralelismo. Este consumidor solo observa y cuenta,
 * asi que un hilo basta: las tres particiones se le asignan al mismo
 * consumidor. Es el mismo mecanismo con otra decision, y sirve para explicar
 * en la presentacion que la concurrencia se elige por caso de uso y no por
 * copiar la configuracion de al lado.
 */
@Configuration
public class KafkaStreamConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaStreamConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        config.put(org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);

        JsonSerializer<Object> json = new JsonSerializer<>();
        json.setAddTypeInfo(false);

        // Cuando falla la deserializacion hay que reenviar los BYTES originales,
        // no un objeto: serializarlos como JSON perderia el contenido que se
        // quiere conservar para analizar.
        //
        // LinkedHashMap y NO Map.of: el orden importa. Con assignable=true se
        // devuelve el PRIMER delegado compatible, y byte[] es compatible con
        // Object. Si Object.class se evaluara primero, los bytes crudos
        // terminarian como una cadena JSON en base64. Map.of no garantiza orden
        // de iteracion, asi que el resultado dependia del azar.
        Map<Class<?>, Serializer<?>> delegados = new LinkedHashMap<>();
        delegados.put(byte[].class, new ByteArraySerializer());
        delegados.put(Object.class, json);

        // assignable=true: por defecto este serializador busca la clase EXACTA,
        // asi que Object.class no cubriria a ningun objeto concreto y el envio
        // a la DLT fallaria con "No matching delegate for type".
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                config, new StringSerializer(), new DelegatingByTypeSerializer(delegados, true)));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderStreamListenerFactory(
            KafkaTemplate<String, Object> dltKafkaTemplate) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderEvent.class.getName());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.pedidos360.*");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(dltKafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (registro, excepcion) -> {
                    log.error("[kafka] mensaje enviado a DLT (topic={}, particion={}, offset={}) motivo: {}",
                            registro.topic(), registro.partition(), registro.offset(), excepcion.getMessage());
                    recuperador.accept(registro, excepcion);
                },
                new FixedBackOff(1_000L, 2L));
        errorHandler.addNotRetryableExceptions(EventoNoProcesableException.class);

        ConcurrentKafkaListenerContainerFactory<String, OrderEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(1);
        return factory;
    }
}
