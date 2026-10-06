package subjectservice.outbox.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.entity.OutboxEventEntity;
import subjectservice.outbox.repository.OutboxAttemptStore;

public final class OutboxDispatcher<E extends OutboxEventEntity> {
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
  private final OutboxAttemptStore<E> store;
  private final OutboxProperties p;
  private final Clock clock;
  private final TransactionTemplate outside;
  private Instant pausedUntil = Instant.MIN;

  public record Delivery<E>(OutboxOutcome outcome, Consumer<E> result) {}

  public OutboxDispatcher(
      OutboxAttemptStore<E> store,
      OutboxProperties p,
      PlatformTransactionManager manager,
      Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.p = Objects.requireNonNull(p);
    this.clock = Objects.requireNonNull(clock);
    outside = new TransactionTemplate(manager);
    outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
  }

  public synchronized void dispatch(Function<OutboxAttempt<E>, Delivery<E>> sender) {
    outside.executeWithoutResult(
        s -> {
          store.recover();
          if (clock.instant().isBefore(pausedUntil)) {
            return;
          }
          for (int i = 0; i < p.batchSize(); i++) {
            var attempt = store.claimNext();
            if (attempt.isEmpty()) {
              return;
            }
            var a = attempt.orElseThrow();
            if (!store.begin(a)) {
              continue;
            }
            try {
              Delivery<E> d = sender.apply(a);
              boolean saved = store.complete(a, d.outcome(), d.result());
              if (saved && !d.outcome().success()) {
                if (!d.outcome().retryable() || a.event().getAttemptCount() >= p.maxAttempts()) {
                  log.error("Outbox FAILED event={} reason={}", a.eventId(), d.outcome().message());
                }
              }
              int completedFailures =
                  a.event().getRetryCount() + (d.outcome().httpCalled() ? 1 : 0);
              if (saved
                  && !d.outcome().success()
                  && (completedFailures >= p.warningRetries()
                      || a.event().getCreatedAt().plus(p.warningAge()).isBefore(clock.instant()))) {
                log.warn("Outbox requires attention event={}", a.eventId());
              }
              if (d.outcome().stopBatch()) {
                pausedUntil = clock.instant().plus(p.batchPause());
                if (d.outcome().retryAfter() != null
                    && d.outcome().retryAfter().isAfter(pausedUntil)) {
                  pausedUntil = d.outcome().retryAfter();
                }
                return;
              }
            } catch (RuntimeException ex) {
              pausedUntil = clock.instant().plus(p.batchPause());
              log.error(
                  "Attempt interrupted; lease recovery will resolve event {}", a.eventId(), ex);
              return;
            }
          }
        });
  }
}
