package cl.duoc.pedidos360.audit.config;

import cl.duoc.pedidos360.audit.messaging.EventoNoProcesableException;
import cl.duoc.pedidos360.audit.messaging.OrderEvent;
import cl.duoc.pedidos360.audit.messaging.TimelineEvent;
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
import org.springframework.kafka.core.ConsumerFactory;
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
 * Cliente consumidor de Kafka: deserializacion segura, reintentos y cola de
 * mensajes muertos.
 *
 * LAS TRES DEFENSAS, DE FUERA HACIA DENTRO
 *
 * 1. ErrorHandlingDeserializer
 *    Si el cuerpo del mensaje no es un JSON valido, el deserializador lanza una
 *    excepcion ANTES de que el listener se ejecute. Sin esta envoltura, el
 *    contenedor no puede avanzar el offset, vuelve a leer el mismo mensaje, y
 *    el consumidor entra en un bucle infinito consumiendo CPU sin progresar.
 *    Es el fallo mas desagradable de Kafka porque no se cae: se queda girando.
 *    Con la envoltura, el error se convierte en un registro nulo que el
 *    manejador de errores puede desviar.
 *
 * 2. DefaultErrorHandler con FixedBackOff(1s, 2)
 *    Dos reintentos separados por un segundo. Cubre los fallos TRANSITORIOS:
 *    la base no responde un instante, un cambio de lider de particion.
 *
 * 3. DeadLetterPublishingRecoverer
 *    Agotados los reintentos, el mensaje se publica en <topico>.DLT y el
 *    consumidor sigue con el siguiente. El equivalente exacto de la DLQ de
 *    RabbitMQ que ya usa este proyecto: cambia el mecanismo, no el criterio.
 *
 * QUE NO SE REINTENTA
 * EventoNoProcesableException esta marcada como no reintentable. Un payload
 * invalido no va a volverse valido por esperar un segundo: reintentarlo son
 * dos segundos perdidos antes de acabar en el mismo sitio. Es la misma
 * distincion que CommandProcessor hace en RabbitMQ entre "error transitorio" y
 * "error no recuperable".
 *
 * POR QUE DOS FABRICAS DE CONTENEDORES
 * El productor publica sin cabeceras de tipo, asi que cada consumidor declara
 * contra que tipo deserializa. Como los dos topicos llevan tipos distintos
 * (OrderEvent y TimelineEvent), hace falta una fabrica por tipo.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ------------------------------------------------------------- Productor
    // Lo usa el publicador de audit.timeline y tambien el recuperador de la DLT.

    /**
     * El serializador delega por tipo a proposito. Cuando la deserializacion
     * falla, lo que hay que reenviar a la DLT son los BYTES ORIGINALES del
     * mensaje, no un objeto: si se intentara serializar como JSON, se perderia
     * justo el contenido que se quiere conservar para analizarlo.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
        config.put(org.apache.kafka.clients.producer.ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(org.apache.kafka.clients.producer.ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);

        JsonSerializer<Object> json = new JsonSerializer<>();
        json.setAddTypeInfo(false);

        // LinkedHashMap y NO Map.of: el orden importa. Con assignable=true el
        // serializador devuelve el PRIMER delegado cuyo tipo sea compatible, y
        // byte[] es compatible con Object. Si Object.class se evaluara primero,
        // los bytes crudos de un mensaje ilegible se serializarian como una
        // cadena JSON en base64 -justo el contenido que la DLT debe conservar
        // intacto para poder analizarlo-. Map.of no garantiza orden de
        // iteracion, asi que el resultado dependia del azar.
        Map<Class<?>, Serializer<?>> delegados = new LinkedHashMap<>();
        delegados.put(byte[].class, new ByteArraySerializer());
        delegados.put(Object.class, json);

        // El segundo argumento (assignable=true) es OBLIGATORIO y no es obvio:
        // por defecto DelegatingByTypeSerializer busca la clase EXACTA en el
        // mapa, asi que tener Object.class registrado no cubre a TimelineEvent
        // y el envio falla con "No matching delegate for type". Con assignable
        // activado busca el primer delegado cuyo tipo sea compatible, que es lo
        // que convierte a Object.class en el caso por defecto.
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                config, new StringSerializer(), new DelegatingByTypeSerializer(delegados, true)));
    }

    // --------------------------------------------------------- Manejo de error

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(kafkaTemplate);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (registro, excepcion) -> {
                    log.error("[kafka] mensaje enviado a DLT (topic={}, particion={}, offset={}) motivo: {}",
                            registro.topic(), registro.partition(), registro.offset(),
                            excepcion.getMessage());
                    recuperador.accept(registro, excepcion);
                },
                new FixedBackOff(1_000L, 2L));

        // Un payload invalido no mejora con reintentos: va directo a la DLT.
        handler.addNotRetryableExceptions(EventoNoProcesableException.class);

        // Ojo con el nombre: Spring invoca este listener en CADA entrega,
        // incluida la primera. "intento 1" significa el envio original, no un
        // reintento. Una excepcion no reintentable se ve asi: intento 1 y
        // acto seguido la DLT, sin espera de un segundo entre medias.
        handler.setRetryListeners((registro, excepcion, intento) ->
                log.warn("[kafka] intento {} de topic={} offset={}: {}",
                        intento, registro.topic(), registro.offset(), excepcion.getMessage()));

        return handler;
    }

    // ------------------------------------------------------------ Consumidores

    private Map<String, Object> propiedadesBase() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "cl.duoc.pedidos360.*");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // El offset se confirma cuando el listener termina sin excepcion, no al
        // recibir. Mismo criterio que el ACK manual de RabbitMQ.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        return config;
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> fabrica(
            Class<T> tipo, DefaultErrorHandler errorHandler, int concurrencia) {

        Map<String, Object> config = propiedadesBase();
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, tipo.getName());

        ConsumerFactory<String, T> consumerFactory = new DefaultKafkaConsumerFactory<>(config);

        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(concurrencia);
        return factory;
    }

    /**
     * Concurrencia 3 porque el topico tiene 3 particiones: el contenedor levanta
     * tres hilos consumidores y Kafka le asigna una particion a cada uno. Poner
     * mas hilos que particiones dejaria hilos ociosos; poner menos dejaria
     * particiones sin atender por ese consumidor.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderEventListenerFactory(
            DefaultErrorHandler errorHandler) {
        return fabrica(OrderEvent.class, errorHandler, 3);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TimelineEvent> timelineEventListenerFactory(
            DefaultErrorHandler errorHandler) {
        return fabrica(TimelineEvent.class, errorHandler, 3);
    }
}
