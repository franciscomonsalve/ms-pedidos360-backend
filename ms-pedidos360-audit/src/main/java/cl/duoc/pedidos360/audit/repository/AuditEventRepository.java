package cl.duoc.pedidos360.audit.repository;

import cl.duoc.pedidos360.audit.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findAllByOrderByOccurredAtDesc();

    List<AuditEvent> findByEntityIdOrderByOccurredAtDesc(String entityId);
}
