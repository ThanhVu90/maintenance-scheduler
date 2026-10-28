package dev.thanhvu.maintenance;

import static dev.thanhvu.maintenance.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:scheduler-tests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
    })
@AutoConfigureMockMvc
class SchedulerTests {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    final AtomicReference<Instant> value = new AtomicReference<>(NOW);

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return value.get();
    }
  }

  @Autowired SchedulerService service;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired MutableClock clock;

  @BeforeEach
  void reset() {
    db.update("DELETE FROM audit_event");
    db.update("DELETE FROM work_order");
    db.update("DELETE FROM booking_batch");
    clock.value.set(NOW);
  }

  Booking booking(long asset, Instant time, int count) {
    return new Booking(asset, "technician", "Inspect equipment", time, 60, count, 7);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  WorkOrder create() {
    return service.book(booking(1, NOW.plusSeconds(3600), 1), key(), "planner").getFirst();
  }

  @Test
  void recurrenceHasExactUtcSpacingAndAudit() {
    var orders = service.book(booking(1, NOW.plusSeconds(3600), 3), key(), "planner");
    assertThat(orders).hasSize(3);
    assertThat(orders.get(2).startsAt()).isEqualTo(NOW.plusSeconds(3600 + 14 * 86400));
    assertThat(service.history(orders.getFirst().id())).hasSize(1);
  }

  @Test
  void sameRequestKeyIsIdempotent() {
    String key = key();
    var request = booking(1, NOW.plusSeconds(3600), 3);
    assertThat(service.book(request, key, "planner"))
        .isEqualTo(service.book(request, key, "planner"));
    assertThat(service.orders(null, 0)).hasSize(3);
    assertThat(db.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(3);
  }

  @Test
  void reusedKeyWithDifferentPayloadRejected() {
    String key = key();
    service.book(booking(1, NOW.plusSeconds(3600), 1), key, "planner");
    assertThatThrownBy(() -> service.book(booking(2, NOW.plusSeconds(7200), 1), key, "planner"))
        .isInstanceOf(DomainError.class)
        .hasMessageContaining("Idempotency");
  }

  @Test
  void adjacentWindowsAllowed() {
    create();
    service.book(booking(1, NOW.plusSeconds(7200), 1), key(), "planner");
    assertThat(service.orders(null, 0)).hasSize(2);
  }

  @Test
  void overlapOnSameTechnicianDifferentAssetRejected() {
    create();
    assertThatThrownBy(() -> service.book(booking(2, NOW.plusSeconds(5400), 1), key(), "planner"))
        .isInstanceOf(DomainError.class);
  }

  @Test
  void sameAssetDifferentTechnicianRejected() {
    db.update("MERGE INTO technician KEY(username) VALUES ('other','Other Tech')");
    create();
    var request = new Booking(1L, "other", "Inspect", NOW.plusSeconds(5400), 60, 1, 7);
    assertThatThrownBy(() -> service.book(request, key(), "planner"))
        .isInstanceOf(DomainError.class);
  }

  @Test
  void laterConflictRollsBackWholeRecurringBatch() {
    service.book(booking(1, NOW.plusSeconds(3600 + 7 * 86400), 1), key(), "planner");
    assertThatThrownBy(() -> service.book(booking(1, NOW.plusSeconds(3600), 3), key(), "planner"))
        .hasMessageContaining("Occurrence 2");
    assertThat(service.orders(null, 0)).hasSize(1);
    assertThat(db.queryForObject("SELECT count(*) FROM booking_batch", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(1);
  }

  @Test
  void concurrentOverlappingBookingsHaveOneWinner() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> attempt =
          () -> {
            start.await();
            try {
              service.book(booking(1, NOW.plusSeconds(3600), 1), key(), "planner");
              return true;
            } catch (DomainError e) {
              assertThat(e.status).isEqualTo(409);
              return false;
            }
          };
      var a = pool.submit(attempt);
      var b = pool.submit(attempt);
      start.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(service.orders(null, 0)).hasSize(1);
  }

  @Test
  void concurrentSameKeyReturnsOneBatch() throws Exception {
    String key = key();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Long> attempt =
          () -> {
            start.await();
            return service
                .book(booking(1, NOW.plusSeconds(3600), 1), key, "planner")
                .getFirst()
                .id();
          };
      var a = pool.submit(attempt);
      var b = pool.submit(attempt);
      start.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
    }
    assertThat(service.orders(null, 0)).hasSize(1);
  }

  @Test
  void lifecycleAndOptimisticVersion() {
    var work = create();
    clock.value.set(NOW.plusSeconds(3600));
    var started =
        service.change(
            work.id(), "start", new Change(0, "Inspection started"), "technician", false);
    assertThat(started.version()).isEqualTo(1);
    assertThatThrownBy(
            () -> service.change(work.id(), "complete", new Change(0, "Done"), "technician", false))
        .hasMessageContaining("changed");
    var done =
        service.change(
            work.id(), "complete", new Change(1, "Voltage checked; normal"), "technician", false);
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(service.history(work.id()))
        .extracting(Audit::action)
        .containsExactly("COMPLETED", "IN_PROGRESS", "SCHEDULED");
  }

  @Test
  void cannotCompleteBeforeStarting() {
    var work = create();
    assertThatThrownBy(
            () -> service.change(work.id(), "complete", new Change(0, "Done"), "technician", false))
        .hasMessageContaining("transition");
  }

  @Test
  void cannotStartEarly() {
    var work = create();
    assertThatThrownBy(
            () -> service.change(work.id(), "start", new Change(0, "Begin"), "technician", false))
        .hasMessageContaining("before");
  }

  @Test
  void onlyAssignedTechnicianCanStart() {
    var work = create();
    assertThatThrownBy(
            () -> service.change(work.id(), "start", new Change(0, "Begin"), "other", false))
        .hasMessageContaining("assigned");
  }

  @Test
  void cancelReleasesWindow() {
    var work = create();
    service.change(work.id(), "cancel", new Change(0, "Rescheduled"), "planner", true);
    create();
    assertThat(service.orders(null, 0)).hasSize(2);
  }

  @Test
  void technicianCannotCancel() {
    var work = create();
    assertThatThrownBy(
            () -> service.change(work.id(), "cancel", new Change(0, "Cancel"), "technician", false))
        .hasMessageContaining("planners");
  }

  @Test
  void overdueIsDerivedFromClockAndClearsOnCancel() {
    var work = create();
    clock.value.set(NOW.plusSeconds(7201));
    assertThat(service.order(work.id()).overdue()).isTrue();
    service.change(work.id(), "cancel", new Change(0, "No longer needed"), "planner", true);
    assertThat(service.order(work.id()).overdue()).isFalse();
  }

  @Test
  void rejectPastAndBeyondHorizon() {
    assertThatThrownBy(() -> service.book(booking(1, NOW.minusSeconds(1), 1), key(), "planner"))
        .hasMessageContaining("future");
    assertThatThrownBy(
            () -> service.book(booking(1, NOW.plusSeconds(367 * 86400L), 1), key(), "planner"))
        .hasMessageContaining("366");
  }

  @Test
  void unknownAssetRejected() {
    assertThatThrownBy(() -> service.book(booking(999, NOW.plusSeconds(3600), 1), key(), "planner"))
        .hasMessageContaining("Asset not found");
  }

  @Test
  void anonymousCannotReadPrivateData() throws Exception {
    mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
  }

  @Test
  void csrfIsRequiredForBooking() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(booking(1, NOW.plusSeconds(3600), 1))))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewerCannotCreate() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(booking(1, NOW.plusSeconds(3600), 1))))
        .andExpect(status().isForbidden());
  }

  @Test
  void plannerCanBookWithCsrf() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(booking(1, NOW.plusSeconds(3600), 1))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("SCHEDULED"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 14, 481})
  void invalidDurationRejected(int minutes) throws Exception {
    var request = new Booking(1L, "technician", "Inspect", NOW.plusSeconds(3600), minutes, 1, 7);
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingIdempotencyKeyRejected() throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(booking(1, NOW.plusSeconds(3600), 1))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void whitespaceTitleRejected() throws Exception {
    var request = new Booking(1L, "technician", "  ", NOW.plusSeconds(3600), 60, 1, 7);
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownJsonFieldsRejected() throws Exception {
    mvc.perform(
            post("/api/assets")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"code\":\"TEST-1\",\"name\":\"test\",\"location\":\"test\",\"admin\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void loginRequiresCsrfAndAuthenticates() throws Exception {
    mvc.perform(
            post("/api/login").param("username", "planner").param("password", "planner-demo-only"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/login")
                .with(csrf())
                .param("username", "planner")
                .param("password", "planner-demo-only"))
        .andExpect(status().isNoContent());
  }

  @Test
  void failedLoginRejected() throws Exception {
    mvc.perform(
            post("/api/login").with(csrf()).param("username", "planner").param("password", "wrong"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidStatusAndPageRejected() throws Exception {
    mvc.perform(get("/api/orders?status=BOGUS").with(user("viewer")))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/orders?page=-1").with(user("viewer"))).andExpect(status().isBadRequest());
  }

  @Test
  void missingOrderReturns404() throws Exception {
    mvc.perform(get("/api/orders/99999/history").with(user("viewer")))
        .andExpect(status().isNotFound());
  }

  @Test
  void retryAfterStartTimeStillReturnsOriginalBatch() {
    String key = key();
    var request = booking(1, NOW.plusSeconds(3600), 1);
    var original = service.book(request, key, "planner");
    clock.value.set(NOW.plusSeconds(7200));
    assertThat(service.book(request, key, "planner").getFirst().id())
        .isEqualTo(original.getFirst().id());
  }

  @Test
  void utcOffsetsResolveToSameReservationInstant() {
    var time = OffsetDateTime.parse("2030-01-01T08:00:00+07:00").toInstant();
    var result = service.book(booking(1, time, 1), key(), "planner");
    assertThat(result.getFirst().startsAt()).isEqualTo(NOW.plusSeconds(3600));
    assertThatThrownBy(
            () ->
                service.book(
                    booking(1, Instant.parse("2030-01-01T01:00:00Z"), 1), key(), "planner"))
        .isInstanceOf(DomainError.class);
  }

  @Test
  void cancellationCannotInterruptInProgressWork() {
    var work = create();
    clock.value.set(NOW.plusSeconds(3600));
    service.change(work.id(), "start", new Change(0, "Start"), "technician", false);
    assertThatThrownBy(
            () -> service.change(work.id(), "cancel", new Change(1, "Cancel"), "planner", true))
        .hasMessageContaining("scheduled");
  }

  @Test
  void concurrentTransitionsRejectStaleVersion() throws Exception {
    var work = create();
    clock.value.set(NOW.plusSeconds(3600));
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> attempt =
          () -> {
            gate.await();
            try {
              service.change(work.id(), "start", new Change(0, "Start"), "technician", false);
              return true;
            } catch (DomainError e) {
              assertThat(e.status).isEqualTo(409);
              return false;
            }
          };
      var a = pool.submit(attempt);
      var b = pool.submit(attempt);
      gate.countDown();
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(service.history(work.id())).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 13})
  void invalidRecurrenceCountRejected(int count) throws Exception {
    mvc.perform(
            post("/api/orders")
                .with(user("planner").roles("PLANNER"))
                .with(csrf())
                .header("Idempotency-Key", key())
                .contentType("application/json")
                .content(json.writeValueAsString(booking(1, NOW.plusSeconds(3600), count))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void duplicateAssetCodeRejected() {
    assertThatThrownBy(() -> service.addAsset(new NewAsset("NET-001", "Duplicate", "Lab")))
        .hasMessageContaining("already exists");
  }

  @Test
  void unknownTechnicianRejected() {
    var request = new Booking(1L, "missing", "Inspect", NOW.plusSeconds(3600), 60, 1, 7);
    assertThatThrownBy(() -> service.book(request, key(), "planner"))
        .hasMessageContaining("Unknown technician");
  }

  @Test
  void successfulLoginSessionCanReadThenLogout() throws Exception {
    var result =
        mvc.perform(
                post("/api/login")
                    .with(csrf())
                    .param("username", "viewer")
                    .param("password", "viewer-demo-only"))
            .andExpect(status().isNoContent())
            .andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
    mvc.perform(get("/api/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("viewer"));
    mvc.perform(post("/api/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }
}
