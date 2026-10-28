package dev.thanhvu.maintenance;

import static dev.thanhvu.maintenance.Models.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;

class FilePersistenceTests {
  @TempDir Path directory;

  @Test
  void committedBookingRemainsWritableAfterDatabaseRestart() {
    String url =
        "--spring.datasource.url=jdbc:h2:file:"
            + directory.resolve("restart").toAbsolutePath().toString().replace('\\', '/')
            + ";DB_CLOSE_ON_EXIT=FALSE";
    long id;
    try (var app = new SpringApplicationBuilder(Application.class).run(url, "--server.port=0")) {
      var service = app.getBean(SchedulerService.class);
      id =
          service
              .book(
                  new Booking(
                      1L,
                      "technician",
                      "Persistence check",
                      Instant.now().plusSeconds(3600),
                      60,
                      1,
                      7),
                  UUID.randomUUID().toString(),
                  "planner")
              .getFirst()
              .id();
    }
    try (var app = new SpringApplicationBuilder(Application.class).run(url, "--server.port=0")) {
      var service = app.getBean(SchedulerService.class);
      assertThat(service.order(id).status()).isEqualTo("SCHEDULED");
      assertThat(
              service
                  .change(id, "cancel", new Change(0, "Restart verified"), "planner", true)
                  .status())
          .isEqualTo("CANCELLED");
      assertThat(service.history(id)).hasSize(2);
    }
  }
}
