package subjectservice.outbox.dto;

import java.time.Instant;

public record OutboxOutcome(
    boolean success,
    boolean retryable,
    boolean httpCalled,
    boolean stopBatch,
    boolean uncertain,
    Integer httpStatus,
    String message,
    Instant retryAfter) {
  public static OutboxOutcome published() {
    return new OutboxOutcome(true, false, true, false, false, null, null, null);
  }

  public static OutboxOutcome failure(
      boolean retryable,
      boolean httpCalled,
      boolean stopBatch,
      boolean uncertain,
      Integer httpStatus,
      String message,
      Instant retryAfter) {
    return new OutboxOutcome(
        false, retryable, httpCalled, stopBatch, uncertain, httpStatus, message, retryAfter);
  }
}
