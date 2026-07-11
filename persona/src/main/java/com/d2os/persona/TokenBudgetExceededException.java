package com.d2os.persona;

/** Raised when the next AI call would exceed the Case's remaining token budget (NFR-7, FR-012). */
public class TokenBudgetExceededException extends RuntimeException {
  public TokenBudgetExceededException(String message) {
    super(message);
  }
}
