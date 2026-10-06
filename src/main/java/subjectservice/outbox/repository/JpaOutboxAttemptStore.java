package subjectservice.outbox.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.entity.OutboxEventEntity;
import subjectservice.outbox.enums.OutboxEventStatus;

public final class JpaOutboxAttemptStore<E extends OutboxEventEntity>
    implements OutboxAttemptStore<E> {
  private static final Logger log = LoggerFactory.getLogger(JpaOutboxAttemptStore.class);
  private final EntityManager em;
  private final Class<E> entityType;
  private final String table;
  private final OutboxProperties p;
  private final TransactionTemplate tx;

  public JpaOutboxAttemptStore(
      EntityManager em,
      PlatformTransactionManager manager,
      Class<E> entityType,
      String table,
      OutboxProperties p) {
    if (table == null || !table.matches("[a-z_][a-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid table");
    }
    this.em = Objects.requireNonNull(em);
    this.entityType = Objects.requireNonNull(entityType);
    this.table = table;
    this.p = Objects.requireNonNull(p);
    tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  private Instant databaseNow() {
    return em.createNativeQuery("SELECT clock_timestamp()", Instant.class).getSingleResult()
            instanceof Instant now
        ? now
        : Instant.now();
  }

  private String owned() {
    return " WHERE id=:id AND status='IN_PROGRESS' AND locked_by=:token AND locked_until>clock_timestamp()";
  }

  private Query bind(Query q, OutboxAttempt<E> a) {
    return q.setParameter("id", a.eventId()).setParameter("token", a.token());
  }

  @Override
  public Optional<OutboxAttempt<E>> claimNext() {
    return tx.execute(
        s -> {
          var rows =
              em.createNativeQuery(
                      "SELECT * FROM "
                          + table
                          + " WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=clock_timestamp()) ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                      entityType)
                  .getResultList();
          if (rows.isEmpty()) {
            return Optional.empty();
          }
          E e = entityType.cast(rows.get(0));
          String token = UUID.randomUUID().toString();
          Instant now = databaseNow();
          e.setOutboxEventStatus(OutboxEventStatus.IN_PROGRESS);
          e.setLockedBy(token);
          e.setLockedAt(now);
          e.setLockedUntil(now.plus(p.lease()));
          em.flush();
          em.detach(e);
          return Optional.of(new OutboxAttempt<>(e.getId(), token, e));
        });
  }

  @Override
  public boolean begin(OutboxAttempt<E> a) {
    return tx.execute(
        s -> {
          int updated =
              bind(
                      em.createNativeQuery(
                          "UPDATE "
                              + table
                              + " SET attempt_count=attempt_count+1"
                              + owned()
                              + " AND attempt_count<:max"),
                      a)
                  .setParameter("max", p.maxAttempts())
                  .executeUpdate();
          if (updated == 1) {
            a.event().setAttemptCount(a.event().getAttemptCount() + 1);
            return true;
          }
          int exhausted =
              bind(
                      em.createNativeQuery(
                          "UPDATE "
                              + table
                              + " SET status='FAILED',error_message='Attempt budget exhausted',next_retry_at=NULL,locked_by=NULL,locked_until=NULL,locked_at=NULL"
                              + owned()
                              + " AND attempt_count>=:max"),
                      a)
                  .setParameter("max", p.maxAttempts())
                  .executeUpdate();
          if (exhausted == 1) {
            log.error("Outbox FAILED event={} reason=attempt budget exhausted", a.eventId());
          }
          return false;
        });
  }

  @Override
  public boolean renew(OutboxAttempt<E> a) {
    return tx.execute(
        s ->
            bind(
                        em.createNativeQuery(
                            "UPDATE "
                                + table
                                + " SET locked_until=clock_timestamp()+(:lease*INTERVAL '1 millisecond')"
                                + owned()),
                        a)
                    .setParameter("lease", p.lease().toMillis())
                    .executeUpdate()
                == 1);
  }

  @Override
  public int recover() {
    return tx.execute(
        s -> {
          var rows =
              em.createNativeQuery(
                      "UPDATE "
                          + table
                          + " SET status=CASE WHEN attempt_count>=:max THEN 'FAILED' ELSE 'PENDING' END,"
                          + " next_retry_at=CASE WHEN attempt_count>=:max THEN NULL ELSE GREATEST(next_retry_at,clock_timestamp()+(:delay*INTERVAL '1 millisecond')) END,"
                          + " uncertain_until=GREATEST(uncertain_until,clock_timestamp()+(:delay*INTERVAL '1 millisecond')),"
                          + " error_message='Lease expired; external result may be unknown',locked_by=NULL,locked_at=NULL,locked_until=NULL"
                          + " WHERE id IN (SELECT id FROM "
                          + table
                          + " WHERE status='IN_PROGRESS' AND locked_until<=clock_timestamp() ORDER BY created_at,id LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING id,status")
                  .setParameter("max", p.maxAttempts())
                  .setParameter("delay", p.uncertainDelay().toMillis())
                  .setParameter("limit", p.batchSize())
                  .getResultList();
          for (Object row : rows) {
            Object[] values = (Object[]) row;
            if ("FAILED".equals(values[1])) {
              log.error(
                  "Outbox FAILED event={} reason=expired lease and exhausted budget; external result may be unknown",
                  values[0]);
            }
          }
          return rows.size();
        });
  }

  @Override
  public boolean complete(OutboxAttempt<E> a, OutboxOutcome o, Consumer<E> result) {
    return tx.execute(
        s -> {
          E e = em.find(entityType, a.eventId(), LockModeType.PESSIMISTIC_WRITE);
          if (e == null) {
            return false;
          }
          Instant now = databaseNow();
          if (e.getOutboxEventStatus() != OutboxEventStatus.IN_PROGRESS
              || !a.token().equals(e.getLockedBy())
              || e.getLockedUntil() == null
              || !e.getLockedUntil().isAfter(now)) {
            return false;
          }
          if (o.success()) {
            result.accept(e);
            e.setOutboxEventStatus(OutboxEventStatus.PUBLISHED);
            e.setSentAt(now);
            e.setNextRetryAt(null);
            e.setErrorMessage(null);
            e.setHttpStatus(null);
            e.setUncertainUntil(null);
          } else {
            if (o.httpCalled()) {
              e.setRetryCount(e.getRetryCount() + 1);
            }
            e.setErrorMessage(o.message());
            e.setHttpStatus(o.httpStatus());
            if (o.uncertain()) {
              e.setUncertainUntil(now.plus(p.uncertainDelay()));
            }
            if (o.retryable() && e.getAttemptCount() < p.maxAttempts()) {
              e.setOutboxEventStatus(OutboxEventStatus.PENDING);
              Instant next = now.plus(p.backoff(e.getAttemptCount()));
              if (o.retryAfter() != null && o.retryAfter().isAfter(next)) {
                next = o.retryAfter();
              }
              if (e.getUncertainUntil() != null && e.getUncertainUntil().isAfter(next)) {
                next = e.getUncertainUntil();
              }
              e.setNextRetryAt(next);
            } else {
              e.setOutboxEventStatus(OutboxEventStatus.FAILED);
              e.setNextRetryAt(null);
            }
          }
          OutboxEventStatus desired = e.getOutboxEventStatus();
          e.setOutboxEventStatus(OutboxEventStatus.IN_PROGRESS);
          em.flush();
          int saved =
              bind(
                      em.createNativeQuery(
                          "UPDATE "
                              + table
                              + " SET status=:status,locked_by=NULL,locked_at=NULL,locked_until=NULL"
                              + owned()),
                      a)
                  .setParameter("status", desired.name())
                  .executeUpdate();
          if (saved != 1) {
            s.setRollbackOnly();
            return false;
          }
          return true;
        });
  }
}
