package cl.duoc.pedidos360.kafkaadmin.controller;

import cl.duoc.pedidos360.kafkaadmin.dto.KafkaAdminDtos.*;
import cl.duoc.pedidos360.kafkaadmin.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el contrato REST: que la validacion rechaza entradas invalidas y
 * que cada error de dominio se traduce al codigo HTTP correcto.
 *
 * El codigo importa: un 502 dice "Kafka no responde" y un 404 dice "ese
 * topico no existe". Confundirlos manda a quien diagnostica por el camino
 * equivocado.
 */
class KafkaAdminControllerTest {

    private KafkaAdminService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(KafkaAdminService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KafkaAdminController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private TopicDetail detalleDemo() {
        return new TopicDetail("demo", 3, 3,
                List.of(new PartitionInfo(0, 1, List.of(1, 2, 3), List.of(1, 2, 3))),
                Map.of("cleanup.policy", "delete"));
    }

    // --------------------------------------------------------- Validacion

    @Test
    void crearTopico_sinNombre_responde400() throws Exception {
        postJson("/api/kafka/topics", "{}").andExpect(status().isBadRequest());
        verify(service, never()).crearTopico(any());
    }

    @Test
    void crearTopico_conNombreEnBlanco_responde400ConDetalle() throws Exception {
        postJson("/api/kafka/topics", "{\"name\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validacion fallida"))
                .andExpect(jsonPath("$.details[?(@.field=='name')]").exists());
        verify(service, never()).crearTopico(any());
    }

    @Test
    void crearTopico_conEspaciosEnElNombre_responde400() throws Exception {
        postJson("/api/kafka/topics", "{\"name\":\"topico con espacios\"}")
                .andExpect(status().isBadRequest());
        verify(service, never()).crearTopico(any());
    }

    @Test
    void crearTopico_conCeroParticiones_responde400() throws Exception {
        postJson("/api/kafka/topics", "{\"name\":\"demo\",\"partitions\":0}")
                .andExpect(status().isBadRequest());
        verify(service, never()).crearTopico(any());
    }

    @Test
    void crearTopico_valido_responde201() throws Exception {
        when(service.crearTopico(any())).thenReturn(detalleDemo());

        postJson("/api/kafka/topics",
                "{\"name\":\"demo\",\"partitions\":3,\"replicationFactor\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.partitionCount").value(3))
                .andExpect(jsonPath("$.replicationFactor").value(3));
    }

    // ------------------------------------------------- Traduccion de errores

    @Test
    void crearTopico_duplicado_responde409() throws Exception {
        when(service.crearTopico(any())).thenThrow(new TopicoYaExisteException("ya existe"));

        postJson("/api/kafka/topics", "{\"name\":\"demo\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void describirTopico_inexistente_responde404() throws Exception {
        when(service.describirTopico("fantasma")).thenThrow(new TopicoNoEncontradoException("fantasma"));

        mvc.perform(get("/api/kafka/topics/fantasma"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no existe el topico: fantasma"));
    }

    /** 502 y no 500: el fallo es de una dependencia, no de este servicio. */
    @Test
    void listarTopicos_conKafkaCaido_responde502() throws Exception {
        when(service.listarTopicos(false)).thenThrow(new KafkaNoDisponibleException("sin conexion"));

        mvc.perform(get("/api/kafka/topics"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void describirGrupo_inexistente_responde404() throws Exception {
        when(service.describirGrupo("fantasma")).thenThrow(new GrupoNoEncontradoException("fantasma"));

        mvc.perform(get("/api/kafka/groups/fantasma"))
                .andExpect(status().isNotFound());
    }

    // ----------------------------------------------------------- Lecturas

    @Test
    void listarTopicos_porDefectoOcultaLosInternos() throws Exception {
        when(service.listarTopicos(false)).thenReturn(List.of(new TopicSummary("demo", 3, 3)));

        mvc.perform(get("/api/kafka/topics")).andExpect(status().isOk());

        verify(service).listarTopicos(false);
    }

    @Test
    void listarTopicos_conInternosTrue_losPide() throws Exception {
        when(service.listarTopicos(true)).thenReturn(List.of());

        mvc.perform(get("/api/kafka/topics").param("internos", "true"))
                .andExpect(status().isOk());

        verify(service).listarTopicos(true);
    }

    @Test
    void describirGrupo_devuelveElLagPorParticion() throws Exception {
        when(service.describirGrupo("g1")).thenReturn(new ConsumerGroupDetail(
                "g1", "STABLE", 3, 7L,
                List.of(new GroupPartitionOffsets("orders.events", 0, 10L, 17L, 7L))));

        mvc.perform(get("/api/kafka/groups/g1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lagTotal").value(7))
                .andExpect(jsonPath("$.offsets[0].lag").value(7))
                .andExpect(jsonPath("$.offsets[0].logEndOffset").value(17));
    }

    @Test
    void cluster_devuelveLosBrokersYElControlador() throws Exception {
        when(service.describirCluster()).thenReturn(new ClusterInfo("c1", 3, List.of(
                new BrokerInfo(1, "kafka1", 9092, true),
                new BrokerInfo(2, "kafka2", 9093, false),
                new BrokerInfo(3, "kafka3", 9094, false))));

        mvc.perform(get("/api/kafka/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokerCount").value(3))
                .andExpect(jsonPath("$.brokers[0].controller").value(true));
    }

    // ----------------------------------------------------------- Escrituras

    @Test
    void reducirParticiones_responde400() throws Exception {
        when(service.aumentarParticiones(anyString(), anyInt()))
                .thenThrow(new PeticionKafkaInvalidaException(
                        "el topico ya tiene 6 particiones; Kafka solo permite AUMENTAR, nunca reducir"));

        mvc.perform(patch("/api/kafka/topics/demo/partitions").param("total", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("solo permite AUMENTAR")));
    }

    @Test
    void aumentarParticiones_sinElParametroTotal_responde400() throws Exception {
        mvc.perform(patch("/api/kafka/topics/demo/partitions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("total")));
    }

    @Test
    void actualizarConfigs_delegaEnElServicio() throws Exception {
        when(service.actualizarConfigs(anyString(), any())).thenReturn(detalleDemo());

        mvc.perform(patch("/api/kafka/topics/demo/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configs\":{\"retention.ms\":\"600000\"}}"))
                .andExpect(status().isOk());

        verify(service).actualizarConfigs("demo", Map.of("retention.ms", "600000"));
    }

    @Test
    void eliminarTopico_responde204() throws Exception {
        mvc.perform(delete("/api/kafka/topics/demo")).andExpect(status().isNoContent());
        verify(service).eliminarTopico("demo");
    }

    @Test
    void eliminarGrupo_responde204() throws Exception {
        mvc.perform(delete("/api/kafka/groups/g1")).andExpect(status().isNoContent());
        verify(service).eliminarGrupo("g1");
    }
}
