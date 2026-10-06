package subjectservice.integration.jira;

import subjectservice.outbox.dto.OutboxOutcome;

public class JiraFailure extends RuntimeException {
  private final OutboxOutcome outcome;

  public JiraFailure(OutboxOutcome outcome, Throwable cause) {
    super(outcome.message(), cause);
    this.outcome = outcome;
  }

  public OutboxOutcome outcome() {
    return outcome;
  }
}
