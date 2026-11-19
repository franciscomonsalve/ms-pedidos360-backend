package cl.duoc.pedidos360.audit.messaging;

import java.time.Instant;

/**
 * Evento normalizado que este servicio publica en el topico
 * {@code audit.timeline} despues de registrar un hecho en la linea de tiempo.
 *
 * POR QUE EXISTE UN SEGUNDO TOPICO Y NO SE REUSA orders.events
 * Son dos flujos con naturalezas distintas, y por eso tienen politicas de
 * limpieza distintas:
 *
 *   orders.events   es un flujo de HECHOS. Cada cambio de estado es un evento
 *                   inmutable. cleanup.policy=delete, 7 dias: pasado ese plazo
 *                   el historico vive en la base de datos, no en Kafka.
 *
 *   audit.timeline  es un flujo de ESTADO. Lo que interesa es el ultimo estado
 *                   conocido de cada pedido. cleanup.policy=compact: Kafka
 *                   conserva el ultimo valor de cada clave (el orderId) y
 *                   descarta los intermedios. Un consumidor que arranca de cero
 *                   reconstruye el estado actual de TODOS los pedidos leyendo
 *                   el topico entero, sin tocar la base.
 *
 * Esa es la razon de que la clave del mensaje sea el orderId: la compactacion
 * trabaja por clave, y sin clave no habria nada que compactar.
 */
public record TimelineEvent(
        String eventId,
        Long orderId,
        String customerId,
        String estadoActual,
        String estadoAnterior,
        Instant occurredAt,
        String traceId
) {

    /** Deriva el evento de linea de tiempo a partir del evento de dominio. */
    public static TimelineEvent desde(OrderEvent origen) {
        return new TimelineEvent(
                origen.eventId(),
                origen.orderId(),
                origen.customerId(),
                origen.status(),
                origen.previousStatus(),
                origen.occurredAt(),
                origen.traceId()
        );
    }
}
