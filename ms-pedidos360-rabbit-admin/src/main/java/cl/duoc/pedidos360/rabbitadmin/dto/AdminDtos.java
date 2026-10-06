package cl.duoc.pedidos360.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Contratos de entrada/salida de la API de administracion. La validacion de
 * los cuerpos se declara aqui (Bean Validation) para que ningun controller ni
 * servicio acepte nombres vacios o configuraciones invalidas.
 */
public final class AdminDtos {

    private AdminDtos() {}

    /** Letra o digito al inicio; luego letras, digitos, '.', '_', ':' o '-'. Maximo 255 caracteres. */
    public static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$";
    public static final String NAME_MESSAGE =
            "debe empezar con letra o digito y usar solo letras, digitos, '.', '_', ':' o '-' (max. 255)";

    public enum ExchangeKind { DIRECT, TOPIC, FANOUT, HEADERS }

    /**
     * @param durable             por defecto true
     * @param withDeadLetter      si es true, tambien se crea la DLQ {@code <name>.dlq} y se enlaza al
     *                            exchange de mensajes muertos indicado (que debe existir)
     * @param deadLetterExchange  obligatorio si withDeadLetter=true
     * @param deadLetterRoutingKey clave del DLX (por defecto, el nombre de la cola)
     */
    public record QueueRequest(
            @NotBlank(message = "el nombre de la cola es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String name,
            Boolean durable,
            Boolean withDeadLetter,
            @Pattern(regexp = "^$|" + NAME_PATTERN, message = NAME_MESSAGE) String deadLetterExchange,
            @Size(max = 255, message = "maximo 255 caracteres") String deadLetterRoutingKey) {}

    public record ExchangeRequest(
            @NotBlank(message = "el nombre del exchange es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String name,
            @NotNull(message = "el tipo es obligatorio (DIRECT, TOPIC, FANOUT o HEADERS)") ExchangeKind type,
            Boolean durable) {}

    public record BindingRequest(
            @NotBlank(message = "la cola es obligatoria")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String queue,
            @NotBlank(message = "el exchange es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String exchange,
            @Size(max = 255, message = "maximo 255 caracteres") String routingKey) {}

    /** Mensaje de prueba para demostrar enrutamiento, consumo y DLQ. */
    public record PublishRequest(
            @NotBlank(message = "el exchange es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE) String exchange,
            @Size(max = 255, message = "maximo 255 caracteres") String routingKey,
            @Size(max = 100, message = "maximo 100 caracteres") String type,
            @NotNull(message = "el payload es obligatorio") Map<String, Object> payload) {}

    public record QueueInfoResponse(String name, int messageCount, int consumerCount) {}

    public record PublishResponse(String eventId, String exchange, String routingKey) {}

    /** Mismo envelope que usa el resto del sistema (orders -> notify). */
    public record MessageEnvelope(
            String type,
            String eventId,
            Instant timestamp,
            String traceId,
            String correlationId,
            Map<String, Object> payload) {}
}
