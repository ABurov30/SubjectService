package subjectservice.integration.jira;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("jira")
public record JiraProperties(
    @DefaultValue("http://localhost:8081") URI url,
    @DefaultValue("") String email,
    @DefaultValue("") String token,
    @DefaultValue("TEST") String project,
    @DefaultValue("Task") String issueType,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("5s") Duration responseTimeout,
    @DefaultValue("10s") Duration callTimeout,
    @DefaultValue("25s") Duration attemptTimeout,
    @DefaultValue("100") int maxSearchPages) {
  public JiraProperties {
    if (url == null
        || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
        || url.getHost() == null
        || project == null
        || project.isBlank()
        || issueType == null
        || issueType.isBlank()
        || maxSearchPages < 1) {
      throw new IllegalArgumentException("Invalid Jira configuration");
    }
    for (Duration d :
        new Duration[] {connectTimeout, responseTimeout, callTimeout, attemptTimeout}) {
      if (d == null || d.toMillis() < 1) {
        throw new IllegalArgumentException("Jira timeouts must be positive");
      }
    }
    if (attemptTimeout.compareTo(callTimeout) < 0) {
      throw new IllegalArgumentException("Attempt timeout must cover a call");
    }
  }

  @Override
  public String toString() {
    return "JiraProperties[url=" + url + ", project=" + project + ", credentials=redacted]";
  }
}
