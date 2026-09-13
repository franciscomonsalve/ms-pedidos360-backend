package cl.duoc.pedidos360.orders.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope comun para todos los mensajes (comandos y eventos) publicados
 * por el dominio de pedidos, tal como recomienda el diseño del caso:
 * type, eventId, timestamp, traceId, correlationId.
 */
public record MessageEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        Map<String, Object> payload
) {
    public static MessageEnvelope of(String type, String correlationId, Map<String, Object> payload) {
        return new MessageEnvelope(
                type,
                UUID.randomUUID().toString(),
                Instant.now(),
                UUID.randomUUID().toString(),
                correlationId,
                payload
        );
    }
}
