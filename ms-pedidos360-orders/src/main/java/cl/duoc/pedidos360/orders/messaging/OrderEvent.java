package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento de dominio que viaja por el topico Kafka {@code orders.events}.
 *
 * POR QUE UN RECORD TIPADO Y NO UN Map
 * El publicador anterior mandaba {@code Map<String,Object>} dentro de un
 * MessageEnvelope generico. Eso funciona, pero el contrato del evento queda
 * escrito en ningun lado: el consumidor tiene que adivinar las claves y un
 * error de tipeo solo se descubre en ejecucion. Con un record, el contrato es
 * el tipo: el compilador valida los campos en los dos extremos.
 *
 * MessageEnvelope SIGUE EN USO para los comandos de RabbitMQ, que son otra
 * cosa: alli el payload si es variable segun el caso de uso (email, cocina,
 * boleta). Un evento de dominio tiene una forma fija; un comando no.
 *
 * PARA QUE SIRVE CADA CAMPO
 *   eventId        identificador unico del evento. Es la clave de idempotencia:
 *                  si un consumidor lo ve dos veces, descarta el duplicado.
 *   eventType      que paso, en lenguaje de negocio (OrderAccepted, no "UPDATE").
 *   orderId        el agregado afectado. Ademas es la CLAVE del mensaje en
 *                  Kafka, asi que determina la particion: todos los eventos de
 *                  un pedido caen en la misma y se procesan en orden.
 *   customerId     a quien pertenece el pedido, para agrupar por cliente sin
 *                  tener que volver a la base.
 *   previousStatus desde que estado se transito. Sin esto el consumidor no
 *                  puede reconstruir la transicion, solo el estado final.
 *   status         el estado resultante.
 *   totalAmount    el monto, para los KPI de negocio sin consultar la base.
 *   occurredAt     cuando ocurrio el hecho (no cuando se proceso). Es lo que
 *                  permite ordenar y calcular tiempos entre estados.
 *   traceId        correlacion entre servicios: el mismo id aparece en los
 *                  registros del productor y de cada consumidor.
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

    /** Construye el evento a partir del pedido ya persistido y su estado anterior. */
    public static OrderEvent from(Order order, OrderStatus previousStatus) {
        return new OrderEvent(
                UUID.randomUUID().toString(),
                mapEventType(order.getStatus()),
                order.getId(),
                order.getCustomerId(),
                previousStatus != null ? previousStatus.name() : "NONE",
                order.getStatus().name(),
                order.getTotalAmount(),
                Instant.now(),
                UUID.randomUUID().toString()
        );
    }

    /**
     * Traduce el estado a un nombre de evento en pasado. El nombre describe un
     * HECHO que ya ocurrio, no una orden: es la diferencia entre un evento y un
     * comando, y es lo que permite que varios consumidores reaccionen al mismo
     * evento sin acoplarse entre si.
     */
    private static String mapEventType(OrderStatus status) {
        return switch (status) {
            case CREADO -> "OrderCreated";
            case ACEPTADO -> "OrderAccepted";
            case EN_PREPARACION -> "OrderPreparing";
            case DESPACHADO -> "OrderDispatched";
            case ENTREGADO -> "OrderDelivered";
            case CANCELADO -> "OrderCancelled";
        };
    }
}
