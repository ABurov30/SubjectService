package subjectservice.exception;

import java.util.UUID;

public class SubjectNotFoundException extends RuntimeException {
  public SubjectNotFoundException(UUID id) {
    super("Subject not found: " + id);
  }
}
