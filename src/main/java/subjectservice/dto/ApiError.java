package subjectservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import subjectservice.enums.ApiErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    int status,
    ApiErrorCode code,
    String message,
    String path,
    Instant timestamp,
    String correlationId) {}
