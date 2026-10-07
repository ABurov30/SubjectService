package subjectservice.outbox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.entity.OutboxEventEntity;
import subjectservice.outbox.enums.OutboxEventStatus;

class JpaOutboxAttemptStoreIT {
  @Entity(name = "TestOutboxEvent")
  @Table(name = "events")
  public static class Event extends OutboxEventEntity {}

  static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");
  static LocalContainerEntityManagerFactoryBean factory;
  static EntityManager em;
  static JpaTransactionManager manager;

  public interface EventRepository extends JpaRepository<Event, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from TestOutboxEvent e where e.id = :id")
    Optional<Event> lockById(UUID id);
  }

  static EventRepository events;

  @BeforeAll
  static void start() {
    pg.start();
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.connection.url", pg.getJdbcUrl(),
            "hibernate.connection.username", pg.getUsername(),
            "hibernate.connection.password", pg.getPassword(),
            "hibernate.hbm2ddl.auto", "create-drop"));
    factory.setPackagesToScan("subjectservice.outbox");
    var adapter = new HibernateJpaVendorAdapter();
    adapter.setGenerateDdl(true);
    factory.setJpaVendorAdapter(adapter);
    factory.afterPropertiesSet();
    em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
    manager = new JpaTransactionManager(factory.getObject());
    JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
    TransactionInterceptor transactions = new TransactionInterceptor();
    transactions.setTransactionManager(manager);
    transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
    repositories.addRepositoryProxyPostProcessor((proxy, info) -> proxy.addAdvice(transactions));
    events = repositories.getRepository(EventRepository.class);
  }

  @AfterAll
  static void stop() {
    if (factory != null) {
      factory.destroy();
    }
    pg.stop();
  }

  @BeforeEach
  void clean() {
    events.deleteAllInBatch();
  }

  JpaOutboxAttemptStore<Event> store(int retries) {
    var d = OutboxProperties.defaults();
    return new JpaOutboxAttemptStore<>(
        em,
        manager,
        Event.class,
        "events",
        new OutboxProperties(
            d.polling(),
            d.batchSize(),
            d.lease(),
            retries,
            Duration.ofMillis(10),
            2,
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            d.batchPause(),
            d.completionMargin(),
            d.warningRetries(),
            d.warningAge()));
  }

  UUID insert() {
    Event event = new Event();
    event.setId(UUID.randomUUID());
    event.setSubjectId(UUID.randomUUID());
    event.setAggregateType("Aggregate");
    event.setEventType("SEND");
    event.setEventKey(event.getId().toString());
    event.setPayload(Map.of());
    event.setCreatedAt(Instant.now());
    return events.saveAndFlush(event).getId();
  }

  String status(UUID id) {
    return events.findById(id).orElseThrow().getOutboxEventStatus().name();
  }

  void ready(UUID id) {
    update(id, e -> e.setNextRetryAt(Instant.now().minusSeconds(1)));
  }

  void update(UUID id, Consumer<Event> change) {
    Event event = events.findById(id).orElseThrow();
    change.accept(event);
    events.saveAndFlush(event);
  }

  @Test
  void claimStartPublishAndRollback() {
    var store = store(2);
    UUID id = insert();
    var a = store.claimNext().orElseThrow();
    assertEquals(id, a.eventId());
    assertEquals(0, a.event().getRetryCount());
    assertEquals(0, a.event().getAttemptCount());
    assertEquals("IN_PROGRESS", status(id));
    assertTrue(store.claimNext().isEmpty());
    assertTrue(store.begin(a));
    assertEquals(1, events.findById(id).orElseThrow().getAttemptCount());
    assertTrue(store.renew(a));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.complete(
                a,
                OutboxOutcome.published(),
                e -> {
                  throw new IllegalArgumentException("result write failed");
                }));
    assertEquals("IN_PROGRESS", status(id));
    assertTrue(store.complete(a, OutboxOutcome.published(), e -> e.setEventKey("saved-result")));
    assertEquals("PUBLISHED", status(id));
    assertNotNull(events.findById(id).orElseThrow().getSentAt());
    assertFalse(store.renew(a));
    assertFalse(store.complete(a, OutboxOutcome.published(), e -> {}));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JpaOutboxAttemptStore<>(
                em,
                manager,
                Event.class,
                "events; DROP TABLE events",
                OutboxProperties.defaults()));
  }

  @Test
  void retryLimitLastSuccessAndPermanentFailure() {
    var store = store(2);
    UUID id = insert();
    var failure =
        OutboxOutcome.builder()
            .retryable(true)
            .httpCalled(true)
            .stopBatch(true)
            .uncertain(false)
            .httpStatus(503)
            .message("unavailable")
            .retryAfter(Instant.now().plusSeconds(60))
            .build();
    for (int i = 1; i <= 2; i++) {
      var a = store.claimNext().orElseThrow();
      assertTrue(store.begin(a));
      assertTrue(store.complete(a, failure, e -> {}));
      assertEquals("PENDING", status(id));
      assertEquals(i, events.findById(id).orElseThrow().getRetryCount());
      assertTrue(store.claimNext().isEmpty());
      ready(id);
    }
    var last = store.claimNext().orElseThrow();
    assertTrue(store.begin(last));
    assertTrue(store.complete(last, OutboxOutcome.published(), e -> {}));
    assertEquals("PUBLISHED", status(id));
    UUID other = insert();
    var a = store.claimNext().orElseThrow();
    assertTrue(store.begin(a));
    assertTrue(
        store.complete(
            a,
            OutboxOutcome.builder()
                .retryable(false)
                .httpCalled(true)
                .stopBatch(false)
                .uncertain(true)
                .httpStatus(400)
                .message("bad request, outcome unknown")
                .retryAfter(null)
                .build(),
            e -> {}));
    assertEquals("FAILED", status(other));
    assertNull(events.findById(other).orElseThrow().getNextRetryAt());
    assertTrue(store.claimNext().isEmpty());
    assertEquals(1, events.findById(other).orElseThrow().getAttemptCount());
  }

  @Test
  void exhaustedBudgetCrashesAndStaleTokens() {
    var zero = store(0);
    UUID id = insert();
    var a = zero.claimNext().orElseThrow();
    assertTrue(zero.begin(a));
    assertTrue(
        zero.complete(
            a,
            OutboxOutcome.builder()
                .retryable(true)
                .httpCalled(true)
                .stopBatch(true)
                .uncertain(true)
                .httpStatus(500)
                .message("uncertain")
                .retryAfter(null)
                .build(),
            e -> {}));
    assertEquals("FAILED", status(id));
    assertTrue(zero.claimNext().isEmpty());
    UUID crash = insert();
    var b = zero.claimNext().orElseThrow();
    assertTrue(zero.begin(b));
    update(crash, e -> e.setLockedUntil(Instant.now().minusSeconds(1)));
    assertFalse(zero.renew(b));
    assertEquals(1, zero.recover());
    assertEquals("FAILED", status(crash));
    assertEquals(1, events.findById(crash).orElseThrow().getAttemptCount());
    assertNotNull(events.findById(crash).orElseThrow().getUncertainUntil());
    assertEquals(0, events.findById(crash).orElseThrow().getRetryCount());
    assertFalse(zero.complete(b, OutboxOutcome.published(), e -> {}));
    var retry = store(2);
    UUID pending = insert();
    var c = retry.claimNext().orElseThrow();
    assertTrue(retry.begin(c));
    update(
        pending,
        e -> {
          e.setLockedUntil(Instant.now().minusSeconds(1));
          e.setNextRetryAt(Instant.now().plusSeconds(3600));
        });
    assertEquals(1, retry.recover());
    assertEquals("PENDING", status(pending));
    assertTrue(retry.claimNext().isEmpty());
    ready(pending);
    var newOwner = retry.claimNext().orElseThrow();
    assertFalse(retry.complete(c, OutboxOutcome.published(), e -> {}));
    assertTrue(retry.begin(newOwner));
    assertTrue(
        retry.complete(
            newOwner,
            OutboxOutcome.builder()
                .retryable(true)
                .httpCalled(false)
                .stopBatch(false)
                .uncertain(false)
                .httpStatus(null)
                .message("no HTTP")
                .retryAfter(null)
                .build(),
            e -> {}));
    assertEquals(0, events.findById(pending).orElseThrow().getRetryCount());
    ready(pending);
    update(pending, e -> e.setAttemptCount(3));
    var exhausted = retry.claimNext().orElseThrow();
    assertFalse(retry.begin(exhausted));
    assertEquals("FAILED", status(pending));
  }

  @Test
  void independentWorkersAndSkipLocked() throws Exception {
    var store = store(2);
    UUID id = insert();
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> store.claimNext());
      var second = pool.submit(() -> store.claimNext());
      Optional<OutboxAttempt<Event>> one = first.get(10, TimeUnit.SECONDS);
      Optional<OutboxAttempt<Event>> two = second.get(10, TimeUnit.SECONDS);
      assertTrue(one.isPresent() != two.isPresent());
      assertEquals("IN_PROGRESS", status(id));
    } finally {
      pool.shutdownNow();
    }
    update(
        id,
        e -> {
          e.setOutboxEventStatus(OutboxEventStatus.PENDING);
          e.setLockedUntil(null);
          e.setLockedBy(null);
        });
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              events.lockById(id).orElseThrow();
              assertTrue(store.claimNext().isEmpty());
              tx.setRollbackOnly();
            });
    assertTrue(store.claimNext().isPresent());
  }

  @Test
  void completionThatOutlivesLeaseRollsBackResult() throws Exception {
    UUID id = insert();
    var d = OutboxProperties.defaults();
    var shortLease =
        new JpaOutboxAttemptStore<>(
            em,
            manager,
            Event.class,
            "events",
            new OutboxProperties(
                d.polling(),
                d.batchSize(),
                Duration.ofMillis(500),
                2,
                d.initialDelay(),
                d.multiplier(),
                d.maxDelay(),
                d.uncertainDelay(),
                d.batchPause(),
                d.completionMargin(),
                d.warningRetries(),
                d.warningAge()));
    var a = shortLease.claimNext().orElseThrow();
    assertTrue(shortLease.begin(a));
    assertFalse(
        shortLease.complete(
            a,
            OutboxOutcome.published(),
            e -> {
              e.setEventKey("must-rollback");
              try {
                Thread.sleep(550);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
              }
            }));
    assertEquals("IN_PROGRESS", status(id));
    assertEquals(id.toString(), events.findById(id).orElseThrow().getEventKey());
  }

  @Test
  void zeroAndTwoRetriesHaveFiniteBudgets() {
    for (int retries : new int[] {0, 2}) {
      UUID id = insert();
      var store = store(retries);
      for (int count = 1; count <= retries + 1; count++) {
        var a = store.claimNext().orElseThrow();
        assertTrue(store.begin(a));
        assertTrue(
            store.complete(
                a,
                OutboxOutcome.builder()
                    .retryable(true)
                    .httpCalled(true)
                    .stopBatch(true)
                    .uncertain(false)
                    .httpStatus(503)
                    .message("unavailable")
                    .retryAfter(null)
                    .build(),
                e -> {}));
        assertEquals(count, events.findById(id).orElseThrow().getAttemptCount());
        assertEquals(count, events.findById(id).orElseThrow().getRetryCount());
        if (count < retries + 1) {
          ready(id);
        } else {
          assertNull(events.findById(id).orElseThrow().getNextRetryAt());
        }
      }
      assertEquals("FAILED", status(id));
      assertTrue(store.claimNext().isEmpty());
    }
  }
}
