package cl.duoc.pedidos360.kafkaadmin.controller;

import cl.duoc.pedidos360.kafkaadmin.dto.KafkaAdminDtos.*;
import cl.duoc.pedidos360.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API REST de administracion de Kafka (solo rol Admin). El controlador solo
 * traduce HTTP: toda la logica esta en {@link KafkaAdminService}.
 *
 * <pre>
 *  POST   /api/kafka/topics                      crea un topico con particiones, replicas y configs
 *  GET    /api/kafka/topics                      lista topicos
 *  GET    /api/kafka/topics/{nombre}             detalle: particiones, lider, ISR y configuracion
 *  PATCH  /api/kafka/topics/{nombre}/configs     cambia retention, cleanup.policy, etc. EN CALIENTE
 *  PATCH  /api/kafka/topics/{nombre}/partitions  aumenta particiones (nunca reduce)
 *  DELETE /api/kafka/topics/{nombre}             elimina un topico
 *  GET    /api/kafka/groups                      lista consumer groups
 *  GET    /api/kafka/groups/{id}                 offsets y LAG por particion
 *  DELETE /api/kafka/groups/{id}                 elimina un grupo
 *  GET    /api/kafka/cluster                     brokers vivos y controlador
 * </pre>
 */
@RestController
@RequestMapping("/api/kafka")
public class KafkaAdminController {

    private final KafkaAdminService service;

    public KafkaAdminController(KafkaAdminService service) {
        this.service = service;
    }

    // --------------------------------------------------------------- Topicos

    @PostMapping("/topics")
    public ResponseEntity<TopicDetail> crear(@Valid @RequestBody CreateTopicRequest peticion) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearTopico(peticion));
    }

    /**
     * @param internos true incluye los topicos internos de Kafka
     *                 (__consumer_offsets y similares). Por defecto se ocultan,
     *                 porque no son del dominio.
     */
    @GetMapping("/topics")
    public List<TopicSummary> listar(@RequestParam(defaultValue = "false") boolean internos) {
        return service.listarTopicos(internos);
    }

    @GetMapping("/topics/{nombre}")
    public TopicDetail describir(@PathVariable String nombre) {
        return service.describirTopico(nombre);
    }

    @PatchMapping("/topics/{nombre}/configs")
    public TopicDetail actualizarConfigs(@PathVariable String nombre,
                                         @RequestBody UpdateTopicConfigRequest peticion) {
        return service.actualizarConfigs(nombre, peticion.configs());
    }

    @PatchMapping("/topics/{nombre}/partitions")
    public TopicDetail aumentarParticiones(@PathVariable String nombre, @RequestParam int total) {
        return service.aumentarParticiones(nombre, total);
    }

    @DeleteMapping("/topics/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable String nombre) {
        service.eliminarTopico(nombre);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------- Consumer groups

    @GetMapping("/groups")
    public List<ConsumerGroupSummary> listarGrupos() {
        return service.listarGrupos();
    }

    @GetMapping("/groups/{id}")
    public ConsumerGroupDetail describirGrupo(@PathVariable String id) {
        return service.describirGrupo(id);
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> eliminarGrupo(@PathVariable String id) {
        service.eliminarGrupo(id);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- Cluster

    @GetMapping("/cluster")
    public ClusterInfo cluster() {
        return service.describirCluster();
    }
}
