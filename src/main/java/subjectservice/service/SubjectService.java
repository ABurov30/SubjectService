package subjectservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import subjectservice.dto.SubjectView;
import subjectservice.entity.Subject;
import subjectservice.enums.SubjectStatus;
import subjectservice.exception.SubjectNotFoundException;
import subjectservice.exception.SubjectValidationException;
import subjectservice.repository.SubjectOutboxRepository;
import subjectservice.repository.SubjectRepository;

@Service
public class SubjectService {
  private final SubjectRepository subjects;
  private final SubjectOutboxRepository outbox;
  private final ObjectMapper json;

  public SubjectService(
      SubjectRepository subjects, SubjectOutboxRepository outbox, ObjectMapper json) {
    this.subjects = subjects;
    this.outbox = outbox;
    this.json = json;
  }

  @Transactional
  public SubjectView create(String name) {
    if (name == null || name.isBlank() || name.length() > 255) {
      throw new SubjectValidationException("name must be non-blank and at most 255 characters");
    }
    Subject subject = new Subject();
    subject.setId(UUID.randomUUID());
    subject.setName(name);
    subject.setStatus(SubjectStatus.CREATED);
    subject = subjects.saveAndFlush(subject);
    return new SubjectView(
        subject.getId(), subject.getName(), subject.getStatus(), subject.getVersion());
  }

  @Transactional
  public SubjectView change(UUID id, SubjectStatus status) {
    if (id == null || status == null) {
      throw new SubjectValidationException("subjectId and status are required");
    }
    Subject subject = subjects.findById(id).orElseThrow(() -> new SubjectNotFoundException(id));
    if (subject.getStatus() != status) {
      subject.setStatus(status);
    }
    subjects.flush();
    if (status == SubjectStatus.REVIEW) {
      register(subject);
    }
    return new SubjectView(
        subject.getId(), subject.getName(), subject.getStatus(), subject.getVersion());
  }

  private void register(Subject s) {
    String payload;
    try {
      payload =
          json.writeValueAsString(
              Map.of(
                  "subjectId",
                  s.getId().toString(),
                  "name",
                  s.getName(),
                  "status",
                  "REVIEW",
                  "jiraLabel",
                  "subject-" + s.getId()));
    } catch (JsonProcessingException ex) {
      throw new SubjectValidationException("Cannot serialize Subject snapshot", ex);
    }
    outbox.register(UUID.randomUUID(), s.getId(), "jira:create:subject:" + s.getId(), payload);
  }
}
