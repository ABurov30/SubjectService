package subjectservice.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import subjectservice.entity.Subject;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {}
