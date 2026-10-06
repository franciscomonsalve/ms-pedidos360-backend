package cl.duoc.pedidos360.rabbitadmin.controller;

import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueInfoResponse;
import cl.duoc.pedidos360.rabbitadmin.service.InvalidRabbitRequestException;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifica el contrato REST y que la validacion de entrada rechaza datos invalidos con 400. */
class RabbitAdminControllerTest {

    private RabbitAdminService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RabbitAdminService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RabbitAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void crearCola_conNombreVacio_responde400ConDetalle() throws Exception {
        postJson("/api/rabbit/queues", "{\"name\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validacion fallida"))
                .andExpect(jsonPath("$.details[?(@.field=='name')]").exists());
        verify(service, never()).createQueue(any());
    }

    @Test
    void crearCola_sinNombre_responde400() throws Exception {
        postJson("/api/rabbit/queues", "{}").andExpect(status().isBadRequest());
        verify(service, never()).createQueue(any());
    }

    @Test
    void crearCola_conCaracteresInvalidos_responde400() throws Exception {
        postJson("/api/rabbit/queues", "{\"name\":\"mala cola!\"}").andExpect(status().isBadRequest());
        verify(service, never()).createQueue(any());
    }

    @Test
    void crearCola_valida_responde201() throws Exception {
        when(service.createQueue(any())).thenReturn(new QueueInfoResponse("q.test", 0, 0));

        postJson("/api/rabbit/queues", "{\"name\":\"q.test\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("q.test"));
    }

    @Test
    void crearExchange_sinTipo_responde400() throws Exception {
        postJson("/api/rabbit/exchanges", "{\"name\":\"ex.test\"}").andExpect(status().isBadRequest());
        verify(service, never()).createExchange(any());
    }

    @Test
    void crearExchange_conTipoInvalido_responde400() throws Exception {
        postJson("/api/rabbit/exchanges", "{\"name\":\"ex.test\",\"type\":\"BANANA\"}")
                .andExpect(status().isBadRequest());
        verify(service, never()).createExchange(any());
    }

    @Test
    void crearBinding_sinCola_responde400() throws Exception {
        postJson("/api/rabbit/bindings", "{\"exchange\":\"ex.test\"}").andExpect(status().isBadRequest());
        verify(service, never()).createBinding(any());
    }

    @Test
    void eliminarBinding_sinParametros_responde400() throws Exception {
        mvc.perform(delete("/api/rabbit/bindings")).andExpect(status().isBadRequest());
    }

    @Test
    void publicarMensaje_sinPayload_responde400() throws Exception {
        postJson("/api/rabbit/messages", "{\"exchange\":\"cmd.direct\",\"routingKey\":\"email.send\"}")
                .andExpect(status().isBadRequest());
        verify(service, never()).publish(any());
    }

    @Test
    void errorDeNegocioDelServicio_seTraduceA400() throws Exception {
        when(service.queueInfo("amq.x")).thenThrow(new InvalidRabbitRequestException("nombre reservado"));

        mvc.perform(get("/api/rabbit/queues/amq.x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("nombre reservado"));
    }

    @Test
    void eliminarCola_valida_responde204() throws Exception {
        mvc.perform(delete("/api/rabbit/queues/q.test")).andExpect(status().isNoContent());
        verify(service).deleteQueue("q.test", false, false);
    }
}
