package cl.duoc.pedidos360.rabbitadmin.service;

import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.BindingRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueInfoResponse;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitAdminServiceTest {

    private AmqpAdmin amqpAdmin;
    private RabbitTemplate rabbitTemplate;
    private RabbitAdminService service;

    @BeforeEach
    void setUp() {
        amqpAdmin = mock(AmqpAdmin.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        service = new RabbitAdminService(amqpAdmin, rabbitTemplate, mock(RabbitManagementClient.class));
    }

    private void exchangeExists(boolean exists) {
        if (exists) {
            doReturn(Boolean.TRUE).when(rabbitTemplate).execute(any(ChannelCallback.class));
        } else {
            doReturn(null).when(rabbitTemplate).execute(any(ChannelCallback.class));
        }
    }

    private static QueueRequest queue(String name, Boolean dlq, String dlx) {
        return new QueueRequest(name, null, dlq, dlx, null);
    }

    // ---------------------------------------------------------- Validacion

    @Test
    void crearCola_conNombreVacio_esRechazada() {
        assertThatThrownBy(() -> service.createQueue(queue("  ", null, null)))
                .isInstanceOf(InvalidRabbitRequestException.class)
                .hasMessageContaining("vacio");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void crearCola_conPrefijoReservadoDeRabbit_esRechazada() {
        assertThatThrownBy(() -> service.createQueue(queue("amq.mi-cola", null, null)))
                .isInstanceOf(InvalidRabbitRequestException.class)
                .hasMessageContaining("reservado");
    }

    @Test
    void nombreConEspacios_enParametroDeRuta_esRechazado() {
        assertThatThrownBy(() -> service.queueInfo("mala cola"))
                .isInstanceOf(InvalidRabbitRequestException.class);
    }

    @Test
    void crearCola_conDlqSinExchange_esRechazada() {
        assertThatThrownBy(() -> service.createQueue(queue("q.test", true, null)))
                .isInstanceOf(InvalidRabbitRequestException.class)
                .hasMessageContaining("deadLetterExchange");
    }

    @Test
    void crearCola_conExchangeDeMuertosPeroSinDlq_esRechazada() {
        assertThatThrownBy(() -> service.createQueue(queue("q.test", false, "dlx")))
                .isInstanceOf(InvalidRabbitRequestException.class);
    }

    // -------------------------------------------------------------- Colas

    @Test
    void crearCola_quePrexiste_respondeConflicto() {
        when(amqpAdmin.getQueueProperties("q.test")).thenReturn(new Properties());

        assertThatThrownBy(() -> service.createQueue(queue("q.test", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void crearCola_simple_declaraUnaColaDurable() {
        when(amqpAdmin.getQueueProperties("q.test")).thenReturn(null);
        when(amqpAdmin.getQueueInfo("q.test")).thenReturn(new QueueInformation("q.test", 0, 0));

        QueueInfoResponse response = service.createQueue(queue("q.test", null, null));

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("q.test");
        assertThat(captor.getValue().isDurable()).isTrue();
        assertThat(response.name()).isEqualTo("q.test");
    }

    @Test
    void crearCola_conDlq_declaraColaPrincipalDlqYBinding() {
        when(amqpAdmin.getQueueProperties(any())).thenReturn(null);
        when(amqpAdmin.getQueueInfo("q.test")).thenReturn(new QueueInformation("q.test", 0, 0));
        exchangeExists(true);

        service.createQueue(queue("q.test", true, "mi.dlx"));

        ArgumentCaptor<Queue> queues = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin, times(2)).declareQueue(queues.capture());
        Queue main = queues.getAllValues().get(0);
        assertThat(main.getArguments())
                .containsEntry("x-dead-letter-exchange", "mi.dlx")
                .containsEntry("x-dead-letter-routing-key", "q.test");
        assertThat(queues.getAllValues().get(1).getName()).isEqualTo("q.test.dlq");

        ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(binding.capture());
        assertThat(binding.getValue().getDestination()).isEqualTo("q.test.dlq");
        assertThat(binding.getValue().getExchange()).isEqualTo("mi.dlx");
    }

    @Test
    void crearCola_conDlqYExchangeInexistente_responde404() {
        when(amqpAdmin.getQueueProperties(any())).thenReturn(null);
        exchangeExists(false);

        assertThatThrownBy(() -> service.createQueue(queue("q.test", true, "no.existe")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void eliminarCola_inexistente_responde404() {
        when(amqpAdmin.getQueueProperties("q.nada")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteQueue("q.nada", false, false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void eliminarCola_existente_laElimina() {
        when(amqpAdmin.getQueueProperties("q.test")).thenReturn(new Properties());

        service.deleteQueue("q.test", false, true);

        verify(amqpAdmin).deleteQueue("q.test", false, true);
    }

    // ------------------------------------------------------------ Bindings

    @Test
    void crearBinding_conColaInexistente_responde404() {
        when(amqpAdmin.getQueueProperties("q.nada")).thenReturn(null);

        assertThatThrownBy(() -> service.createBinding(new BindingRequest("q.nada", "ex", "rk")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void crearBinding_valido_declaraElBinding() {
        when(amqpAdmin.getQueueProperties("q.test")).thenReturn(new Properties());
        exchangeExists(true);

        BindingRequest created = service.createBinding(new BindingRequest("q.test", "mi.ex", "clave"));

        ArgumentCaptor<Binding> captor = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(captor.capture());
        assertThat(captor.getValue().getRoutingKey()).isEqualTo("clave");
        assertThat(created.exchange()).isEqualTo("mi.ex");
    }
}
