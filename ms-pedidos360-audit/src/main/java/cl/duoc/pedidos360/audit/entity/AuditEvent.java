package cl.duoc.pedidos360.audit.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Registro de linea de tiempo por entidad: "quien / que / cuando / desde donde",
 * consumido desde Kafka (topicos orders.events y audit.timeline).
 */
@Entity
@Table(name = "AUDIT_EVENTS")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "EVENT_ID", nullable = false, unique = true)
    private String eventId;

    @Column(name = "EVENT_TYPE", nullable = false)
    private String eventType;

    @Column(name = "ENTITY_ID")
    private String entityId;

    @Column(name = "ACTOR")
    private String actor;

    @Column(name = "SOURCE_TOPIC")
    private String sourceTopic;

    @Column(name = "OCCURRED_AT", nullable = false)
    private Instant occurredAt;

    @Column(name = "DETAILS", length = 2000)
    private String details;
}
