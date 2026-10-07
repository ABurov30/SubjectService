package subjectservice.integration.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JiraClientTest {
  WireMockServer server;
  JiraClient client;
  final Instant now = Instant.parse("2026-10-06T00:00:00Z");
  final Map<String, Object> payload = Map.of("jiraLabel", "subject-123", "name", "Review Subject");

  @BeforeEach
  void start() {
    server = new WireMockServer(0);
    server.start();
    client = client(10, Duration.ofSeconds(30));
  }

  JiraClient client(int pages, Duration attempt) {
    return client(
        pages, attempt, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10));
  }

  JiraClient timeoutClient(int pages, Duration attempt) {
    return client(
        pages, attempt, Duration.ofMillis(200), Duration.ofMillis(300), Duration.ofMillis(500));
  }

  JiraClient client(
      int pages, Duration attempt, Duration connect, Duration response, Duration call) {
    return new JiraClient(
        new JiraProperties(
            URI.create(server.baseUrl()),
            "email",
            "secret",
            "TEST",
            "Task",
            connect,
            response,
            call,
            attempt,
            pages),
        new ObjectMapper(),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @AfterEach
  void stop() throws Exception {
    client.close();
    server.stop();
  }

  void search(String response) {
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(response)));
  }

  @Test
  void createsWithLabelInSameRequest() {
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(
                aResponse().withStatus(201).withBody("{\"id\":\"100\",\"key\":\"TEST-1\"}")));
    assertEquals(new JiraClient.Issue("100", "TEST-1"), client.resolve(payload, () -> true));
    server.verify(
        postRequestedFor(urlEqualTo("/rest/api/3/issue"))
            .withRequestBody(
                equalToJson(
                    "{\"fields\":{\"project\":{\"key\":\"TEST\"},\"issuetype\":{\"name\":\"Task\"},\"summary\":\"Review Subject\",\"labels\":[\"subject-123\"]}}")));
    server.verify(
        postRequestedFor(urlEqualTo("/rest/api/3/search/jql"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath(
                    "$.jql",
                    com.github.tomakehurst.wiremock.client.WireMock.equalTo(
                        "project = \"TEST\" AND labels = \"subject-123\""))));
  }

  @Test
  void searchesAllPagesAndReusesExisting() {
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .withRequestBody(equalToJson("{\"nextPageToken\":\"page2\"}", true, true))
            .willReturn(
                aResponse()
                    .withBody("{\"issues\":[{\"id\":\"1\",\"key\":\"TEST-1\"}],\"isLast\":true}")));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withBody("{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"page2\"}")));
    assertEquals("TEST-1", client.resolve(payload, () -> true).key());
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void duplicateMatchesSuppressCreation() {
    search(
        "{\"issues\":[{\"id\":\"1\",\"key\":\"TEST-1\"},{\"id\":\"2\",\"key\":\"TEST-2\"}],\"isLast\":true}");
    var ex = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertFalse(ex.outcome().retryable(), ex.outcome().message());
    assertEquals(200, ex.outcome().httpStatus());
    assertTrue(ex.outcome().httpCalled());
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 422, 429, 500, 503})
  void classifiesSearchErrors(int status) {
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withStatus(status).withHeader("Retry-After", "60")));
    var ex = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertEquals(status, ex.outcome().httpStatus());
    assertEquals(status == 429 || status >= 500, ex.outcome().retryable());
    assertFalse(ex.outcome().uncertain());
    assertEquals(now.plusSeconds(60), ex.outcome().retryAfter());
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 429, 500})
  void classifiesCreationErrorsAndUncertainty(int status) {
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue")).willReturn(aResponse().withStatus(status)));
    var ex = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertEquals(status >= 500, ex.outcome().uncertain());
    assertEquals(status == 429 || status >= 500, ex.outcome().retryable());
  }

  @Test
  void creationTimeoutIsUncertain() throws Exception {
    client.close();
    client = timeoutClient(10, Duration.ofSeconds(2));
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(aResponse().withStatus(201).withFixedDelay(1000).withBody("{}")));
    assertTrue(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .uncertain());
  }

  @Test
  void unparseableCreateResultsAreUncertain() {
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(aResponse().withStatus(201).withBody("not json")));
    assertTrue(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .uncertain());
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(aResponse().withStatus(201).withBody("{}")));
    assertTrue(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .uncertain());
  }

  @Test
  void lostOwnershipNeverStartsNextRequest() {
    var noLease = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> false));
    assertFalse(noLease.outcome().httpCalled());
    search("{\"issues\":[],\"isLast\":true}");
    AtomicInteger guards = new AtomicInteger();
    var lost =
        assertThrows(
            JiraFailure.class, () -> client.resolve(payload, () -> guards.incrementAndGet() == 1));
    assertTrue(lost.outcome().httpCalled());
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void pageLimitAndTotalDeadlineBoundAttempt() throws Exception {
    client.close();
    client = client(1, Duration.ofSeconds(30));
    search("{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"next\"}");
    assertFalse(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .retryable());
    client.close();
    client = timeoutClient(10, Duration.ofMillis(500));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(aResponse().withFixedDelay(1000).withBody("{}")));
    assertTrue(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .stopBatch());
  }

  @Test
  void retryAfterAndEscaping() {
    assertEquals(now.plusSeconds(10), client.retryAfter("10"));
    assertEquals(now.plusSeconds(30), client.retryAfter("Tue, 6 Oct 2026 00:00:30 GMT"));
    assertNull(client.retryAfter(null));
    assertNull(client.retryAfter("-1"));
    assertNull(client.retryAfter("wrong"));
    assertNull(client.retryAfter("999999999999999999999999"));
    assertEquals("\"a\\\"b\\\\c\"", JiraClient.quote("a\"b\\c"));
  }

  @Test
  void fullCallTimeoutStopsSlowBodyEvenWhenSocketReadsKeepArriving() throws Exception {
    client.close();
    client = timeoutClient(10, Duration.ofSeconds(2));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .willReturn(
                aResponse()
                    .withChunkedDribbleDelay(10, 1200)
                    .withBody("{\"issues\":[],\"isLast\":true}")));
    long start = System.nanoTime();
    var failure = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertTrue(failure.outcome().retryable());
    assertFalse(failure.outcome().uncertain());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void totalAttemptDeadlineIncludesAllSearchPages() throws Exception {
    client.close();
    client = timeoutClient(10, Duration.ofMillis(500));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withFixedDelay(220)
                    .withBody("{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"one\"}")));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .withRequestBody(equalToJson("{\"nextPageToken\":\"one\"}", true, true))
            .willReturn(
                aResponse()
                    .withFixedDelay(220)
                    .withBody("{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"two\"}")));
    server.stubFor(
        post(urlEqualTo("/rest/api/3/search/jql"))
            .withRequestBody(equalToJson("{\"nextPageToken\":\"two\"}", true, true))
            .willReturn(
                aResponse().withFixedDelay(220).withBody("{\"issues\":[],\"isLast\":true}")));
    long start = System.nanoTime();
    assertTrue(
        assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true))
            .outcome()
            .retryable());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @Test
  void slowErrorBodyKeepsKnownPermanentHttpStatus() throws Exception {
    client.close();
    client = timeoutClient(10, Duration.ofSeconds(2));
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withChunkedDribbleDelay(10, 1200)
                    .withBody("Access denied with a deliberately slow body")));
    var failure = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertEquals(403, failure.outcome().httpStatus());
    assertFalse(failure.outcome().retryable());
    assertFalse(failure.outcome().uncertain());
  }
}
