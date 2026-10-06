package subjectservice.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import subjectservice.entity.SubjectServiceOutbox;

public interface SubjectOutboxRepository extends JpaRepository<SubjectServiceOutbox, UUID> {
  Optional<SubjectServiceOutbox> findBySubjectId(UUID subjectId);

  long countBySubjectId(UUID subjectId);

  @Modifying
  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      value =
          """
          INSERT INTO subject_service_outbox
            (id, subject_id, aggregate_type, event_type, event_key, payload, status,
             attempt_count, retry_count, created_at)
          VALUES (:id, :subjectId, 'Subject', 'CREATE_JIRA_TASK', :eventKey,
                  CAST(:payload AS jsonb), 'PENDING', 0, 0, CURRENT_TIMESTAMP)
          ON CONFLICT (subject_id) DO NOTHING
          """,
      nativeQuery = true)
  int register(
      @Param("id") UUID id,
      @Param("subjectId") UUID subjectId,
      @Param("eventKey") String eventKey,
      @Param("payload") String payload);
}
