package com.d2os.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex digest helper (tasks.md T013), extracted as a small PUBLIC utility (unlike {@code
 * DefinitionPublishService}'s private {@code sha256}) so callers outside {@code catalog} can pin
 * and re-verify the exact same content hash — specifically the {@code studio} module's
 * gate-integrated {@code PublishService} (T013/T016), which cannot live in {@code catalog} itself
 * without creating a {@code catalog -&gt; governance} circular dependency ({@code governance}
 * already depends on {@code catalog}) — see {@code studio.PublishService}'s javadoc for the full
 * module-placement reasoning.
 *
 * <p>Deliberately duplicated logic rather than refactoring {@link DefinitionPublishService}'s
 * private {@code sha256} to delegate here: {@code DefinitionPublishService} is explicitly untouched
 * in this phase (Phase 1-2's design decision keeps it as {@code CatalogSeedLoader}'s sole
 * direct-publish primitive). Same "~20-line pure function, not worth a forced coupling" trade-off
 * {@code governance.HashUtil}'s own javadoc documents for the identical duplication.
 */
public final class ChecksumUtil {

  private ChecksumUtil() {}

  public static String sha256Hex(String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
    }
  }
}
