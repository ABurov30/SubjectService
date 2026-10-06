package subjectservice.integration.jira;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import subjectservice.outbox.dto.OutboxOutcome;

@Component
public class JiraClient {
  private final JiraProperties p;
  private final ObjectMapper json;
  private final Clock clock;
  private final CloseableHttpClient http;
  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "jira-http");
            t.setDaemon(true);
            return t;
          });

  public JiraClient(JiraProperties p, ObjectMapper json, Clock clock) {
    this.p = p;
    this.json = json;
    this.clock = clock;
    http =
        HttpClients.custom()
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(p.connectTimeout().toMillis()))
                    .setConnectionRequestTimeout(
                        Timeout.ofMilliseconds(p.connectTimeout().toMillis()))
                    .setResponseTimeout(Timeout.ofMilliseconds(p.responseTimeout().toMillis()))
                    .build())
            .build();
  }

  public record Issue(String id, String key) {}

  private record Response(int status, String body, String retryAfter) {}

  private static final class Attempt {
    final long deadline;
    volatile boolean called;
    Integer lastStatus;

    Attempt(long timeout) {
      deadline = System.nanoTime() + timeout;
    }
  }

  private static final class CallState {
    volatile boolean started;
    volatile Integer status;
    volatile String retryAfter;
  }

  public Issue resolve(Map<String, Object> payload, BooleanSupplier ownership) {
    Attempt a = new Attempt(p.attemptTimeout().toNanos());
    Map<String, Issue> found = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    String next = null;
    for (int page = 0; page < p.maxSearchPages(); page++) {
      Map<String, Object> query = new LinkedHashMap<>();
      query.put(
          "jql",
          "project = "
              + quote(p.project())
              + " AND labels = "
              + quote(payload.get("jiraLabel").toString()));
      query.put("maxResults", 100);
      query.put("fields", List.of("key"));
      if (next != null) {
        query.put("nextPageToken", next);
      }
      JsonNode response = request("/rest/api/3/search/jql", query, false, a, ownership);
      if (!response.path("issues").isArray() || !response.path("isLast").isBoolean()) {
        throw failure(
            FailureKind.PERMANENT,
            a.called,
            a.lastStatus,
            "Malformed Jira search response",
            null,
            null);
      }
      for (JsonNode node : response.path("issues")) {
        Issue issue = issue(node, false, a);
        found.put(issue.id(), issue);
      }
      if (found.size() > 1) {
        throw failure(
            FailureKind.PERMANENT,
            a.called,
            a.lastStatus,
            "Multiple Jira issues match the stable label; investigate duplicates",
            null,
            null);
      }
      if (response.path("isLast").asBoolean()) {
        if (!found.isEmpty()) {
          return found.values().iterator().next();
        }
        Map<String, Object> fields =
            Map.of(
                "project",
                Map.of("key", p.project()),
                "issuetype",
                Map.of("name", p.issueType()),
                "summary",
                payload.get("name"),
                "labels",
                List.of(payload.get("jiraLabel")));
        return issue(
            request("/rest/api/3/issue", Map.of("fields", fields), true, a, ownership), true, a);
      }
      next = response.path("nextPageToken").asText("");
      if (next.isBlank() || !seen.add(next)) {
        throw failure(
            FailureKind.PERMANENT,
            a.called,
            a.lastStatus,
            "Invalid Jira search pagination",
            null,
            null);
      }
    }
    throw failure(
        FailureKind.PERMANENT,
        a.called,
        a.lastStatus,
        "Jira search page limit exceeded; creation suppressed",
        null,
        null);
  }

  private Issue issue(JsonNode node, boolean creating, Attempt a) {
    String id = node.path("id").asText("");
    String key = node.path("key").asText("");
    if (id.isBlank() || key.isBlank()) {
      throw failure(
          creating ? FailureKind.UNCERTAIN : FailureKind.PERMANENT,
          a.called,
          a.lastStatus,
          "Jira response has no issue id/key; result may be unknown",
          null,
          null);
    }
    return new Issue(id, key);
  }

  private JsonNode request(
      String path, Object body, boolean creating, Attempt a, BooleanSupplier ownership) {
    if (!ownership.getAsBoolean()) {
      throw failure(
          FailureKind.RETRYABLE,
          a.called,
          null,
          "Lease ownership lost before HTTP request",
          null,
          null);
    }
    long remaining = Math.min(p.callTimeout().toNanos(), a.deadline - System.nanoTime());
    if (remaining <= 0) {
      throw failure(
          FailureKind.RETRYABLE,
          a.called,
          null,
          "Jira attempt deadline exceeded before request",
          null,
          null);
    }
    HttpPost request = new HttpPost(p.url().toString().replaceAll("/$", "") + path);
    request.setHeader("Accept", "application/json");
    request.setHeader(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((p.email() + ":" + p.token()).getBytes(StandardCharsets.UTF_8)));
    Future<Response> future = null;
    CallState call = new CallState();
    try {
      request.setEntity(
          new StringEntity(json.writeValueAsString(body), ContentType.APPLICATION_JSON));
      remaining = Math.min(p.callTimeout().toNanos(), a.deadline - System.nanoTime());
      if (remaining <= 0) {
        throw failure(
            FailureKind.RETRYABLE,
            a.called,
            null,
            "Jira attempt deadline exceeded before request",
            null,
            null);
      }
      future =
          executor.submit(
              () -> {
                call.started = true;
                a.called = true;
                return http.execute(
                    request,
                    response -> {
                      call.status = response.getCode();
                      call.retryAfter =
                          response.getFirstHeader("Retry-After") == null
                              ? null
                              : response.getFirstHeader("Retry-After").getValue();
                      boolean successful = call.status >= 200 && call.status < 300;
                      return new Response(
                          call.status,
                          successful && response.getEntity() != null
                              ? EntityUtils.toString(response.getEntity())
                              : "",
                          call.retryAfter);
                    });
              });
      Response response = future.get(remaining, TimeUnit.NANOSECONDS);
      a.lastStatus = response.status();
      if (response.status() < 200 || response.status() >= 300) {
        throw httpFailure(response.status(), response.retryAfter(), creating, null);
      }
      return json.readTree(response.body());
    } catch (JiraFailure ex) {
      throw ex;
    } catch (JsonProcessingException ex) {
      throw failure(
          creating ? FailureKind.UNCERTAIN : FailureKind.PERMANENT,
          a.called,
          a.lastStatus,
          "Malformed Jira JSON response",
          null,
          ex);
    } catch (Exception ex) {
      request.cancel();
      if (future != null) {
        future.cancel(true);
      }
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      if (call.status != null && (call.status < 200 || call.status >= 300)) {
        throw httpFailure(call.status, call.retryAfter, creating, ex);
      }
      boolean uncertain = creating && call.started;
      throw failure(
          uncertain ? FailureKind.UNCERTAIN : FailureKind.RETRYABLE,
          a.called,
          call.status,
          "Jira transport/response failure" + (uncertain ? "; creation result may be unknown" : ""),
          null,
          ex);
    }
  }

  private JiraFailure httpFailure(
      int status, String retryHeader, boolean creating, Throwable cause) {
    boolean retry = status == 429 || status >= 500;
    boolean uncertain = creating && status >= 500;
    FailureKind kind = FailureKind.PERMANENT;
    if (uncertain) {
      kind = FailureKind.UNCERTAIN;
    } else if (retry) {
      kind = FailureKind.RETRYABLE;
    }
    return failure(
        kind,
        true,
        status,
        "Jira HTTP " + status + (uncertain ? "; creation result may be unknown" : ""),
        retryAfter(retryHeader),
        cause);
  }

  static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  Instant retryAfter(String value) {
    if (value == null) {
      return null;
    }
    try {
      long seconds = Long.parseLong(value.trim());
      return seconds < 0 ? null : clock.instant().plusSeconds(seconds);
    } catch (RuntimeException ex) {
      try {
        return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      } catch (RuntimeException invalid) {
        return null;
      }
    }
  }

  private enum FailureKind {
    PERMANENT,
    RETRYABLE,
    UNCERTAIN
  }

  private JiraFailure failure(
      FailureKind kind,
      boolean called,
      Integer status,
      String message,
      Instant after,
      Throwable cause) {
    return new JiraFailure(
        OutboxOutcome.failure(
            kind != FailureKind.PERMANENT,
            called,
            kind != FailureKind.PERMANENT,
            kind == FailureKind.UNCERTAIN,
            status,
            message,
            after),
        cause);
  }

  @PreDestroy
  public void close() throws java.io.IOException {
    executor.shutdownNow();
    http.close();
  }
}
