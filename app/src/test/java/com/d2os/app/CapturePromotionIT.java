package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.knowledge.KnowledgeScope;
import com.d2os.knowledge.capture.CaptureCandidate;
import com.d2os.knowledge.capture.CaptureCandidateRepository;
import com.d2os.knowledge.capture.CaptureService;
import com.d2os.knowledge.capture.D4AuthorizationException;
import com.d2os.knowledge.capture.GateOrderViolationException;
import com.d2os.knowledge.capture.PrefilterFinding;
import com.d2os.knowledge.capture.PrefilterFindingRepository;
import com.d2os.knowledge.capture.PromotionGateService;
import com.d2os.knowledge.capture.RedactionService;
import com.d2os.knowledge.capture.SensitivityPreFilter;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.WorkspaceProvisioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture → promotion pipeline IT (T027, US2, SC-005/SC-006). Drives the pipeline through the SERVICES
 * DIRECTLY (not the full BPMN process) — the BPMN wiring is exercised separately; driving the services
 * gives deterministic, fast coverage of the state machine + gate discipline, which is what SC-005/006
 * assert. Uses the same Testcontainers + RLS + workspace-provisioning setup as {@code KnowledgeRetrievalIT}.
 *
 * <p>Asserts: delivered case → CAPTURED, PROJECT-confidential, non-promotable candidate; pre-filter
 * produces findings and default-excludes a seeded PII span; redaction creates a new revision; D4
 * self-approval → 403 (non-self-satisfiable); workspace-owner APPROVE → PUBLISHED with a real
 * KnowledgeItem carrying source_candidate_id provenance; each-stage REJECT stays non-promotable with a
 * reason; a gate-order violation → 409; and zero partial promotion (a rejected candidate never yields a
 * knowledge_item).
 */
