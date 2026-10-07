package subjectservice.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import subjectservice.enums.SubjectStatus;

public record StatusRequest(@NotNull UUID subjectId, @NotNull SubjectStatus status) {}
