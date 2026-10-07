package subjectservice.controller;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import subjectservice.dto.ApiError;
import subjectservice.enums.ApiErrorCode;
import subjectservice.exception.SubjectNotFoundException;
import subjectservice.exception.SubjectValidationException;

@RestControllerAdvice
public class RestErrors {
  private static final Logger log = LoggerFactory.getLogger(RestErrors.class);

  @ExceptionHandler({
    SubjectValidationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiError> bad(Exception ex, HttpServletRequest request) {
    return error(
        400,
        ApiErrorCode.INVALID_REQUEST,
        "Invalid request: check required fields, name length, UUID and status",
        request);
  }

  @ExceptionHandler(SubjectNotFoundException.class)
  public ResponseEntity<ApiError> missing(SubjectNotFoundException ex, HttpServletRequest request) {
    return error(404, ApiErrorCode.SUBJECT_NOT_FOUND, ex.getMessage(), request);
  }

  @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class})
  public ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
    return error(409, ApiErrorCode.CONFLICT, "Concurrent change", request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
    log.error("Request failed path={}", request.getRequestURI(), ex);
    return error(500, ApiErrorCode.INTERNAL_ERROR, "Internal server error", request);
  }

  private ResponseEntity<ApiError> error(
      int status, ApiErrorCode code, String message, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                status,
                code,
                message,
                request.getRequestURI(),
                Instant.now(),
                request.getHeader("X-Correlation-Id")));
  }
}
