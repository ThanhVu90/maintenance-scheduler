package dev.thanhvu.maintenance;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseReadiness implements ApplicationRunner {
  private final DataSource source;

  public DatabaseReadiness(DataSource source) {
    this.source = source;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    try (var connection = source.getConnection()) {
      if (connection.isReadOnly() || connection.getMetaData().isReadOnly()) {
        throw new IllegalStateException(
            "Database is read-only. Grant this process write access to the database directory before starting the application.");
      }
    }
  }
}
