package dev.thanhvu.maintenance;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorAdvice {
  @ExceptionHandler(DomainError.class)
  ResponseEntity<?> domain(DomainError e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> validation(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Invalid request: check required fields, lengths and ranges"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException e) {
    org.slf4j.LoggerFactory.getLogger(ErrorAdvice.class).error("Database operation failed", e);
    return ResponseEntity.status(503)
        .body(
            Map.of("message", "Database busy or unavailable; retry with the same idempotency key"));
  }
}
