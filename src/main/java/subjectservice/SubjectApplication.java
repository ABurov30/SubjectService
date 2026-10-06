package subjectservice;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import subjectservice.entity.Subject;
import subjectservice.integration.jira.JiraProperties;
import subjectservice.outbox.config.OutboxProperties;

@OpenAPIDefinition(
    info =
        @Info(
            title = "Subject Service API",
            version = "1.0.0",
            description = "Создание Subject и отправка на рассмотрение."))
@SpringBootApplication
@EntityScan(basePackageClasses = Subject.class)
@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, JiraProperties.class})
public class SubjectApplication {
  public static void main(String[] args) {
    SpringApplication.run(SubjectApplication.class, args);
  }
}
