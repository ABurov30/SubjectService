package subjectservice.outbox.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import subjectservice.dto.Issue;
import subjectservice.entity.SubjectServiceOutbox;
import subjectservice.integration.jira.JiraClient;
import subjectservice.integration.jira.JiraFailure;
import subjectservice.outbox.dto.OutboxAttempt;
import subjectservice.outbox.dto.OutboxOutcome;
import subjectservice.outbox.repository.OutboxAttemptStore;
import subjectservice.outbox.service.OutboxDispatcher;

@Component
@ConditionalOnProperty(
    name = "outbox.scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxWorker {
  private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
  private final OutboxDispatcher<SubjectServiceOutbox> dispatcher;
  private final OutboxAttemptStore<SubjectServiceOutbox> store;
  private final JiraClient jira;

  public OutboxWorker(
      OutboxDispatcher<SubjectServiceOutbox> dispatcher,
      OutboxAttemptStore<SubjectServiceOutbox> store,
      JiraClient jira) {
    this.dispatcher = dispatcher;
    this.store = store;
    this.jira = jira;
  }

  @Scheduled(fixedDelayString = "#{@outboxPollingMillis}")
  public void run() {
    dispatcher.dispatch(this::deliver);
  }

  public OutboxDispatcher.Delivery<SubjectServiceOutbox> deliver(
      OutboxAttempt<SubjectServiceOutbox> attempt) {
    SubjectServiceOutbox event = attempt.event();
    if (event.getJiraIssueId() != null && event.getJiraIssueKey() != null) {
      return new OutboxDispatcher.Delivery<>(OutboxOutcome.published(), e -> {});
    }
    try {
      Issue issue = jira.resolve(event.getPayload(), () -> store.renew(attempt));
      return new OutboxDispatcher.Delivery<>(
          OutboxOutcome.published(),
          e -> {
            e.setJiraIssueId(issue.id());
            e.setJiraIssueKey(issue.key());
          });
    } catch (JiraFailure ex) {
      log.warn("Jira attempt failed event={}", attempt.eventId(), ex);
      return new OutboxDispatcher.Delivery<>(ex.outcome(), e -> {});
    }
  }
}
