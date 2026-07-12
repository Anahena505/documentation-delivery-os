package com.d2os.catalog;

/** A workspace has already subscribed to this Global definition (T025, FR-013) → HTTP 409. */
public class SubscriptionConflictException extends RuntimeException {
  public SubscriptionConflictException(String message) {
    super(message);
  }
}
