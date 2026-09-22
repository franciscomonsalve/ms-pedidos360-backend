package cl.duoc.pedidos360.audit.controller;

import cl.duoc.pedidos360.audit.entity.AuditEvent;
import cl.duoc.pedidos360.audit.repository.AuditEventRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Solo lectura: la linea de tiempo la escriben los listeners de Kafka
 * (paquete messaging), no este controller. Acceso restringido a Admin,
 * reforzado tanto aqui (defensa en profundidad, ver SecurityConfig) como
 * en el BFF (SecurityConfig: /api/audit/** -> hasRole("ADMIN")).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<AuditEvent> findAll() {
        return repository.findAllByOrderByOccurredAtDesc();
    }

    @GetMapping("/{entityId}")
    public List<AuditEvent> findByEntity(@PathVariable String entityId) {
        return repository.findByEntityIdOrderByOccurredAtDesc(entityId);
    }
}
