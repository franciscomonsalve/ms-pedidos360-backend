package cl.duoc.pedidos360.kafkaadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Contratos de entrada y salida de la API de administracion de Kafka. La
 * validacion se declara aqui (Bean Validation) para que ningun controlador ni
 * servicio acepte nombres vacios o configuraciones invalidas.
 */
public final class KafkaAdminDtos {

    private KafkaAdminDtos() {}

    /**
     * Kafka admite letras, digitos, punto, guion bajo y guion. Ademas desaconseja
     * mezclar '.' y '_' en el mismo nombre porque colisionan en las metricas.
     */
    public static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$";
    public static final String NAME_MESSAGE =
            "debe empezar con letra o digito y usar solo letras, digitos, '.', '_' o '-' (max. 249)";

    /**
     * @param partitions  cuantos consumidores de un mismo grupo pueden trabajar
     *                    en paralelo. No se puede reducir despues.
     * @param replicationFactor cuantas copias del dato. No puede superar el
     *                    numero de brokers vivos.
     * @param configs     retention.ms, cleanup.policy, min.insync.replicas, etc.
     */
    public record CreateTopicRequest(
            @NotBlank(message = "el nombre del topico es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String name,

            @Min(value = 1, message = "minimo 1 particion")
            @Max(value = 100, message = "maximo 100 particiones") Integer partitions,

            @Min(value = 1, message = "minimo 1 replica")
            @Max(value = 10, message = "maximo 10 replicas") Integer replicationFactor,

            Map<String, String> configs) {

        public int particionesONull() {
            return partitions != null ? partitions : 3;
        }

        public short replicasONull() {
            return replicationFactor != null ? replicationFactor.shortValue() : (short) 3;
        }
    }

    /** Solo se pueden modificar configuraciones; particiones y replicas no. */
    public record UpdateTopicConfigRequest(Map<String, String> configs) {}

    public record PartitionInfo(
            int partition,
            Integer leader,
            List<Integer> replicas,
            List<Integer> inSyncReplicas) {}

    public record TopicDetail(
            String name,
            int partitionCount,
            int replicationFactor,
            List<PartitionInfo> partitions,
            Map<String, String> configs) {}

    public record TopicSummary(String name, int partitionCount, int replicationFactor) {}

    /**
     * Estado de un grupo consumidor por particion.
     *
     * @param lag lo que le falta por consumir: logEndOffset - currentOffset.
     *            Es la metrica operacional mas importante de Kafka. Si crece de
     *            forma sostenida, el consumidor no da abasto o esta caido.
     */
    public record GroupPartitionOffsets(
            String topic,
            int partition,
            Long currentOffset,
            Long logEndOffset,
            Long lag) {}

    public record ConsumerGroupDetail(
            String groupId,
            String state,
            int members,
            long lagTotal,
            List<GroupPartitionOffsets> offsets) {}

    public record ConsumerGroupSummary(String groupId, String state) {}

    public record BrokerInfo(int id, String host, int port, boolean controller) {}

    public record ClusterInfo(String clusterId, int brokerCount, List<BrokerInfo> brokers) {}
}
