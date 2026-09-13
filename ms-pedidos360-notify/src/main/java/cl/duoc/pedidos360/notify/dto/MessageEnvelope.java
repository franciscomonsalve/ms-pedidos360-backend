package cl.duoc.pedidos360.notify.dto;

import java.time.Instant;
import java.util.Map;

public record MessageEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        Map<String, Object> payload
) {}