@SpringBootTest
@Import(StubAiGatewayClient.class)
class CapturePromotionIT {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired WorkspaceProvisioningService workspaceProvisioningService;
    @Autowired CaptureService captureService;
    @Autowired CaptureCandidateRepository candidateRepository;
    @Autowired PrefilterFindingRepository findingRepository;
    @Autowired SensitivityPreFilter preFilter;
    @Autowired RedactionService redactionService;
    @Autowired PromotionGateService promotionGateService;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private UUID projectId;
    private UUID featureId;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ContainerFixtures.startAll();
        String jdbcUrl = ContainerFixtures.POSTGRES.getJdbcUrl();
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", ContainerFixtures.POSTGRES::getUsername);
        registry.add("spring.flyway.password", ContainerFixtures.POSTGRES::getPassword);
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "d2os_app");
        registry.add("spring.datasource.password", () -> "d2os_app");
        registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
        registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
        registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
        registry.add("d2os.storage.bucket", () -> "d2os-artifacts");
    }

    @BeforeEach
    void seedTenancy() throws Exception {
        projectId = UUID.randomUUID();
        featureId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        WorkspaceContext.set(WORKSPACE_ID);
        workspaceProvisioningService.provisionWorkspace(WORKSPACE_ID, "capture-it-ws", "test");

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)",
                    projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)",
                    versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)",
                    featureId, WORKSPACE_ID, versionId, "f", "t");
        }
    }

    @AfterEach
    void clearContext() {
        WorkspaceContext.clear();
    }

    /** Insert a Delivered case for this feature and return its id. */
    private UUID deliveredCase() throws Exception {
        UUID caseId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,"
                    + "case_type_version,token_budget,status,created_by) VALUES (?,?,?,?,?,?,?,?,?)",
                    caseId, WORKSPACE_ID, featureId, UUID.randomUUID(), "initiation", "2.0.0",
                    1_000_000L, "Delivered", "t");
        }
        return caseId;
    }

    @Test
    void capturedCandidatesAreProjectConfidentialAndNonPromotable() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();

        List<UUID> ids = captureService.captureFrom(caseId);
        assertEquals(1, ids.size(), "v1 harvest produces one candidate per delivered case");

        CaptureCandidate c = candidateRepository.findById(ids.get(0)).orElseThrow();
        assertEquals(CaptureCandidate.Status.CAPTURED, c.status(), "candidates are born CAPTURED");
        assertEquals(projectId, c.getProjectId(), "candidates are PROJECT-scoped (confidential birth scope)");
        assertEquals(1, c.getRevision());

        // Non-promotable: no knowledge_item exists for this candidate's provenance yet.
        Integer items = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_item WHERE source_candidate_id = ?", Integer.class, c.getId());
        assertEquals(0, items, "a captured candidate is non-promotable — no knowledge_item yet");
    }

    @Test
    void prefilterRecordsFindingsAndDefaultExcludesPii() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();

        // Seed a candidate whose content carries a PII span (email) so the default-exclusion is observable.
        UUID candidateId = insertCandidate(caseId,
                "Lessons with PII", "Contact the owner at alice@example.com for the retro notes.");

        List<PrefilterFinding> findings = preFilter.prefilter(candidateId, List.of());
        assertFalse(findings.isEmpty(), "the seeded email is detected");
        assertTrue(findings.stream().anyMatch(f -> "EMAIL".equals(f.getCategory())), "an EMAIL finding is recorded");

        CaptureCandidate after = candidateRepository.findById(candidateId).orElseThrow();
        assertEquals(CaptureCandidate.Status.PREFILTERED, after.status(), "CAPTURED → PREFILTERED");
        assertFalse(after.getContent().contains("alice@example.com"),
                "the PII span is redacted OUT of the content by default (T3-c)");
        assertTrue(after.getContent().contains("[REDACTED:EMAIL]"), "the span is replaced by a redaction marker");
    }

    @Test
    void redactionCreatesNewRevisionPreservingPrior() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();
        UUID root = insertCandidate(caseId, "Lesson", "Body with alice@example.com inside.");
        preFilter.prefilter(root, List.of());

        UUID revisionId = redactionService.saveRedaction(
                root, "curator", "Redacted lesson", "Body with the contact removed.",
                List.of("lessons-learned"), null);

        assertFalse(revisionId.equals(root), "redaction is a NEW revision row, not an in-place edit");
        CaptureCandidate rev = candidateRepository.findById(revisionId).orElseThrow();
        assertEquals(2, rev.getRevision());
        assertEquals(root, rev.getRevisionOf(), "the new revision chains to the prior one");
        assertEquals(CaptureCandidate.Status.D4_PENDING, rev.status(), "redaction advances to D4_PENDING");

        CaptureCandidate prior = candidateRepository.findById(root).orElseThrow();
        assertEquals(CaptureCandidate.Status.PREFILTERED, prior.status(), "the prior revision is preserved");
    }

    @Test
    void d4SelfApprovalIsRejected() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();
        UUID root = insertCandidate(caseId, "Lesson", "Body.");
        preFilter.prefilter(root, List.of());
        UUID revisionId = redactionService.saveRedaction(
                root, "curator-alice", "Redacted", "Clean body.", List.of("lessons-learned"), null);

        // The redaction actor cannot self-approve at D4 (non-self-satisfiable), even as a workspace owner.
        assertThrows(D4AuthorizationException.class, () -> promotionGateService.decideD4(
                revisionId, "curator-alice", true, true, KnowledgeScope.WORKSPACE, null),
                "the D4 approver must differ from the redaction actor");

        // A non-owner is also refused.
        assertThrows(D4AuthorizationException.class, () -> promotionGateService.decideD4(
                revisionId, "someone-else", false, true, KnowledgeScope.WORKSPACE, null),
                "the D4 approver must hold the workspace-owner role");

        CaptureCandidate rev = candidateRepository.findById(revisionId).orElseThrow();
        assertEquals(CaptureCandidate.Status.D4_PENDING, rev.status(), "a refused D4 leaves the candidate pending");
    }

    @Test
    void ownerApprovePublishesWithProvenance() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();
        UUID root = insertCandidate(caseId, "Lesson", "Body.");
        preFilter.prefilter(root, List.of());
        UUID revisionId = redactionService.saveRedaction(
                root, "curator-alice", "Redacted", "Clean governed body.", List.of("lessons-learned"), null);

        UUID itemId = promotionGateService.decideD4(
                revisionId, "owner-bob", true, true, KnowledgeScope.WORKSPACE, "approved");
        assertNotNull(itemId, "APPROVE publishes a KnowledgeItem");

        CaptureCandidate rev = candidateRepository.findById(revisionId).orElseThrow();
        assertEquals(CaptureCandidate.Status.PUBLISHED, rev.status(), "candidate → PUBLISHED on approve");

        Map<String, Object> item = jdbcTemplate.queryForMap(
                "SELECT source_candidate_id, status FROM knowledge_item WHERE id = ?", itemId);
        assertEquals(revisionId, item.get("source_candidate_id"),
                "the published item carries source_candidate_id provenance");
        assertEquals("PUBLISHED", item.get("status"));
    }

    @Test
    void rejectAtCurationStaysNonPromotableWithReason() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();
        UUID root = insertCandidate(caseId, "Lesson", "Body.");
        preFilter.prefilter(root, List.of());

        promotionGateService.rejectAtGate(root, com.d2os.knowledge.capture.PromotionGateRecord.Gate.CURATION,
                CaptureCandidate.RejectionStage.CURATION, "curator", "off-domain — not curatable");

        CaptureCandidate c = candidateRepository.findById(root).orElseThrow();
        assertEquals(CaptureCandidate.Status.REJECTED, c.status());
        assertEquals("CURATION", c.getRejectionStage());
        assertEquals("off-domain — not curatable", c.getRejectionReason());

        // Zero partial promotion: a rejected candidate never yields a knowledge_item.
        Integer items = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_item WHERE source_candidate_id = ?", Integer.class, root);
        assertEquals(0, items, "a rejected candidate yields no knowledge_item (no partial promotion)");
    }

    @Test
    void gateOrderViolationIsRejected() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        UUID caseId = deliveredCase();
        UUID root = insertCandidate(caseId, "Lesson", "Body.");

        // Redaction before pre-filter → wrong state (candidate is CAPTURED, not PREFILTERED).
        assertThrows(RuntimeException.class, () -> redactionService.saveRedaction(
                root, "curator", "x", "y", List.of("t"), null),
                "redaction before pre-filter is a fixed-order violation");

        // D4 on a candidate that never reached D4_PENDING → gate-order violation (409).
        assertThrows(GateOrderViolationException.class, () -> promotionGateService.decideD4(
                root, "owner", true, true, KnowledgeScope.WORKSPACE, null),
                "D4 out of order is a gate-order violation");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Insert a CAPTURED revision-1 candidate directly so subtests control the exact content. */
    private UUID insertCandidate(UUID caseId, String title, String content) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO capture_candidate (id,workspace_id,case_instance_id,project_id,revision,"
                            + "title,content,tags,status) VALUES (?,?,?,?,?,?,?, '{lessons-learned}', 'CAPTURED')")) {
                ps.setObject(1, id);
                ps.setObject(2, WORKSPACE_ID);
                ps.setObject(3, caseId);
                ps.setObject(4, projectId);
                ps.setInt(5, 1);
                ps.setString(6, title);
                ps.setString(7, content);
                ps.executeUpdate();
            }
        }
        return id;
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
    }

    private void ins(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof UUID u) ps.setObject(i + 1, u);
                else if (p instanceof Long l) ps.setLong(i + 1, l);
                else if (p instanceof Integer n) ps.setInt(i + 1, n);
                else ps.setString(i + 1, p.toString());
            }
            ps.executeUpdate();
        }
    }
}
