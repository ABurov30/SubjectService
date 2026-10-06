package subjectservice.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.OptimisticLockException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import subjectservice.exception.SubjectNotFoundException;
import subjectservice.exception.SubjectValidationException;
import subjectservice.service.SubjectService;

class RestApiTest {
  SubjectService service;
  MockMvc mvc;
  UUID id = UUID.randomUUID();

  @BeforeEach
  void setup() {
    service = mock(SubjectService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SubjectController(service))
            .setControllerAdvice(new RestErrors())
            .build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}", "not-json"})
  void invalidCreateRequestNeverCallsService(String body) throws Exception {
    mvc.perform(post("/subjects").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.path").value("/subjects"));
    verifyNoInteractions(service);
  }

  @Test
  void tooLongNameNeverCallsService() throws Exception {
    mvc.perform(
            post("/subjects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "a".repeat(256) + "\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  String request() {
    return "{\"subjectId\":\"" + id + "\",\"status\":\"REVIEW\"}";
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"subjectId\":\"bad\",\"status\":\"REVIEW\"}",
        "{\"status\":\"REVIEW\"}",
        "{\"subjectId\":\"550e8400-e29b-41d4-a716-446655440000\"}",
        "{\"subjectId\":\"550e8400-e29b-41d4-a716-446655440000\",\"status\":\"INVALID\"}",
        "not-json"
      })
  void invalidInput(String body) throws Exception {
    mvc.perform(patch("/subjects/status").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.path").value("/subjects/status"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void mapsMissingConflictAndInternalErrors() throws Exception {
    for (RuntimeException failure :
        new RuntimeException[] {
          new SubjectNotFoundException(id),
          new OptimisticLockException("version mismatch"),
          new OptimisticLockingFailureException("version mismatch"),
          new IllegalStateException("password=secret SQL details"),
          new SubjectValidationException("invalid")
        }) {
      doThrow(failure).when(service).change(any(), any());
      int expected =
          failure instanceof SubjectNotFoundException
              ? 404
              : failure instanceof SubjectValidationException
                  ? 400
                  : failure instanceof IllegalStateException ? 500 : 409;
      String response =
          mvc.perform(
                  patch("/subjects/status")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(request())
                      .header("X-Correlation-Id", "test-correlation"))
              .andExpect(status().is(expected))
              .andExpect(jsonPath("$.correlationId").value("test-correlation"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(response.contains("password="));
      assertFalse(response.contains("SQL hidden"));
      assertFalse(response.contains("SQL details"));
    }
  }
}
