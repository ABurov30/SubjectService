package subjectservice.enums;

import org.springframework.http.HttpStatus;

public enum JiraHttpStatus {
  TOO_MANY_REQUESTS,
  SERVER_ERROR,
  OTHER;

  public static JiraHttpStatus from(int status) {
    if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
      return TOO_MANY_REQUESTS;
    }
    if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
      return SERVER_ERROR;
    }
    return OTHER;
  }

  public boolean isRetryable() {
    return this == TOO_MANY_REQUESTS || this == SERVER_ERROR;
  }

  public boolean isCreationUncertain() {
    return this == SERVER_ERROR;
  }
}
