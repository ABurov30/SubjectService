package subjectservice.dto;

import java.util.UUID;
import subjectservice.enums.SubjectStatus;

public record SubjectView(UUID id, String name, SubjectStatus status, Long version) {}
