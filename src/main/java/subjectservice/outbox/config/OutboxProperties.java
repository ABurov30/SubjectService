package subjectservice.outbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("outbox")
public record OutboxProperties(
    @DefaultValue("5s") Duration polling,
    @DefaultValue("50") int batchSize,
    @DefaultValue("60s") Duration lease,
    @DefaultValue("-1") int maxRetries,
    @DefaultValue("5s") Duration initialDelay,
    @DefaultValue("2") double multiplier,
    @DefaultValue("5m") Duration maxDelay,
    @DefaultValue("30s") Duration uncertainDelay,
    @DefaultValue("15s") Duration batchPause,
    @DefaultValue("5s") Duration completionMargin,
    @DefaultValue("3") int warningRetries,
    @DefaultValue("1h") Duration warningAge) {
  public OutboxProperties {
    for (Duration d :
        new Duration[] {
          polling,
          lease,
          initialDelay,
          maxDelay,
          uncertainDelay,
          batchPause,
          completionMargin,
          warningAge
        }) {
      if (d == null || d.toMillis() < 1) {
        throw new IllegalArgumentException("Outbox durations must be positive");
      }
    }
    if (batchSize < 1
        || maxRetries < -1
        || maxRetries == Integer.MAX_VALUE
        || warningRetries < 0
        || !Double.isFinite(multiplier)
        || multiplier < 1
        || maxDelay.compareTo(initialDelay) < 0) {
      throw new IllegalArgumentException("Invalid Outbox limits/backoff");
    }
  }

  public int maxAttempts() {
    return maxRetries == -1 ? -1 : maxRetries + 1;
  }

  public boolean canAttempt(int attemptCount) {
    return maxRetries == -1 || attemptCount < maxAttempts();
  }

  public Duration backoff(int attemptCount) {
    return Duration.ofMillis(
        (long)
            Math.min(
                maxDelay.toMillis(),
                initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attemptCount - 1))));
  }

  public static OutboxProperties defaults() {
    return new OutboxProperties(
        Duration.ofSeconds(5),
        50,
        Duration.ofSeconds(60),
        -1,
        Duration.ofSeconds(5),
        2,
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(15),
        Duration.ofSeconds(5),
        3,
        Duration.ofHours(1));
  }
}
