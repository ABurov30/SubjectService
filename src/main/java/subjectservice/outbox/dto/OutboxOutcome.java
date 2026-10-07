package subjectservice.outbox.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
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
    return OutboxOutcome.builder().success(true).httpCalled(true).build();
  }
}
