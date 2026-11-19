package cl.duoc.pedidos360.audit.messaging;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La validacion del evento decide entre procesarlo y mandarlo a la DLT, asi
 * que conviene que este cubierta: un fallo aqui significa o bien eventos
 * validos descartados, o bien NullPointerException dentro del listener.
 */
class OrderEventValidacionTest {

    private OrderEvent con(String eventId, String eventType, Long orderId,
                           String status, Instant occurredAt) {
        return new OrderEvent(eventId, eventType, orderId, "cliente-1",
                "CREADO", status, new BigDecimal("1000"), occurredAt, "trace-1");
    }

    private static final Instant AHORA = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void eventoCompleto_esValido() {
        assertThat(con("evt-1", "OrderAccepted", 42L, "ACEPTADO", AHORA).esValido()).isTrue();
    }

    @Test
    void sinEventId_noEsValido() {
        assertThat(con(null, "OrderAccepted", 42L, "ACEPTADO", AHORA).esValido()).isFalse();
    }

    @Test
    void conEventIdEnBlanco_noEsValido() {
        assertThat(con("   ", "OrderAccepted", 42L, "ACEPTADO", AHORA).esValido()).isFalse();
    }

    @Test
    void sinTipoDeEvento_noEsValido() {
        assertThat(con("evt-1", null, 42L, "ACEPTADO", AHORA).esValido()).isFalse();
    }

    @Test
    void sinOrderId_noEsValido() {
        assertThat(con("evt-1", "OrderAccepted", null, "ACEPTADO", AHORA).esValido()).isFalse();
    }

    @Test
    void sinEstado_noEsValido() {
        assertThat(con("evt-1", "OrderAccepted", 42L, null, AHORA).esValido()).isFalse();
    }

    /**
     * Sin marca temporal no se puede ordenar la linea de tiempo ni calcular
     * tiempos entre estados, que es para lo que existe.
     */
    @Test
    void sinFechaDelHecho_noEsValido() {
        assertThat(con("evt-1", "OrderAccepted", 42L, "ACEPTADO", null).esValido()).isFalse();
    }

    @Test
    void jsonVacioDeserializadoATodoNulos_noEsValido() {
        assertThat(new OrderEvent(null, null, null, null, null, null, null, null, null)
                .esValido()).isFalse();
    }
}
