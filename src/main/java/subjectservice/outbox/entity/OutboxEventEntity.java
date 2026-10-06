package subjectservice.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import subjectservice.outbox.enums.OutboxEventStatus;

@Getter
@Setter
@MappedSuperclass
public abstract class OutboxEventEntity {
  @Id private UUID id;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "event_key", nullable = false)
  private String eventKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private OutboxEventStatus outboxEventStatus = OutboxEventStatus.PENDING;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "http_status")
  private Integer httpStatus;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "next_retry_at")
  private Instant nextRetryAt;

  @Column(name = "uncertain_until")
  private Instant uncertainUntil;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "locked_by")
  private String lockedBy;
}
