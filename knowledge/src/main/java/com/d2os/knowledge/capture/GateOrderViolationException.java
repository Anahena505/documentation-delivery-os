package com.d2os.knowledge.capture;

/**
 * Thrown when a promotion gate is recorded out of the fixed order PREFILTER → CURATION → D4, or
 * when a gate that already PASSed is passed again (at-most-one PASS per gate). Surfaced as HTTP
 * 409.
 */
public class GateOrderViolationException extends RuntimeException {

  public GateOrderViolationException(String message) {
    super(message);
  }
}
