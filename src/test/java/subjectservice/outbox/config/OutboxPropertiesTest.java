package subjectservice.outbox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class OutboxPropertiesTest {
  @Test
  void bindingAndBackoff() {
    var p =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "outbox.max-retries",
                        "2",
                        "outbox.initial-delay",
                        "10ms",
                        "outbox.multiplier",
                        "3",
                        "outbox.max-delay",
                        "50ms")))
            .bind("outbox", Bindable.of(OutboxProperties.class))
            .get();
    assertEquals(3, p.maxAttempts());
    assertEquals(Duration.ofMillis(10), p.backoff(1));
    assertEquals(Duration.ofMillis(30), p.backoff(2));
    assertEquals(Duration.ofMillis(50), p.backoff(100));
    assertEquals(-1, OutboxProperties.defaults().maxAttempts());
    org.junit.jupiter.api.Assertions.assertTrue(
        OutboxProperties.defaults().canAttempt(Integer.MAX_VALUE));
    assertEquals(Duration.ofMinutes(5), OutboxProperties.defaults().backoff(Integer.MAX_VALUE));
  }

  @Test
  void rejectsInvalidConfiguration() {
    for (var setting :
        Map.of(
                "max-retries",
                "-2",
                "batch-size",
                "0",
                "lease",
                "0ms",
                "multiplier",
                "0.5",
                "max-delay",
                "1ms")
            .entrySet()) {
      assertThrows(
          RuntimeException.class,
          () ->
              new Binder(
                      new MapConfigurationPropertySource(
                          Map.of("outbox." + setting.getKey(), setting.getValue())))
                  .bind("outbox", Bindable.of(OutboxProperties.class)));
    }
  }
}
