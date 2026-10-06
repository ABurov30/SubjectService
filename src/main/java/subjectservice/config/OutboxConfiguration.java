package subjectservice.config;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import subjectservice.entity.SubjectServiceOutbox;
import subjectservice.integration.jira.JiraProperties;
import subjectservice.outbox.config.OutboxProperties;
import subjectservice.outbox.repository.JpaOutboxAttemptStore;
import subjectservice.outbox.repository.OutboxAttemptStore;
import subjectservice.outbox.service.OutboxDispatcher;

@Configuration
public class OutboxConfiguration {
  @Bean
  public String outboxPollingMillis(OutboxProperties p) {
    return Long.toString(p.polling().toMillis());
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public OutboxAttemptStore<SubjectServiceOutbox> outboxStore(
      EntityManager em,
      PlatformTransactionManager manager,
      OutboxProperties p,
      JiraProperties jira) {
    if (p.lease().compareTo(jira.attemptTimeout().plus(p.completionMargin())) <= 0) {
      throw new IllegalArgumentException(
          "Outbox lease must exceed Jira attempt timeout plus completion margin");
    }
    return new JpaOutboxAttemptStore<>(
        em, manager, SubjectServiceOutbox.class, "subject_service_outbox", p);
  }

  @Bean
  public OutboxDispatcher<SubjectServiceOutbox> dispatcher(
      OutboxAttemptStore<SubjectServiceOutbox> store,
      OutboxProperties p,
      PlatformTransactionManager manager,
      Clock clock) {
    return new OutboxDispatcher<>(store, p, manager, clock);
  }
}
