package subjectservice.exception;

public class SubjectValidationException extends RuntimeException {
  public SubjectValidationException(String message) {
    super(message);
  }

  public SubjectValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
