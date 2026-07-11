package com.d2os.persona;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 hex digest helper, shared by the recorder (T034) and the replay harness (T041). */
public final class HashUtil {

  private HashUtil() {}

  public static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static String sha256Hex(String content) {
    return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
  }
}
