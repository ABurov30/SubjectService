package subjectservice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import subjectservice.entity.Subject;
import subjectservice.entity.SubjectServiceOutbox;
import subjectservice.enums.SubjectStatus;
import subjectservice.integration.jira.JiraClient;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.repository.OutboxAttemptStore;
import subjectservice.outbox.service.OutboxDispatcher;
import subjectservice.outbox.worker.OutboxWorker;
import subjectservice.repository.SubjectOutboxRepository;
import subjectservice.repository.SubjectRepository;
import subjectservice.service.SubjectService;

@SpringBootTest(
    properties = {
      "outbox.scheduler-enabled=false",
      "outbox.max-retries=2",
      "outbox.initial-delay=20ms",
      "outbox.max-delay=100ms",
      "outbox.uncertain-delay=500ms",
      "outbox.batch-pause=500ms",
      "jira.response-timeout=300ms",
      "jira.call-timeout=500ms",
      "jira.attempt-timeout=2s",
      "outbox.lease=4s",
      "outbox.completion-margin=500ms"
    })
@AutoConfigureMockMvc
@Import(SubjectFlowIT.CoordinationConfig.class)
class SubjectFlowIT {
  static final PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");
  static final WireMockServer jira = new WireMockServer(0);
  static volatile UUID coordinatedId;
  static volatile CyclicBarrier reads;

  static {
    pg.start();
    jira.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", pg::getJdbcUrl);
    r.add("spring.datasource.username", pg::getUsername);
    r.add("spring.datasource.password", pg::getPassword);
    r.add("jira.url", jira::baseUrl);
  }

  @AfterAll
  static void stop() {
    jira.stop();
    pg.stop();
  }

  @TestConfiguration
  static class CoordinationConfig {
    @Bean
    HibernatePropertiesCustomizer versionRace() {
      return props ->
          props.put(
              "hibernate.session_factory.interceptor",
              new Interceptor() {
                @Override
                public boolean onLoad(
                    Object entity,
                    Object id,
                    Object[] state,
                    String[] propertyNames,
                    Type[] types) {
                  if (entity instanceof Subject && id.equals(coordinatedId)) {
                    try {
                      reads.await(10, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                      throw new IllegalStateException("Concurrent reads failed", ex);
                    }
                  }
                  return false;
                }
              });
    }
  }

  @Autowired MockMvc mvc;
  @Autowired SubjectService service;
  @Autowired SubjectRepository subjects;
  @MockitoSpyBean SubjectOutboxRepository outboxRepository;
  @Autowired JiraClient client;
  @Autowired OutboxAttemptStore<SubjectServiceOutbox> store;
  @Autowired OutboxProperties p;
  @Autowired PlatformTransactionManager manager;
  @Autowired Clock clock;
  OutboxWorker worker;

  @BeforeEach
  void reset() {
    outboxRepository.deleteAllInBatch();
    subjects.deleteAllInBatch();
    jira.resetAll();
    worker = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
  }

  @AfterEach
  void resetRace() {
    coordinatedId = null;
    reads = null;
  }

  UUID subject(SubjectStatus status) {
    Subject subject = new Subject();
    subject.setId(UUID.randomUUID());
    subject.setName("Example");
    subject.setStatus(status);
    return subjects.saveAndFlush(subject).getId();
  }

  UUID submit() {
    UUID id = subject(SubjectStatus.CREATED);
    service.change(id, SubjectStatus.REVIEW);
    return id;
  }

  UUID eventId(UUID id) {
    return event(id).getId();
  }

  String eventStatus(UUID id) {
    return event(id).getOutboxEventStatus().name();
  }

  String subjectStatus(UUID id) {
    return subjects.findById(id).orElseThrow().getStatus().name();
  }

  int attempts(UUID id) {
    return event(id).getAttemptCount();
  }

  void ready(UUID id) {
    updateEvent(id, e -> e.setNextRetryAt(Instant.now().minusSeconds(1)));
  }

  SubjectServiceOutbox event(UUID id) {
    return outboxRepository.findBySubjectId(id).orElseThrow();
  }

  void updateEvent(UUID id, Consumer<SubjectServiceOutbox> change) {
    SubjectServiceOutbox event = event(id);
    change.accept(event);
    outboxRepository.saveAndFlush(event);
  }

