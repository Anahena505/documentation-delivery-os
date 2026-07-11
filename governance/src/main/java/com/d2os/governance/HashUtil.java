package com.d2os.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex digest helper (T020). Intentionally duplicated from {@code persona.HashUtil}/{@code
 * artifacts.HashUtil} rather than shared, matching this repo's existing precedent (those two classes'
 * own javadocs document the same trade-off) — a ~20-line pure function doesn't justify a new shared
 * module or a cross-module dependency just for this.
 */
final class HashUtil {

    private HashUtil() {}

    static String sha256Hex(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
