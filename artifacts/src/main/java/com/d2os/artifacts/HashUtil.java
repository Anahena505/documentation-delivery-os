package com.d2os.artifacts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex digest helper. Intentionally duplicated from {@code persona.HashUtil} rather than
 * shared, to avoid an artifacts↔persona module cycle (persona already depends on artifacts) — a
 * ~15-line pure function doesn't justify a new shared module.
 */
final class HashUtil {

  private HashUtil() {}

  static String sha256Hex(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
