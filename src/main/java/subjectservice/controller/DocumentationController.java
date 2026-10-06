package subjectservice.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Hidden
@Controller
public class DocumentationController {
  @GetMapping("/")
  public String documentation() {
    return "redirect:/swagger-ui.html";
  }
}
