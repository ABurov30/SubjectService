package subjectservice.integration.jira;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import subjectservice.dto.Issue;

class JiraClientTest {
  WireMockServer server;
  JiraClient client;
  final Instant now = Instant.parse("2026-10-06T00:00:00Z");
  final Map<String, Object> payload = Map.of("jiraLabel", "subject-123", "name", "Review Subject");

  @BeforeEach
  void start() {
    server = new WireMockServer(0);
    server.start();
    client =
        new JiraClient(
            new JiraProperties(
                URI.create(server.baseUrl()),
                "email",
                "secret",
                "TEST",
                "Task",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                10),
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
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"100\",\"key\":\"TEST-100\"}")));
    assertEquals(new Issue("100", "TEST-100"), client.resolve(payload, () -> true));
    server.verify(
        postRequestedFor(urlEqualTo("/rest/api/3/issue"))
            .withRequestBody(
                equalToJson(
                    "{\"fields\":{\"project\":{\"key\":\"TEST\"},\"issuetype\":{\"name\":\"Task\"},\"summary\":\"Review Subject\",\"labels\":[\"subject-123\"]}}")));
    server.verify(
        postRequestedFor(urlEqualTo("/rest/api/3/search/jql"))
            .withRequestBody(
                matchingJsonPath(
                    "$.jql", equalTo("project = \"TEST\" AND labels = \"subject-123\""))));
  }

  @Test
  void reusesExistingIssue() {
    search("{\"issues\":[{\"id\":\"100\",\"key\":\"TEST-100\"}],\"isLast\":true}");
    assertEquals(new Issue("100", "TEST-100"), client.resolve(payload, () -> true));
    server.verify(0, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 429, 503})
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
  @ValueSource(ints = {400, 401, 429, 503})
  void classifiesCreationErrorsAndUncertainty(int status) {
    search("{\"issues\":[],\"isLast\":true}");
    server.stubFor(
        post(urlEqualTo("/rest/api/3/issue")).willReturn(aResponse().withStatus(status)));
    var ex = assertThrows(JiraFailure.class, () -> client.resolve(payload, () -> true));
    assertEquals(status, ex.outcome().httpStatus());
    assertEquals(status >= 500, ex.outcome().uncertain());
    assertEquals(status == 429 || status >= 500, ex.outcome().retryable());
    server.verify(1, postRequestedFor(urlEqualTo("/rest/api/3/issue")));
  }
}
