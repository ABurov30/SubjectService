package subjectservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import subjectservice.dto.CreateRequest;
import subjectservice.dto.RetryResponse;
import subjectservice.dto.StatusRequest;
import subjectservice.dto.SubjectView;
import subjectservice.service.SubjectService;

@RestController
public class SubjectController {
  private final SubjectService service;

  public SubjectController(SubjectService service) {
    this.service = service;
  }

  @Operation(
      summary = "Создать Subject",
      description = "Создаёт Subject в статусе CREATED без обращения к Jira.")
  @ApiResponse(responseCode = "201", description = "Subject создан")
  @PostMapping("/subjects")
  public ResponseEntity<SubjectView> create(@Valid @RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.name()));
  }

  @Operation(
      summary = "Изменить статус Subject",
      description =
          "REVIEW атомарно регистрирует операцию outbox. Успех подтверждает локальный коммит, а не создание задачи Jira.")
  @PatchMapping("/subjects/status")
  public SubjectView change(@Valid @RequestBody StatusRequest request) {
    return service.change(request.subjectId(), request.status());
  }

  @Operation(
      summary = "Повторить создание задачи Jira",
      description =
          "Возвращает только FAILED в очередь после устранения причины ошибки. Сохраняет исходную операцию и метку Jira.")
  @PostMapping("/subjects/{subjectId}/jira/retry")
  public RetryResponse retryJira(@PathVariable UUID subjectId) {
    return new RetryResponse(service.retryJira(subjectId));
  }
}
