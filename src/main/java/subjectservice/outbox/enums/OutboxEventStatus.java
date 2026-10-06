package subjectservice.outbox.enums;

public enum OutboxEventStatus {
  PENDING,
  IN_PROGRESS,
  PUBLISHED,
  FAILED
}
