package dev.thanhvu.maintenance;

import static dev.thanhvu.maintenance.Models.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final SchedulerService service;

  public ApiController(SchedulerService service) {
    this.service = service;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "header", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication auth) {
    return Map.of(
        "username",
        auth.getName(),
        "roles",
        auth.getAuthorities().stream().map(Object::toString).toList());
  }

  @GetMapping("/assets")
  public List<Asset> assets() {
    return service.assets();
  }

  @GetMapping("/technicians")
  public List<Map<String, Object>> technicians() {
    return service.technicians();
  }

  @PostMapping("/assets")
  @PreAuthorize("hasRole('PLANNER')")
  public Asset add(@Valid @RequestBody NewAsset input) {
    return service.addAsset(input);
  }

  @GetMapping("/orders")
  public List<WorkOrder> orders(
      @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
    return service.orders(status, page);
  }

  @GetMapping("/orders/{id}/history")
  public List<Audit> history(@PathVariable long id) {
    return service.history(id);
  }

  @PostMapping("/orders")
  @PreAuthorize("hasRole('PLANNER')")
  public List<WorkOrder> book(
      @Valid @RequestBody Booking input,
      @RequestHeader("Idempotency-Key") String key,
      Authentication auth) {
    return service.book(input, key, auth.getName());
  }

  @PostMapping("/orders/{id}/{action}")
  @PreAuthorize("hasAnyRole('PLANNER','TECHNICIAN')")
  public WorkOrder change(
      @PathVariable long id,
      @PathVariable String action,
      @Valid @RequestBody Change input,
      Authentication auth) {
    return service.change(
        id,
        action,
        input,
        auth.getName(),
        auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLANNER")));
  }
}
