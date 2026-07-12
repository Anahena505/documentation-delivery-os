package com.d2os.intake.attachment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex digest helper. Intentionally duplicated from {@code persona.HashUtil}/{@code
 * artifacts.HashUtil} rather than shared, to avoid a new shared module for a ~20-line pure function
 * (the same trade-off those classes document). Used for the stored-bytes hash (Principle III) and
 * the extracted-text / summary hashes recorded in the reproducibility snapshot (Principle II).
 */
final class HashUtil {

  private HashUtil() {}

  static String sha256Hex(byte[] content) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
    }
  }

  static String sha256Hex(String content) {
    return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
  }
}
