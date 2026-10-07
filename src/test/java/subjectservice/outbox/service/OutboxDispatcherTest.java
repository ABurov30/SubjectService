package subjectservice.outbox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.entity.OutboxEventEntity;
import subjectservice.outbox.repository.OutboxAttemptStore;

class OutboxDispatcherTest {
  static class Event extends OutboxEventEntity {}

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-06T00:00:00Z");

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void stopsBatchAndHonorsRetryAfter() {
    var store = (OutboxAttemptStore<Event>) mock(OutboxAttemptStore.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var clock = new MutableClock();
    var d = new OutboxDispatcher<>(store, OutboxProperties.defaults(), manager, clock);
    Event event = new Event();
    event.setId(UUID.randomUUID());
    event.setCreatedAt(clock.instant().minusSeconds(7200));
    event.setRetryCount(4);
    event.setAttemptCount(6);
    var a = new OutboxAttempt<>(event.getId(), "owner", event);
    when(store.claimNext()).thenReturn(Optional.of(a), Optional.empty());
    when(store.begin(a)).thenReturn(true);
    when(store.complete(any(), any(), any())).thenReturn(true);
    var calls = new AtomicInteger();
    d.dispatch(
        attempt -> {
          calls.incrementAndGet();
          return new OutboxDispatcher.Delivery<>(
              OutboxOutcome.builder()
                  .retryable(true)
                  .httpCalled(true)
                  .stopBatch(true)
                  .uncertain(false)
                  .httpStatus(429)
                  .message("rate limit")
                  .retryAfter(clock.instant().plusSeconds(100))
                  .build(),
              e -> {});
        });
    assertEquals(1, calls.get());
    d.dispatch(
        attempt -> {
          throw new AssertionError("paused");
        });
    verify(store).claimNext();
    clock.now = clock.now.plusSeconds(101);
    d.dispatch(
        attempt -> {
          throw new AssertionError("empty");
        });
    verify(store, times(2)).claimNext();
    verify(store, times(3)).recover();
    verify(store).complete(any(), any(), any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void successStaleSkipAndDatabaseFailure() {
    var store = (OutboxAttemptStore<Event>) mock(OutboxAttemptStore.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var clock = new MutableClock();
    var d = new OutboxDispatcher<>(store, OutboxProperties.defaults(), manager, clock);
    Event e = new Event();
    e.setId(UUID.randomUUID());
    e.setCreatedAt(clock.instant());
    var a = new OutboxAttempt<>(e.getId(), "owner", e);
    when(store.claimNext())
        .thenReturn(Optional.of(a), Optional.of(a), Optional.of(a), Optional.empty());
    when(store.begin(a)).thenReturn(false, true, true);
    when(store.complete(any(), any(), any())).thenReturn(false, true);
    d.dispatch(attempt -> new OutboxDispatcher.Delivery<>(OutboxOutcome.published(), event -> {}));
    when(store.claimNext()).thenReturn(Optional.of(a));
    doThrow(new IllegalStateException("DB unavailable")).when(store).complete(any(), any(), any());
    d.dispatch(attempt -> new OutboxDispatcher.Delivery<>(OutboxOutcome.published(), event -> {}));
    verify(store, times(3)).complete(any(), any(), any());
    d.dispatch(
        attempt -> {
          throw new AssertionError("paused after database failure");
        });
    verify(store, times(3)).complete(any(), any(), any());
  }
}
