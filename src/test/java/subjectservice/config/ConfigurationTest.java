package subjectservice.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import subjectservice.enums.SubjectStatus;
import subjectservice.exception.SubjectNotFoundException;
import subjectservice.exception.SubjectValidationException;
import subjectservice.integration.jira.JiraProperties;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.repository.SubjectOutboxRepository;
import subjectservice.repository.SubjectRepository;
import subjectservice.service.SubjectService;

class ConfigurationTest {
  JiraProperties properties(URI url, Duration call, Duration attempt, int pages) {
    return new JiraProperties(
        url,
        "email",
        "secret",
        "TEST",
        "Task",
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        call,
        attempt,
        pages);
  }

  @Test
  void rejectsBadJiraAndLeaseSettings() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            properties(
                URI.create("file:/tmp/jira"), Duration.ofSeconds(1), Duration.ofSeconds(2), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties(URI.create("http://localhost"), Duration.ZERO, Duration.ofSeconds(2), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            properties(
                URI.create("http://localhost"), Duration.ofSeconds(3), Duration.ofSeconds(2), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            properties(
                URI.create("http://localhost"), Duration.ofSeconds(1), Duration.ofSeconds(2), 0));
    var p =
        properties(
            URI.create("http://localhost"), Duration.ofSeconds(1), Duration.ofSeconds(60), 1);
    assertFalse(p.toString().contains("secret"));
    var config = new OutboxConfiguration();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config.outboxStore(
                mock(EntityManager.class),
                mock(PlatformTransactionManager.class),
                OutboxProperties.defaults(),
                p));
  }

  @Test
  void serviceValidationRejectsInvalidRequests() {
    SubjectRepository subjects = mock(SubjectRepository.class);
    SubjectOutboxRepository outbox = mock(SubjectOutboxRepository.class);
    ObjectMapper json = mock(ObjectMapper.class);
    var service = new SubjectService(subjects, outbox, json);
    UUID id = UUID.randomUUID();
    assertThrows(
        SubjectValidationException.class, () -> service.change(null, SubjectStatus.REVIEW));
    assertThrows(SubjectValidationException.class, () -> service.change(id, null));
    assertThrows(SubjectNotFoundException.class, () -> service.change(id, SubjectStatus.REVIEW));
  }
}
