package dev.thanhvu.maintenance;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class Models {
  private Models() {}

  public record Booking(
      @NotNull @Positive Long assetId,
      @NotBlank @Size(max = 40) String technician,
      @NotBlank @Size(max = 140) String title,
      @NotNull Instant startsAt,
      @Min(15) @Max(480) int durationMinutes,
      @Min(1) @Max(12) int occurrences,
      @Min(1) @Max(365) int intervalDays) {}

  public record Change(@Min(0) long expectedVersion, @NotBlank @Size(max = 1000) String note) {}

  public record NewAsset(
      @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,40}") String code,
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 120) String location) {}

  public record Asset(long id, String code, String name, String location) {}

  public record WorkOrder(
      long id,
      long assetId,
      String assetCode,
      String assetName,
      String technician,
      String title,
      Instant startsAt,
      Instant endsAt,
      String status,
      long version,
      boolean overdue) {}

  public record Audit(
      long id, long workId, String actor, String action, String note, Instant createdAt) {}
}
