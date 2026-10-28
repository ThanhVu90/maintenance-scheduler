package dev.thanhvu.maintenance;

import static dev.thanhvu.maintenance.Models.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchedulerService {
  private final JdbcTemplate db;
  private final Clock clock;
  private final ObjectMapper json;

  public SchedulerService(JdbcTemplate db, Clock clock, ObjectMapper json) {
    this.db = db;
    this.clock = clock;
    this.json = json;
  }

  private void lock() {
    db.queryForObject("SELECT id FROM schedule_lock WHERE id=1 FOR UPDATE", Integer.class);
  }

  private OffsetDateTime utc(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private final String select =
      "SELECT w.*,a.code,a.name FROM work_order w JOIN asset a ON a.id=w.asset_id ";

  private RowMapper<WorkOrder> mapper() {
    return (rs, n) -> {
      Instant start = rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
          end = rs.getObject("ends_at", OffsetDateTime.class).toInstant();
      String status = rs.getString("status");
      return new WorkOrder(
          rs.getLong("id"),
          rs.getLong("asset_id"),
          rs.getString("code"),
          rs.getString("name"),
          rs.getString("technician"),
          rs.getString("title"),
          start,
          end,
          status,
          rs.getLong("version"),
          end.isBefore(clock.instant()) && !Set.of("COMPLETED", "CANCELLED").contains(status));
    };
  }

  public List<Asset> assets() {
    return db.query(
        "SELECT * FROM asset ORDER BY code",
        (rs, n) ->
            new Asset(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("location")));
  }

  public List<Map<String, Object>> technicians() {
    return db.queryForList("SELECT username,display_name FROM technician ORDER BY username");
  }

  public List<WorkOrder> orders(String status, int page) {
    if (page < 0 || page > 10000) throw new DomainError(400, "Invalid page");
    if (status != null
        && !Set.of("SCHEDULED", "IN_PROGRESS", "COMPLETED", "CANCELLED").contains(status))
      throw new DomainError(400, "Invalid status filter");
    return status == null
        ? db.query(select + "ORDER BY w.starts_at,w.id LIMIT 100 OFFSET ?", mapper(), page * 100)
        : db.query(
            select + "WHERE w.status=? ORDER BY w.starts_at,w.id LIMIT 100 OFFSET ?",
            mapper(),
            status,
            page * 100);
  }

  public WorkOrder order(long id) {
    return db.query(select + "WHERE w.id=?", mapper(), id).stream()
        .findFirst()
        .orElseThrow(() -> new DomainError(404, "Work order not found"));
  }

  @Transactional
  public Asset addAsset(NewAsset input) {
    lock();
    if (db.queryForObject("SELECT count(*) FROM asset WHERE code=?", Integer.class, input.code())
        > 0) throw new DomainError(409, "Asset code already exists");
    db.update(
        "INSERT INTO asset(code,name,location) VALUES (?,?,?)",
        input.code(),
        input.name().strip(),
        input.location().strip());
    return assets().stream().filter(a -> a.code().equals(input.code())).findFirst().orElseThrow();
  }

  private String hash(Booking booking) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsString(booking).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to fingerprint booking", e);
    }
  }

  @Transactional
  public List<WorkOrder> book(Booking input, String key, String actor) {
    try {
      if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new DomainError(400, "Idempotency-Key must be a lowercase UUID");
    }
    lock();
    String fingerprint = hash(input);
    var existing =
        db.queryForList("SELECT payload_hash,actor FROM booking_batch WHERE request_key=?", key);
    if (!existing.isEmpty()) {
      if (!fingerprint.equals(existing.getFirst().get("PAYLOAD_HASH"))
          || !actor.equals(existing.getFirst().get("ACTOR")))
        throw new DomainError(409, "Idempotency key already used for another request");
      return db.query(select + "WHERE w.batch_key=? ORDER BY w.occurrence", mapper(), key);
    }
    Instant now = clock.instant();
    if (input.startsAt().isBefore(now))
      throw new DomainError(400, "Start time must be in the future");
    Instant last =
        input
            .startsAt()
            .plus((long) (input.occurrences() - 1) * input.intervalDays(), ChronoUnit.DAYS);
    if (last.isAfter(now.plus(366, ChronoUnit.DAYS)))
      throw new DomainError(400, "All occurrences must start within the next 366 days");
    if (db.queryForObject("SELECT count(*) FROM asset WHERE id=?", Integer.class, input.assetId())
        == 0) throw new DomainError(404, "Asset not found");
    if (db.queryForObject(
            "SELECT count(*) FROM technician WHERE username=?", Integer.class, input.technician())
        == 0) throw new DomainError(400, "Unknown technician");
    db.update("INSERT INTO booking_batch VALUES (?,?,?)", key, fingerprint, actor);
    for (int i = 0; i < input.occurrences(); i++) {
      Instant start = input.startsAt().plus((long) i * input.intervalDays(), ChronoUnit.DAYS),
          end = start.plus(input.durationMinutes(), ChronoUnit.MINUTES);
      int conflicts =
          db.queryForObject(
              "SELECT count(*) FROM work_order WHERE status IN ('SCHEDULED','IN_PROGRESS') AND (asset_id=? OR technician=?) AND starts_at<? AND ends_at>?",
              Integer.class,
              input.assetId(),
              input.technician(),
              utc(end),
              utc(start));
      if (conflicts > 0)
        throw new DomainError(
            409,
            "Occurrence "
                + (i + 1)
                + " overlaps an active asset or technician reservation; no occurrences were saved");
      db.update(
          "INSERT INTO work_order(asset_id,technician,title,starts_at,ends_at,status,batch_key,occurrence) VALUES (?,?,?,?,?,'SCHEDULED',?,?)",
          input.assetId(),
          input.technician(),
          input.title().strip(),
          utc(start),
          utc(end),
          key,
          i + 1);
      Long id =
          db.queryForObject(
              "SELECT id FROM work_order WHERE batch_key=? AND occurrence=?",
              Long.class,
              key,
              i + 1);
      audit(id, actor, "SCHEDULED", "Occurrence " + (i + 1) + " of " + input.occurrences());
    }
    return db.query(select + "WHERE w.batch_key=? ORDER BY w.occurrence", mapper(), key);
  }

  @Transactional
  public WorkOrder change(long id, String action, Change input, String actor, boolean planner) {
    lock();
    WorkOrder current = order(id);
    if (current.version() != input.expectedVersion())
      throw new DomainError(409, "This work order changed; refresh before retrying");
    String target;
    if (action.equals("cancel")) {
      if (!planner) throw new DomainError(403, "Only planners can cancel work");
      if (!current.status().equals("SCHEDULED"))
        throw new DomainError(409, "Only scheduled work can be cancelled");
      target = "CANCELLED";
    } else {
      if (!current.technician().equals(actor))
        throw new DomainError(403, "Only the assigned technician can update this work");
      target =
          switch (action) {
            case "start" -> "IN_PROGRESS";
            case "complete" -> "COMPLETED";
            default -> throw new DomainError(400, "Unknown transition");
          };
      String expected = action.equals("start") ? "SCHEDULED" : "IN_PROGRESS";
      if (!current.status().equals(expected))
        throw new DomainError(409, "Invalid work-order transition");
      if (action.equals("start") && current.startsAt().isAfter(clock.instant()))
        throw new DomainError(409, "Work cannot start before its scheduled time");
    }
    db.update("UPDATE work_order SET status=?,version=version+1 WHERE id=?", target, id);
    audit(id, actor, target, input.note().strip());
    return order(id);
  }

  private void audit(long id, String actor, String action, String note) {
    db.update(
        "INSERT INTO audit_event(work_id,actor,action,note,created_at) VALUES (?,?,?,?,?)",
        id,
        actor,
        action,
        note,
        utc(clock.instant()));
  }

  public List<Audit> history(long id) {
    order(id);
    return db.query(
        "SELECT * FROM audit_event WHERE work_id=? ORDER BY id DESC LIMIT 100",
        (rs, n) ->
            new Audit(
                rs.getLong("id"),
                id,
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()),
        id);
  }
}
