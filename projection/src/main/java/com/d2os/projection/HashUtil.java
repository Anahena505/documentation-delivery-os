package com.d2os.projection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hex digest helper (T010). Intentionally duplicated from {@code casecore.HashUtil}/{@code
 * governance.HashUtil}/etc. rather than shared, matching this repo's established per-module
 * precedent (those classes' own javadocs document the same trade-off) — a ~20-line pure function
 * doesn't justify a new shared module or a cross-module dependency just for this. Used by {@link
 * EquivalenceVerifier} to digest the sorted canonical natural-key/edge-identity strings of one
 * node/edge type, on both the candidate graph generation and the independently-queried relational
 * truth (research R5).
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
