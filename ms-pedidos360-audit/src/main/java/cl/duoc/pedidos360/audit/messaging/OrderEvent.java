package cl.duoc.pedidos360.audit.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Copia del contrato del evento que publica ms-pedidos360-orders en el topico
 * {@code orders.events}.
 *
 * POR QUE ESTA DUPLICADO Y NO EN UN MODULO COMPARTIDO
 * Es la misma decision que ya toma el proyecto con MessageEnvelope, que tambien
 * esta duplicado entre orders y notify. Un modulo comun acoplaria el despliegue
 * de los microservicios: cambiar el contrato obligaria a recompilar y desplegar
 * todos a la vez, que es justo lo que la arquitectura de eventos intenta evitar.
 *
 * El contrato real es el JSON, no la clase Java. Por eso el productor publica
 * con las cabeceras de tipo desactivadas y cada consumidor deserializa contra
 * el tipo que el declara. Un campo nuevo en el productor no rompe a este
 * consumidor: Jackson lo ignora.
 */
public record OrderEvent(
        String eventId,
        String eventType,
        Long orderId,
        String customerId,
        String previousStatus,
        String status,
        BigDecimal totalAmount,
        Instant occurredAt,
        String traceId
) {

    /**
     * Comprueba que el evento trae lo minimo para ser auditado.
     *
     * Se valida en el consumidor y no se confia en el productor: un mensaje
     * puede venir de una version antigua del servicio, de una prueba manual
     * desde Kafka UI, o estar incompleto por un error. La validacion decide
     * entre "procesar" y "mandar a la DLT", y sin ella un NullPointerException
     * se reintentaria tres veces antes de acabar en el mismo sitio.
     */
    public boolean esValido() {
        return eventId != null && !eventId.isBlank()
                && eventType != null && !eventType.isBlank()
                && orderId != null
                && status != null && !status.isBlank()
                && occurredAt != null;
    }
}
