package subjectservice.outbox.repository;

import java.util.Optional;
import java.util.function.Consumer;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.entity.OutboxEventEntity;

public interface OutboxAttemptStore<E extends OutboxEventEntity> {
  Optional<OutboxAttempt<E>> claimNext();

  boolean begin(OutboxAttempt<E> attempt);

  boolean renew(OutboxAttempt<E> attempt);

  int recover();

  boolean complete(OutboxAttempt<E> attempt, OutboxOutcome outcome, Consumer<E> result);
}
