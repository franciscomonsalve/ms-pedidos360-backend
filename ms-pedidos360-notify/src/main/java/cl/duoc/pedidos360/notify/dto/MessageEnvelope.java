package cl.duoc.pedidos360.notify.dto;

import java.time.Instant;
import java.util.Map;

/** Envelope comun de los mensajes (mismo contrato que publica ms-pedidos360-orders). */
public record MessageEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        Map<String, Object> payload
) {

    /**
     * Devuelve un campo obligatorio del payload.
     *
     * @throws IllegalArgumentException si falta (error no recuperable: el
     *         mensaje esta mal formado y va directo a la DLQ)
     */
    public Object require(String field) {
        Object value = payload == null ? null : payload.get(field);
        if (value == null) {
            throw new IllegalArgumentException("el payload no trae '" + field + "'");
        }
        return value;
    }
}
