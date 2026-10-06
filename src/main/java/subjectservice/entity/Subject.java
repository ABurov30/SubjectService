package subjectservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import subjectservice.enums.SubjectStatus;

@Entity
@Table(name = "subjects")
@Getter
@Setter
public class Subject {
  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private SubjectStatus status;

  @Version
  @Column(nullable = false)
  private Long version;
}
