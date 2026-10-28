package dev.thanhvu.maintenance;

public class DomainError extends RuntimeException {
  public final int status;

  public DomainError(int status, String message) {
    super(message);
    this.status = status;
  }
}
