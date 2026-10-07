package subjectservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRequest(
    @NotBlank @Size(max = 255) @Schema(example = "Example", description = "Имя Subject")
        String name) {}
