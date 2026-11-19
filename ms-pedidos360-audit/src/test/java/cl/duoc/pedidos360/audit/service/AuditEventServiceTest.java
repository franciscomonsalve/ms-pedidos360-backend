package cl.duoc.pedidos360.audit.service;

import cl.duoc.pedidos360.audit.entity.AuditEvent;
import cl.duoc.pedidos360.audit.messaging.OrderEvent;
import cl.duoc.pedidos360.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pruebas de la logica de auditoria.
 *
 * Que no haga falta ni un broker ni un contenedor para ejecutarlas es
 * justamente la consecuencia de haber separado la logica de dominio de la
 * infraestructura de mensajeria: AuditEventService no conoce Kafka.
 */
class AuditEventServiceTest {

    private AuditEventRepository repository;
    private AuditEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        service = new AuditEventService(repository);
    }

    private OrderEvent evento(String eventId) {
        return new OrderEvent(
                eventId, "OrderAccepted", 42L, "cliente-1",
                "CREADO", "ACEPTADO", new BigDecimal("150000"),
                Instant.parse("2026-11-19T12:00:00Z"), "trace-1");
    }

    @Test
    void registrar_eventoNuevo_loGuardaYDevuelveTrue() {
        when(repository.existsByEventId("evt-1")).thenReturn(false);

        boolean registrado = service.registrar(evento("evt-1"), "orders.events");

        assertThat(registrado).isTrue();
        verify(repository).save(any(AuditEvent.class));
    }

    @Test
    void registrar_mapeaTodosLosCamposDelEvento() {
        when(repository.existsByEventId("evt-2")).thenReturn(false);

        service.registrar(evento("evt-2"), "orders.events");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent guardado = captor.getValue();

        assertThat(guardado.getEventId()).isEqualTo("evt-2");
        assertThat(guardado.getEventType()).isEqualTo("OrderAccepted");
        assertThat(guardado.getEntityId()).isEqualTo("42");
        assertThat(guardado.getActor()).isEqualTo("cliente-1");
        assertThat(guardado.getOccurredAt()).isEqualTo(Instant.parse("2026-11-19T12:00:00Z"));
        assertThat(guardado.getDetails()).contains("CREADO", "ACEPTADO", "150000");
    }

    /**
     * El topico de origen se guarda porque la misma tabla se alimenta de dos
     * topicos distintos: sin esta columna no se podria saber de cual vino cada
     * registro.
     */
    @Test
    void registrar_guardaElTopicoDeOrigen() {
        when(repository.existsByEventId(any())).thenReturn(false);

        service.registrar(evento("evt-3"), "orders.events");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSourceTopic()).isEqualTo("orders.events");
    }

    /**
     * Kafka entrega "al menos una vez": tras un rebalanceo, o si el consumidor
     * cae despues de procesar y antes de confirmar el offset, el mismo evento
     * vuelve a llegar. Sin este control la linea de tiempo mostraria el mismo
     * hecho repetido.
     */
    @Test
    void registrar_eventoDuplicado_noGuardaYDevuelveFalse() {
        when(repository.existsByEventId("evt-repetido")).thenReturn(true);

        boolean registrado = service.registrar(evento("evt-repetido"), "orders.events");

        assertThat(registrado).isFalse();
        verify(repository, never()).save(any());
    }
}