  void searchEmpty() {
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withBody("{\"issues\":[],\"isLast\":true}")));
  }

  void searchFound() {
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(
                aResponse()
                    .withBody(
                        "{\"issues\":[{\"id\":\"100\",\"key\":\"TEST-1\"}],\"isLast\":true}")));
  }

  void create() {
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(
                aResponse().withStatus(201).withBody("{\"id\":\"100\",\"key\":\"TEST-1\"}")));
  }

  @Test
  void createPersistsAndCanBeSubmittedThroughRest() throws Exception {
    String name = "a".repeat(255);
    String response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/subjects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value(name))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(new ObjectMapper().readTree(response).get("id").asText());
    assertEquals("CREATED", subjectStatus(id));
    assertEquals(name, subjects.findById(id).orElseThrow().getName());
    assertEquals(0L, subjects.findById(id).orElseThrow().getVersion());
    assertEquals(0, outboxRepository.count());
    assertTrue(jira.getAllServeEvents().isEmpty());
    mvc.perform(
            patch("/subjects/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"" + id + "\",\"status\":\"REVIEW\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    assertEquals("REVIEW", subjectStatus(id));
    assertEquals("PENDING", eventStatus(id));
    assertTrue(jira.getAllServeEvents().isEmpty());
  }

  @Test
  void patchPersistsWithoutJiraAndLaterPublishes() throws Exception {
    UUID id = subject(SubjectStatus.CREATED);
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(503)));
    mvc.perform(
            patch("/subjects/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectId\":\"" + id + "\",\"status\":\"REVIEW\"}"))
        .andExpect(status().isOk());
    assertEquals("REVIEW", subjectStatus(id));
    assertEquals("PENDING", eventStatus(id));
    assertEquals(0, attempts(id));
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    worker.run();
    assertEquals("PENDING", eventStatus(id));
    assertEquals(1, attempts(id));
    searchEmpty();
    create();
    ready(id);
    worker = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
    worker.run();
    assertEquals("PUBLISHED", eventStatus(id));
    assertEquals("TEST-1", event(id).getJiraIssueKey());
  }

  @Test
  void publishedOperationSurvivesRepeatedReviewAndOtherStatuses() {
    UUID id = submit();
    searchEmpty();
    create();
    worker.run();
    UUID event = eventId(id);
    Map<String, Object> payload = event(id).getPayload();
    Long version = subjects.findById(id).orElseThrow().getVersion();
    service.change(id, SubjectStatus.REVIEW);
    assertEquals(version, subjects.findById(id).orElseThrow().getVersion());
    service.change(id, SubjectStatus.CREATED);
    service.change(id, SubjectStatus.TERMINATED);
    service.change(id, SubjectStatus.REVIEW);
    assertEquals(event, eventId(id));
    assertEquals("PUBLISHED", eventStatus(id));
    assertEquals(payload, event(id).getPayload());
    worker.run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
    UUID other = subject(SubjectStatus.CREATED);
    service.change(other, SubjectStatus.TERMINATED);
    service.change(other, SubjectStatus.CREATED);
    assertEquals(0, outboxRepository.countBySubjectId(other));
  }

  @Test
  void atomicRegistrationRollsBackVersionedSubject() {
    UUID id = service.create("Example").id();
    var failure = new IllegalStateException("intentional registration failure");
    SubjectOutboxRepository repositorySpy = AopTestUtils.getUltimateTargetObject(outboxRepository);
    doThrow(failure)
        .when(repositorySpy)
        .register(any(UUID.class), eq(id), anyString(), anyString());

    assertSame(
        failure,
        assertThrows(IllegalStateException.class, () -> service.change(id, SubjectStatus.REVIEW)));
    verify(repositorySpy)
        .register(any(UUID.class), eq(id), eq("jira:create:subject:" + id), anyString());
    Subject subject = subjects.findById(id).orElseThrow();
    assertEquals(SubjectStatus.CREATED, subject.getStatus());
    assertEquals(0L, subject.getVersion());
    assertEquals(0L, outboxRepository.count());
  }

  @Test
  void optimisticRaceReturns409AndOnlyWinnerCommits() throws Exception {
    UUID id = subject(SubjectStatus.CREATED);
    coordinatedId = id;
    reads = new CyclicBarrier(2);
    var pool = Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Integer> request =
          () ->
              mvc.perform(
                      patch("/subjects/status")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"subjectId\":\"" + id + "\",\"status\":\"REVIEW\"}"))
                  .andReturn()
                  .getResponse()
                  .getStatus();
      var a = pool.submit(request);
      var b = pool.submit(request);
      var result = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
      coordinatedId = null;
      assertTrue(result.contains(200));
      assertTrue(result.contains(409));
      assertEquals("REVIEW", subjectStatus(id));
      assertEquals(1L, subjects.findById(id).orElseThrow().getVersion());
      assertEquals(1, outboxRepository.countBySubjectId(id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void simultaneousRegistrationOfExistingReviewUsesOnConflict() throws Exception {
    UUID id = subject(SubjectStatus.REVIEW);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(() -> service.change(id, SubjectStatus.REVIEW));
      var b = pool.submit(() -> service.change(id, SubjectStatus.REVIEW));
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      assertEquals(1, outboxRepository.countBySubjectId(id));
      assertEquals(0L, subjects.findById(id).orElseThrow().getVersion());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void parallelWorkersCreateOnce() throws Exception {
    UUID id = submit();
    searchEmpty();
    create();
    var second = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a = pool.submit(worker::run);
      var b = pool.submit(second::run);
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
      assertEquals("PUBLISHED", eventStatus(id));
      jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void foundIssueAndDuplicateIssuesNeverCreate() {
    UUID id = submit();
    searchFound();
    worker.run();
    assertEquals("PUBLISHED", eventStatus(id));
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
    UUID other = submit();
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(
                aResponse()
                    .withBody(
                        "{\"issues\":[{\"id\":\"1\",\"key\":\"TEST-1\"},{\"id\":\"2\",\"key\":\"TEST-2\"}],\"isLast\":true}")));
    worker.run();
    assertEquals("FAILED", eventStatus(other));
    assertEquals(1, event(other).getRetryCount());
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void lostCreateResponseWaitsForVisibilityAndSearchesInsteadOfPostingAgain() throws Exception {
    UUID id = submit();
    searchEmpty();
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withFixedDelay(1200)
                    .withBody("{\"id\":\"100\",\"key\":\"TEST-1\"}")));
    worker.run();
    assertEquals("PENDING", eventStatus(id));
    assertEquals(1, attempts(id));
    assertEquals(1, event(id).getRetryCount());
    assertNotNull(event(id).getUncertainUntil());
    worker.run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
    searchFound();
    Thread.sleep(650);
    worker.run();
    assertEquals("PUBLISHED", eventStatus(id));
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void crashAfterJiraResponseRecoversWithSameLabel() throws Exception {
    UUID id = submit();
    searchEmpty();
    create();
    var a = store.claimNext().orElseThrow();
    assertTrue(store.begin(a));
    var delivery = worker.deliver(a);
    assertTrue(delivery.outcome().success());
    assertEquals("IN_PROGRESS", eventStatus(id));
    updateEvent(id, e -> e.setLockedUntil(Instant.now().minusSeconds(1)));
    searchFound();
    worker.run();
    assertEquals("PENDING", eventStatus(id));
    assertEquals(1, attempts(id));
    worker.run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    Thread.sleep(650);
    worker.run();
    assertEquals("PUBLISHED", eventStatus(id));
    assertFalse(store.complete(a, OutboxOutcome.published(), e -> e.setJiraIssueKey("STALE")));
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void permanentFailureRemainsFailedAfterRepeatedReview() throws Exception {
    UUID id = submit();
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(403)));
    worker.run();
    assertEquals("FAILED", eventStatus(id));
    service.change(id, SubjectStatus.CREATED);
    service.change(id, SubjectStatus.REVIEW);
    worker.run();
    assertEquals("FAILED", eventStatus(id));
    assertEquals(1, attempts(id));
    searchFound();
    worker.run();
    assertEquals("FAILED", eventStatus(id));
    assertEquals(1, attempts(id));
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void batchStopsOn503And429WithoutClaimingOtherRows() {
    UUID first = submit();
    UUID second = submit();
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql")).willReturn(aResponse().withStatus(503)));
    worker.run();
    assertEquals(1, attempts(first) + attempts(second));
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    worker.run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    jira.resetAll();
    worker = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));
    worker.run();
    assertEquals(2, attempts(first) + attempts(second));
    worker.run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    var latest =
        outboxRepository.findAll().stream()
            .map(SubjectServiceOutbox::getNextRetryAt)
            .filter(java.util.Objects::nonNull)
            .max(Instant::compareTo)
            .orElseThrow();
    assertTrue(latest.isAfter(Instant.now().plusSeconds(50)));
    assertNull(event(first).getJiraIssueKey());
  }

  @Test
  void recordedResultPreventsAnotherCreateEvenAfterRecovery() {
    UUID id = submit();
    updateEvent(
        id,
        e -> {
          e.setJiraIssueId("100");
          e.setJiraIssueKey("TEST-1");
        });
    worker.run();
    assertEquals("PUBLISHED", eventStatus(id));
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
    jira.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
  }

  @ParameterizedTest
  @ValueSource(ints = {503, 429})
  void retryBudgetSurvivesWorkerRecreation(int responseStatus) throws Exception {
    assertEquals(2, p.maxRetries());

    UUID id = submit();
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withStatus(responseStatus)));
    for (int attempt = 1; attempt <= 3; attempt++) {
      worker = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
      worker.run();
      assertEquals(attempt, attempts(id));
      assertEquals(attempt, event(id).getRetryCount());
      if (attempt < 3) {
        ready(id);
      }
    }
    assertEquals("FAILED", eventStatus(id));
    assertNull(event(id).getNextRetryAt());
    worker = new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client);
    worker.run();
    jira.verify(3, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    searchFound();
    worker.run();
    assertEquals("FAILED", eventStatus(id));
    assertEquals(3, attempts(id));
    jira.verify(3, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
    assertEquals(responseStatus, event(id).getHttpStatus());
    assertEquals(3, event(id).getRetryCount());
  }

  @Test
  void retryAfterHttpDateNeverMakesAnEarlyCall() {
    UUID id = submit();
    Instant deadline =
        Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    String date =
        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
            deadline.atZone(java.time.ZoneOffset.UTC));
    jira.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", date)));
    worker.run();
    assertEquals("PENDING", eventStatus(id));
    Instant next = event(id).getNextRetryAt();
    assertFalse(next.isBefore(deadline));
    new OutboxWorker(new OutboxDispatcher<>(store, p, manager, clock), store, client).run();
    jira.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/search/jql")));
  }
}
