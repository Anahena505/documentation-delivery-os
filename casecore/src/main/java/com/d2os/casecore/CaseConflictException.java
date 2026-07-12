package com.d2os.casecore;

/** An active mutating Case already exists on the target Feature (FR-016) → HTTP 409. */
public class CaseConflictException extends RuntimeException {
  public CaseConflictException(String message) {
    super(message);
  }
}
