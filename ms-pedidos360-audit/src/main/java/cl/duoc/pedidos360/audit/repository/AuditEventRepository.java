package cl.duoc.pedidos360.audit.repository;

import cl.duoc.pedidos360.audit.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findAllByOrderByOccurredAtDesc();

    /**
     * Idempotencia del consumidor de Kafka: la entrega es "al menos una vez",
     * asi que el mismo eventId puede llegar dos veces tras un rebalanceo.
     */
    boolean existsByEventId(String eventId);

    List<AuditEvent> findByEntityIdOrderByOccurredAtDesc(String entityId);
}
