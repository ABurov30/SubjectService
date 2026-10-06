package subjectservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import subjectservice.outbox.entity.OutboxEventEntity;

@Entity
@Table(name = "subject_service_outbox")
@Getter
@Setter
public class SubjectServiceOutbox extends OutboxEventEntity {
  @Column(name = "jira_issue_id")
  private String jiraIssueId;

  @Column(name = "jira_issue_key")
  private String jiraIssueKey;
}
