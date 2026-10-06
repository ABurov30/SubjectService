package subjectservice.outbox.dto;

import java.util.UUID;
import subjectservice.outbox.entity.OutboxEventEntity;

public record OutboxAttempt<E extends OutboxEventEntity>(UUID eventId, String token, E event) {}
