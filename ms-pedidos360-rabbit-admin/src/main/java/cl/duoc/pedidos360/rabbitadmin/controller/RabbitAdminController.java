package cl.duoc.pedidos360.rabbitadmin.controller;

import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.BindingRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.ExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.PublishRequest;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.PublishResponse;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueInfoResponse;
import cl.duoc.pedidos360.rabbitadmin.dto.AdminDtos.QueueRequest;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API REST de administracion de RabbitMQ (solo Admin). El controller solo
 * traduce HTTP: toda la logica esta en {@link RabbitAdminService}.
 *
 * <pre>
 *  POST   /api/rabbit/queues                 crea una cola (opcional: con su DLQ)
 *  GET    /api/rabbit/queues                 lista colas
 *  GET    /api/rabbit/queues/{name}          estado de una cola (mensajes, consumidores)
 *  DELETE /api/rabbit/queues/{name}          elimina una cola
 *  POST   /api/rabbit/exchanges              crea un exchange (direct, topic, fanout, headers)
 *  GET    /api/rabbit/exchanges              lista exchanges
 *  DELETE /api/rabbit/exchanges/{name}       elimina un exchange
 *  POST   /api/rabbit/bindings               enlaza una cola a un exchange
 *  GET    /api/rabbit/bindings               lista bindings
 *  DELETE /api/rabbit/bindings?queue=&amp;exchange=&amp;routingKey=   elimina un binding
 *  POST   /api/rabbit/messages               publica un mensaje de prueba
 * </pre>
 */
@RestController
@RequestMapping("/api/rabbit")
public class RabbitAdminController {

    private final RabbitAdminService service;

    public RabbitAdminController(RabbitAdminService service) {
        this.service = service;
    }

    // --------------------------------------------------------------- Colas

    @PostMapping("/queues")
    public ResponseEntity<QueueInfoResponse> createQueue(@Valid @RequestBody QueueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createQueue(request));
    }

    @GetMapping("/queues")
    public List<Map<String, Object>> listQueues() {
        return service.listQueues();
    }

    @GetMapping("/queues/{name}")
    public QueueInfoResponse queueInfo(@PathVariable String name) {
        return service.queueInfo(name);
    }

    @DeleteMapping("/queues/{name}")
    public ResponseEntity<Void> deleteQueue(@PathVariable String name,
                                            @RequestParam(defaultValue = "false") boolean ifUnused,
                                            @RequestParam(defaultValue = "false") boolean ifEmpty) {
        service.deleteQueue(name, ifUnused, ifEmpty);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------- Exchanges

    @PostMapping("/exchanges")
    public ResponseEntity<ExchangeRequest> createExchange(@Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createExchange(request));
    }

    @GetMapping("/exchanges")
    public List<Map<String, Object>> listExchanges() {
        return service.listExchanges();
    }

    @DeleteMapping("/exchanges/{name}")
    public ResponseEntity<Void> deleteExchange(@PathVariable String name) {
        service.deleteExchange(name);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ Bindings

    @PostMapping("/bindings")
    public ResponseEntity<BindingRequest> createBinding(@Valid @RequestBody BindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBinding(request));
    }

    @GetMapping("/bindings")
    public List<Map<String, Object>> listBindings() {
        return service.listBindings();
    }

    @DeleteMapping("/bindings")
    public ResponseEntity<Void> deleteBinding(@RequestParam String queue,
                                              @RequestParam String exchange,
                                              @RequestParam(defaultValue = "") String routingKey) {
        service.deleteBinding(queue, exchange, routingKey);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------- Mensaje de prueba

    @PostMapping("/messages")
    public ResponseEntity<PublishResponse> publish(@Valid @RequestBody PublishRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.publish(request));
    }
}
