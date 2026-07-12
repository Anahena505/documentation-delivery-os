package com.d2os.knowledge.capture;

/**
 * Thrown when the D4 approver is not authorized: they lack the workspace-owner role, or they are
 * the redaction actor (the D4 gate is non-self-satisfiable — the approver must differ from the
 * actor who saved the REDACTED revision). Surfaced as HTTP 403.
 */
public class D4AuthorizationException extends RuntimeException {

  public D4AuthorizationException(String message) {
    super(message);
  }
}
