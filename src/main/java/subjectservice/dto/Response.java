package subjectservice.dto;

public record Response(int status, String body, String retryAfter) {}
