package cl.duoc.pedidos360.notify.messaging.stream;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contrato del evento que publica ms-pedidos360-orders en {@code orders.events}.
 *
 * Es el mismo JSON que consume ms-pedidos360-audit, con su propia copia del
 * record. El contrato es el JSON, no la clase: el productor publica sin
 * cabeceras de tipo y cada consumidor deserializa contra el tipo que declara.
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

    public boolean esValido() {
        return eventId != null && !eventId.isBlank()
                && eventType != null && !eventType.isBlank()
                && orderId != null
                && status != null && !status.isBlank();
    }
}
