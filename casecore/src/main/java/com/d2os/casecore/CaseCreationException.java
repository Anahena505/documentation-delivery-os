package com.d2os.casecore;

/**
 * The Case cannot be opened (submission not confirmed, no published case type, etc.) → HTTP 422.
 */
public class CaseCreationException extends RuntimeException {
  public CaseCreationException(String message) {
    super(message);
  }
}
