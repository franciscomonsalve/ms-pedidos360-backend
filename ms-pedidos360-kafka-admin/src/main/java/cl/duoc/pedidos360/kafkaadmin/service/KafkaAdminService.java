package cl.duoc.pedidos360.kafkaadmin.service;

import cl.duoc.pedidos360.kafkaadmin.dto.KafkaAdminDtos.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Administracion de la plataforma Kafka mediante {@link AdminClient}.
 *
 * POR QUE AdminClient Y NO LOS SCRIPTS kafka-topics.sh
 * Los scripts existen y se usan para el arranque reproducible del sistema
 * (infra/kafka-topics.sh). Esta API cubre otra necesidad: administrar en
 * caliente, desde fuera del servidor, con autenticacion por rol y respuestas
 * en JSON que puede consumir un frontend o un panel. Es la misma relacion que
 * en este proyecto tienen rabbitmqctl y ms-pedidos360-rabbit-admin.
 *
 * QUE SE PUEDE CAMBIAR Y QUE NO, QUE ES LA PARTE QUE SORPRENDE
 *   particiones          se pueden AUMENTAR, nunca reducir. Y aumentarlas
 *                        cambia el reparto por hash de la clave, asi que
 *                        mensajes de la misma clave pueden pasar a otra
 *                        particion y se pierde la garantia de orden historica.
 *   factor de replicacion no se cambia por aqui: requiere reasignar replicas
 *                        entre brokers (kafka-reassign-partitions).
 *   configuraciones      si se cambian en caliente: retention.ms,
 *                        cleanup.policy, min.insync.replicas, segment.ms...
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);

    private final AdminClient admin;

    public KafkaAdminService(AdminClient admin) {
        this.admin = admin;
    }

    // ------------------------------------------------------------- Topicos

    public List<TopicSummary> listarTopicos(boolean incluirInternos) {
        try {
            Set<String> nombres = admin.listTopics(new ListTopicsOptions().listInternal(incluirInternos))
                    .names().get();
            if (nombres.isEmpty()) {
                return List.of();
            }
            Map<String, TopicDescription> descripciones = admin.describeTopics(nombres).allTopicNames().get();
            return descripciones.values().stream()
                    .map(d -> new TopicSummary(
                            d.name(),
                            d.partitions().size(),
                            d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size()))
                    .sorted(Comparator.comparing(TopicSummary::name))
                    .toList();
        } catch (Exception ex) {
            throw traducir(ex, "no se pudieron listar los topicos");
        }
    }

    public TopicDetail describirTopico(String nombre) {
        try {
            TopicDescription d = admin.describeTopics(List.of(nombre)).allTopicNames().get().get(nombre);
            if (d == null) {
                throw new TopicoNoEncontradoException(nombre);
            }

            List<PartitionInfo> particiones = d.partitions().stream()
                    .map(KafkaAdminService::aPartitionInfo)
                    .toList();

            return new TopicDetail(
                    d.name(),
                    d.partitions().size(),
                    d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size(),
                    particiones,
                    leerConfigs(nombre));
        } catch (TopicoNoEncontradoException ex) {
            throw ex;
        } catch (Exception ex) {
            // Kafka responde "This server does not host this topic-partition",
            // que no dice QUE topico falta. Se traduce aqui, donde si se sabe.
            Throwable causa = (ex.getCause() != null) ? ex.getCause() : ex;
            if (causa instanceof UnknownTopicOrPartitionException) {
                throw new TopicoNoEncontradoException(nombre);
            }
            throw traducir(ex, "no se pudo describir el topico " + nombre);
        }
    }

    public TopicDetail crearTopico(CreateTopicRequest peticion) {
        NewTopic nuevo = new NewTopic(
                peticion.name(), peticion.particionesONull(), peticion.replicasONull());

        if (peticion.configs() != null && !peticion.configs().isEmpty()) {
            nuevo.configs(peticion.configs());
        }

        try {
            admin.createTopics(List.of(nuevo)).all().get();
            log.info("[kafka-admin] topico creado nombre={} particiones={} replicas={} configs={}",
                    peticion.name(), peticion.particionesONull(), peticion.replicasONull(),
                    peticion.configs());
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo crear el topico " + peticion.name());
        }
        return describirRecienCreado(peticion.name());
    }

    /**
     * Describe un topico recien creado, reintentando unas cuantas veces.
     *
     * POR QUE HACE FALTA ESPERAR
     * createTopics devuelve cuando el CONTROLADOR acepto la creacion, no cuando
     * todos los brokers conocen las nuevas particiones. Describir en el
     * instante siguiente puede dar "This server does not host this
     * topic-partition" contra un broker que todavia no recibio los metadatos.
     *
     * El sintoma es desconcertante: la API devuelve error y el topico existe.
     * Por eso se reintenta en lugar de propagar el fallo: la creacion YA ocurrio
     * y lo unico pendiente es la propagacion, que tarda milisegundos.
     */
    private TopicDetail describirRecienCreado(String nombre) {
        RuntimeException ultimo = null;
        for (int intento = 1; intento <= 10; intento++) {
            try {
                return describirTopico(nombre);
            } catch (TopicoNoEncontradoException | KafkaNoDisponibleException ex) {
                ultimo = ex;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.warn("[kafka-admin] el topico {} se creo pero sus metadatos no se propagaron a tiempo", nombre);
        throw ultimo != null ? ultimo : new TopicoNoEncontradoException(nombre);
    }

    public TopicDetail actualizarConfigs(String nombre, Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            throw new PeticionKafkaInvalidaException("hay que indicar al menos una configuracion");
        }

        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, nombre);
        List<AlterConfigOp> operaciones = configs.entrySet().stream()
                .map(e -> new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET))
                .toList();

        try {
            // incrementalAlterConfigs y no alterConfigs: el segundo REEMPLAZA
            // toda la configuracion del topico, asi que borraria en silencio
            // cualquier ajuste que no venga en esta peticion.
            admin.incrementalAlterConfigs(Map.of(recurso, operaciones)).all().get();
            log.info("[kafka-admin] configuracion actualizada topico={} cambios={}", nombre, configs);
            return describirTopico(nombre);
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo actualizar la configuracion de " + nombre);
        }
    }

    public TopicDetail aumentarParticiones(String nombre, int total) {
        try {
            TopicDescription actual = admin.describeTopics(List.of(nombre)).allTopicNames().get().get(nombre);
            if (actual == null) {
                throw new TopicoNoEncontradoException(nombre);
            }
            int actuales = actual.partitions().size();
            if (total <= actuales) {
                throw new PeticionKafkaInvalidaException(
                        "el topico ya tiene %d particiones; Kafka solo permite AUMENTAR, nunca reducir"
                                .formatted(actuales));
            }
            admin.createPartitions(Map.of(nombre, NewPartitions.increaseTo(total))).all().get();
            log.warn("[kafka-admin] particiones de {} aumentadas de {} a {}. ATENCION: el reparto "
                    + "por hash de la clave cambia, asi que mensajes nuevos de una clave pueden caer "
                    + "en otra particion y se pierde el orden respecto de los historicos.",
                    nombre, actuales, total);
            return describirTopico(nombre);
        } catch (PeticionKafkaInvalidaException | TopicoNoEncontradoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw traducir(ex, "no se pudieron aumentar las particiones de " + nombre);
        }
    }

    public void eliminarTopico(String nombre) {
        try {
            admin.deleteTopics(List.of(nombre)).all().get();
            log.info("[kafka-admin] topico eliminado nombre={}", nombre);
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo eliminar el topico " + nombre);
        }
    }

    // ------------------------------------------------------ Consumer groups

    public List<ConsumerGroupSummary> listarGrupos() {
        try {
            return admin.listConsumerGroups().all().get().stream()
                    .map(g -> new ConsumerGroupSummary(
                            g.groupId(),
                            g.state().map(Enum::name).orElse("DESCONOCIDO")))
                    .sorted(Comparator.comparing(ConsumerGroupSummary::groupId))
                    .toList();
        } catch (Exception ex) {
            throw traducir(ex, "no se pudieron listar los grupos de consumidores");
        }
    }

    /**
     * Calcula el LAG de un grupo: por cada particion, cuanto le falta por leer.
     *
     * Son dos consultas distintas que hay que cruzar:
     *   1. listConsumerGroupOffsets -> por donde va el grupo (current offset)
     *   2. listOffsets(latest)      -> donde termina el log (log-end offset)
     * El lag es la resta. Un offset nulo significa que el grupo nunca confirmo
     * nada en esa particion, no que vaya por cero.
     */
    public ConsumerGroupDetail describirGrupo(String grupo) {
        try {
            ConsumerGroupDescription descripcion =
                    admin.describeConsumerGroups(List.of(grupo)).all().get().get(grupo);
            if (descripcion == null) {
                throw new GrupoNoEncontradoException(grupo);
            }

            Map<TopicPartition, OffsetAndMetadata> actuales =
                    admin.listConsumerGroupOffsets(grupo).partitionsToOffsetAndMetadata().get();

            if (actuales.isEmpty()) {
                return new ConsumerGroupDetail(grupo, descripcion.state().name(),
                        descripcion.members().size(), 0L, List.of());
            }

            Map<TopicPartition, OffsetSpec> finales = new HashMap<>();
            actuales.keySet().forEach(tp -> finales.put(tp, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ultimos =
                    admin.listOffsets(finales).all().get();

            List<GroupPartitionOffsets> detalle = new ArrayList<>();
            long lagTotal = 0L;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> entrada : actuales.entrySet()) {
                TopicPartition tp = entrada.getKey();
                Long actual = entrada.getValue() != null ? entrada.getValue().offset() : null;
                Long fin = ultimos.containsKey(tp) ? ultimos.get(tp).offset() : null;

                Long lag = (actual != null && fin != null) ? Math.max(0L, fin - actual) : null;
                if (lag != null) {
                    lagTotal += lag;
                }
                detalle.add(new GroupPartitionOffsets(tp.topic(), tp.partition(), actual, fin, lag));
            }

            detalle.sort(Comparator.comparing(GroupPartitionOffsets::topic)
                    .thenComparingInt(GroupPartitionOffsets::partition));

            return new ConsumerGroupDetail(grupo, descripcion.state().name(),
                    descripcion.members().size(), lagTotal, detalle);
        } catch (GrupoNoEncontradoException ex) {
            throw ex;
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo describir el grupo " + grupo);
        }
    }

    public void eliminarGrupo(String grupo) {
        try {
            admin.deleteConsumerGroups(List.of(grupo)).all().get();
            log.info("[kafka-admin] grupo eliminado groupId={}", grupo);
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo eliminar el grupo " + grupo);
        }
    }

    // ---------------------------------------------------------- Cluster

    public ClusterInfo describirCluster() {
        try {
            DescribeClusterResult r = admin.describeCluster();
            Node controlador = r.controller().get();
            Collection<Node> nodos = r.nodes().get();

            List<BrokerInfo> brokers = nodos.stream()
                    .map(n -> new BrokerInfo(n.id(), n.host(), n.port(),
                            controlador != null && controlador.id() == n.id()))
                    .sorted(Comparator.comparingInt(BrokerInfo::id))
                    .toList();

            return new ClusterInfo(r.clusterId().get(), brokers.size(), brokers);
        } catch (Exception ex) {
            throw traducir(ex, "no se pudo describir el cluster");
        }
    }

    // ---------------------------------------------------------- Auxiliares

    private Map<String, String> leerConfigs(String topico) throws ExecutionException, InterruptedException {
        ConfigResource recurso = new ConfigResource(ConfigResource.Type.TOPIC, topico);
        Config config = admin.describeConfigs(List.of(recurso)).all().get().get(recurso);
        if (config == null) {
            return Map.of();
        }
        Map<String, String> salida = new TreeMap<>();
        for (ConfigEntry entrada : config.entries()) {
            // Solo lo que NO es el valor por defecto: es lo que alguien decidio
            // a proposito para este topico, y es lo que interesa auditar.
            if (entrada.source() != ConfigEntry.ConfigSource.DEFAULT_CONFIG) {
                salida.put(entrada.name(), entrada.value());
            }
        }
        return salida;
    }

    private static PartitionInfo aPartitionInfo(TopicPartitionInfo p) {
        return new PartitionInfo(
                p.partition(),
                p.leader() != null ? p.leader().id() : null,
                p.replicas().stream().map(Node::id).toList(),
                p.isr().stream().map(Node::id).toList());
    }

    /** Convierte las excepciones de Kafka en errores de dominio con mensaje util. */
    private RuntimeException traducir(Exception ex, String contexto) {
        Throwable causa = (ex instanceof ExecutionException && ex.getCause() != null) ? ex.getCause() : ex;

        if (causa instanceof TopicExistsException) {
            return new TopicoYaExisteException(causa.getMessage());
        }
        if (causa instanceof UnknownTopicOrPartitionException) {
            return new TopicoNoEncontradoException(causa.getMessage());
        }
        if (causa instanceof InvalidReplicationFactorException) {
            return new PeticionKafkaInvalidaException(
                    "factor de replicacion invalido: no puede superar el numero de brokers vivos ("
                            + causa.getMessage() + ")");
        }
        if (causa instanceof InvalidPartitionsException) {
            return new PeticionKafkaInvalidaException("numero de particiones invalido: " + causa.getMessage());
        }
        if (ex instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.error("[kafka-admin] {}: {}", contexto, causa.getMessage());
        return new KafkaNoDisponibleException(contexto + ": " + causa.getMessage());
    }
}
