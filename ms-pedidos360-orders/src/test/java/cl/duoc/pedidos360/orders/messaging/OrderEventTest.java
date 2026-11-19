package cl.duoc.pedidos360.orders.messaging;

import cl.duoc.pedidos360.orders.entity.Order;
import cl.duoc.pedidos360.orders.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas del contrato del evento de dominio.
 *
 * Lo que se protege aqui es el CONTRATO que consumen otros microservicios: si
 * cambia un nombre de tipo de evento, los consumidores que filtran por el
 * dejan de reaccionar y no se entera nadie hasta produccion.
 */
class OrderEventTest {

    private Order pedido(OrderStatus estado) {
        Order order = new Order();
        order.setId(42L);
        order.setCustomerId("cliente-1");
        order.setStatus(estado);
        order.setTotalAmount(new BigDecimal("150000"));
        return order;
    }

    /**
     * Los nombres van en pasado porque describen un HECHO consumado, no una
     * orden. Es la diferencia entre un evento y un comando, y es lo que permite
     * que varios consumidores reaccionen sin acoplarse entre si.
     */
    @ParameterizedTest
    @CsvSource({
            "CREADO,          OrderCreated",
            "ACEPTADO,        OrderAccepted",
            "EN_PREPARACION,  OrderPreparing",
            "DESPACHADO,      OrderDispatched",
            "ENTREGADO,       OrderDelivered",
            "CANCELADO,       OrderCancelled"
    })
    void from_traduceCadaEstadoASuTipoDeEvento(OrderStatus estado, String tipoEsperado) {
        OrderEvent evento = OrderEvent.from(pedido(estado), OrderStatus.CREADO);
        assertThat(evento.eventType()).isEqualTo(tipoEsperado);
    }

    @Test
    void from_copiaLosDatosDelPedido() {
        OrderEvent evento = OrderEvent.from(pedido(OrderStatus.ACEPTADO), OrderStatus.CREADO);

        assertThat(evento.orderId()).isEqualTo(42L);
        assertThat(evento.customerId()).isEqualTo("cliente-1");
        assertThat(evento.status()).isEqualTo("ACEPTADO");
        assertThat(evento.previousStatus()).isEqualTo("CREADO");
        assertThat(evento.totalAmount()).isEqualByComparingTo("150000");
    }

    /**
     * El estado anterior puede no existir (el pedido acaba de nacer). Se manda
     * "NONE" en vez de null para que el consumidor no tenga que distinguir
     * entre "no habia estado previo" y "el campo falta".
     */
    @Test
    void from_sinEstadoAnterior_usaNONE() {
        OrderEvent evento = OrderEvent.from(pedido(OrderStatus.CREADO), null);
        assertThat(evento.previousStatus()).isEqualTo("NONE");
    }

    /** El eventId es la clave de idempotencia: dos eventos nunca pueden compartirlo. */
    @Test
    void from_generaUnEventIdDistintoCadaVez() {
        OrderEvent uno = OrderEvent.from(pedido(OrderStatus.ACEPTADO), OrderStatus.CREADO);
        OrderEvent otro = OrderEvent.from(pedido(OrderStatus.ACEPTADO), OrderStatus.CREADO);

        assertThat(uno.eventId()).isNotBlank().isNotEqualTo(otro.eventId());
    }

    @Test
    void from_rellenaLaFechaDelHechoYElTraceId() {
        OrderEvent evento = OrderEvent.from(pedido(OrderStatus.ENTREGADO), OrderStatus.DESPACHADO);

        assertThat(evento.occurredAt()).isNotNull();
        assertThat(evento.traceId()).isNotBlank();
    }
}
