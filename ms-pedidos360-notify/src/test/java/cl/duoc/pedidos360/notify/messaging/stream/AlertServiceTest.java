package cl.duoc.pedidos360.notify.messaging.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de la vigilancia del flujo de eventos.
 *
 * Igual que AuditEventService en el otro microservicio, esta clase no conoce
 * Kafka: recibe un objeto de dominio. Por eso se puede probar sin broker.
 */
class AlertServiceTest {

    private AlertService service;

    @BeforeEach
    void setUp() {
        service = new AlertService();
        // El umbral es @Value; en una prueba unitaria no hay contexto de Spring
        // que lo inyecte, asi que se fija a mano.
        ReflectionTestUtils.setField(service, "montoAlto", new BigDecimal("100000"));
    }

    private OrderEvent evento(String id, String tipo, String monto) {
        return new OrderEvent(id, tipo, 42L, "cliente-1", "CREADO", "ACEPTADO",
                new BigDecimal(monto), Instant.parse("2026-11-19T12:00:00Z"), "trace-1");
    }

    @Test
    void evaluar_cuentaLosEventosPorTipo() {
        service.evaluar(evento("e1", "OrderAccepted", "1000"));
        service.evaluar(evento("e2", "OrderAccepted", "1000"));
        service.evaluar(evento("e3", "OrderDelivered", "1000"));

        assertThat(service.resumen())
                .containsEntry("OrderAccepted", 2L)
                .containsEntry("OrderDelivered", 1L);
    }

    @Test
    void resumen_sinEventos_estaVacio() {
        assertThat(service.resumen()).isEmpty();
    }

    /**
     * El contador es la forma de comprobar que el consumidor proceso lo que
     * dice: el total tiene que cuadrar con lo que Kafka UI muestra consumido
     * por este grupo.
     */
    @Test
    void evaluar_variosTiposDistintos_losCuentaPorSeparado() {
        for (int i = 0; i < 5; i++) {
            service.evaluar(evento("e" + i, "OrderCancelled", "500"));
        }
        service.evaluar(evento("otro", "OrderCreated", "500"));

        assertThat(service.resumen())
                .hasSize(2)
                .containsEntry("OrderCancelled", 5L)
                .containsEntry("OrderCreated", 1L);
    }

    @Test
    void evaluar_montoNulo_noFalla() {
        OrderEvent sinMonto = new OrderEvent("e1", "OrderCreated", 42L, "cliente-1",
                "NONE", "CREADO", null, Instant.now(), "trace-1");

        service.evaluar(sinMonto);

        assertThat(service.resumen()).containsEntry("OrderCreated", 1L);
    }

    @Test
    void evaluar_montoExactamenteEnElUmbral_noSeConsideraAlto() {
        // El umbral es estricto (compareTo > 0): 100000 no dispara, 100001 si.
        service.evaluar(evento("e1", "OrderAccepted", "100000"));

        assertThat(service.resumen()).containsEntry("OrderAccepted", 1L);
    }
}
