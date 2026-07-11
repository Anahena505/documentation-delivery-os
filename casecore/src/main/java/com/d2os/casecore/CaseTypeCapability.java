package com.d2os.casecore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the Phase 4 capability flags ({@code mutating}, {@code artifactKindAllowlist}) back out of a
 * pinned {@link CaseDefinitionSnapshot}'s {@code case_type} entry (research R2/R3; written by
 * {@link CaseService}'s {@code pinSnapshot}/{@code caseTypeEntry}).
 *
 * <p>Shared by {@link CaseService} (T018 — the guard-exemption helper) and artifacts' {@code
 * ArtifactService} (T017 — the read-only write-path enforcement choke point) so both read the SAME
 * frozen capability the Case was planned with (AD-4), rather than re-querying the live catalog. A
 * snapshot predating these flags (or an unparseable entries blob) defaults to {@code mutating=true}
 * with an empty allowlist — i.e. the pre-Phase-4 behavior (unrestricted mutating case) — so existing
 * case types are unaffected.
 */
public final class CaseTypeCapability {

    public record Capability(boolean mutating, List<String> artifactKindAllowlist) {}

    private static final Capability DEFAULT = new Capability(true, List.of());

    private CaseTypeCapability() {}

    public static Capability from(ObjectMapper objectMapper, CaseDefinitionSnapshot snapshot) {
        if (snapshot == null) {
            return DEFAULT;
        }
        try {
            JsonNode entries = objectMapper.readTree(snapshot.getEntries());
            for (JsonNode entry : entries) {
                if ("case_type".equals(entry.path("type").asText())) {
                    boolean mutating = entry.path("mutating").asBoolean(true);
                    List<String> allow = new ArrayList<>();
                    entry.path("artifactKindAllowlist").forEach(n -> allow.add(n.asText()));
                    return new Capability(mutating, allow);
                }
            }
        } catch (Exception e) {
            // fall through to the permissive default below
        }
        return DEFAULT;
    }
}
