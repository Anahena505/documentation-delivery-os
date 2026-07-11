package com.d2os.casecore.dto;

import com.d2os.casecore.AuditEntryRecord;
import com.d2os.casecore.CaseInstance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API view of an Enhancement case's pinned baseline (T025, US3, research R4 — {@code GET
 * /cases/{id}/baseline}). Built from the {@code BASELINE_RESOLVED} {@link AuditEntryRecord} {@code
 * BaselineResolutionDelegate} (orchestration) writes — casecore has no dependency on the artifacts
 * module (the reverse dependency would be a cycle: artifacts already depends on casecore), so the
 * audit entry's durable JSON is this endpoint's only source of truth, per the same "audit entry as
 * durable record" convention the delegate itself follows.
 */
public record BaselineResponse(
        UUID caseId,
        UUID featureId,
        UUID baselineCaseId,
        List<BaselineEntry> entries
) {

    /**
     * One pinned baseline ArtifactRevision.
     *
     * <p><b>{@code superseded} (research R4/T025 design decision):</b> there is no existing concept of
     * "superseded" for an ArtifactRevision anywhere in this codebase — {@code superseded}/{@code
     * deprecated} exist only for {@code KnowledgeItem}, a completely different subsystem. Defined here,
     * simply and derivably from existing data with no new schema: a pinned baseline revision is {@code
     * superseded} when a STRICTLY NEWER revision now exists on the same Artifact (i.e. {@code
     * max(revision_no) over that artifact_id > the pinned revisionNo}) — the pinned reference itself
     * never changes, only this flag.
     *
     * <p><b>{@code deprecated}:</b> has no natural existing analog at the artifact-revision level at
     * all (again, only {@code KnowledgeItem} has a deprecation concept). Always {@code false} here —
     * an honest placeholder, not a fabricated mechanism, matching this project's established standard
     * of reporting real scope gaps rather than inventing coverage.
     */
    public record BaselineEntry(
            UUID artifactId,
            String artifactType,
            UUID revisionId,
            int revisionNo,
            String contentHash,
            boolean superseded,
            boolean deprecated
    ) {}

    public static BaselineResponse from(CaseInstance kase, AuditEntryRecord entry,
            ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        try {
            JsonNode details = objectMapper.readTree(entry.getDetails());
            UUID featureId = UUID.fromString(details.path("featureId").asText());
            UUID baselineCaseId = UUID.fromString(details.path("baselineCaseId").asText());

            List<BaselineEntry> entries = new ArrayList<>();
            for (JsonNode r : details.path("revisions")) {
                UUID artifactId = UUID.fromString(r.path("artifactId").asText());
                UUID revisionId = UUID.fromString(r.path("revisionId").asText());
                int revisionNo = r.path("revisionNo").asInt();
                boolean superseded = isSuperseded(jdbcTemplate, artifactId, revisionNo);
                entries.add(new BaselineEntry(
                        artifactId, r.path("artifactType").asText(), revisionId, revisionNo,
                        r.path("contentHash").asText(), superseded, false));
            }
            return new BaselineResponse(kase.getId(), featureId, baselineCaseId, entries);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "malformed BASELINE_RESOLVED audit entry for case " + kase.getId(), e);
        }
    }

    private static boolean isSuperseded(JdbcTemplate jdbcTemplate, UUID artifactId, int pinnedRevisionNo) {
        Integer maxRevisionNo = jdbcTemplate.queryForObject(
                "SELECT max(revision_no) FROM artifact_revision WHERE artifact_id = ?", Integer.class, artifactId);
        return maxRevisionNo != null && maxRevisionNo > pinnedRevisionNo;
    }
}
