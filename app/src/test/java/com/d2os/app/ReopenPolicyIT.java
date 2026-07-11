package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateStatus;
import com.d2os.governance.reopen.GateReopenCandidate;
import com.d2os.governance.reopen.GateReopenCandidateRepository;
import com.d2os.governance.reopen.ReopenCandidateService;
import com.d2os.testsupport.ContainerFixtures;
import com.d2os.tenancy.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reopen policy end to end (T030, US3, research R3, FR-006/007/008, SC-003). Seeds a small
 * dependency chain directly (upstream artifact revised v1->v2; a direct dependent that DERIVES_FROM
 * v1 and holds an APPROVED gate; a transitive dependent two hops out that ALSO holds its own APPROVED
 * gate) rather than driving a full case pipeline — this is a policy/mechanism test, not an end-to-end
 * delivery test (those are covered by GateFlowIT/CommentRegenerateIT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(StubAiGatewayClient.class)
class ReopenPolicyIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired ReopenCandidateService reopenCandidateService;
    @Autowired GateInstanceRepository gateInstanceRepository;
    @Autowired GateReopenCandidateRepository candidateRepository;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private UUID caseId;
    private UUID artifactId;
    private UUID upstreamV1;
    private UUID upstreamV2;
    private UUID dep1Revision;
    private UUID dep1GateId;
    private UUID dep2Revision;
    private UUID dep2GateId;

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
    }

    @BeforeEach
    void seed() throws Exception {
        UUID projectId = UUID.randomUUID(), versionId = UUID.randomUUID(), featureId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        caseId = UUID.randomUUID();
        artifactId = UUID.randomUUID();
        UUID dep1ArtifactId = UUID.randomUUID();
        UUID dep2ArtifactId = UUID.randomUUID();
        upstreamV1 = UUID.randomUUID();
        upstreamV2 = UUID.randomUUID();
        dep1Revision = UUID.randomUUID();
        dep2Revision = UUID.randomUUID();
        dep1GateId = UUID.randomUUID();
        dep2GateId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", WORKSPACE_ID, "ws", "t");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)", projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)", versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)", featureId, WORKSPACE_ID, versionId, "f", "t");
            ins(c, "INSERT INTO problem_submission (id,workspace_id,form_data,created_by) VALUES (?,?,'{}'::jsonb,?)", submissionId, WORKSPACE_ID, "t");
            ins(c, "INSERT INTO case_instance (id,workspace_id,feature_id,submission_id,case_type_key,case_type_version,mode,status,token_budget,created_by) "
                    + "VALUES (?,?,?,?,'initiation','1.0.0','mutating','Running',1000,'t')", caseId, WORKSPACE_ID, featureId, submissionId);

            // Upstream artifact: v1 (superseded), v2 (current) — same Artifact, two revisions.
            ins(c, "INSERT INTO artifact (id,workspace_id,case_instance_id,template_definition_id,template_definition_version,artifact_type) "
                    + "VALUES (?,?,?,?,?,?)", artifactId, WORKSPACE_ID, caseId, UUID.randomUUID(), "1.0.0", "upstream-artifact");
            ins(c, "INSERT INTO artifact_revision (id,workspace_id,artifact_id,revision_no,storage_ref,content_hash) "
                    + "VALUES (?,?,?,1,?,?)", upstreamV1, WORKSPACE_ID, artifactId, "s3://x/v1", "hashv1");
            ins(c, "INSERT INTO artifact_revision (id,workspace_id,artifact_id,revision_no,storage_ref,content_hash) "
                    + "VALUES (?,?,?,2,?,?)", upstreamV2, WORKSPACE_ID, artifactId, "s3://x/v2", "hashv2");

            // Direct dependent (depth 1): DERIVES_FROM upstream v1, holds an APPROVED gate.
            ins(c, "INSERT INTO artifact (id,workspace_id,case_instance_id,template_definition_id,template_definition_version,artifact_type) "
                    + "VALUES (?,?,?,?,?,?)", dep1ArtifactId, WORKSPACE_ID, caseId, UUID.randomUUID(), "1.0.0", "dep1-artifact");
            ins(c, "INSERT INTO artifact_revision (id,workspace_id,artifact_id,revision_no,storage_ref,content_hash) "
                    + "VALUES (?,?,?,1,?,?)", dep1Revision, WORKSPACE_ID, dep1ArtifactId, "s3://x/dep1", "hashdep1");
            insertTraceLink(c, dep1Revision, upstreamV1);
            insertApprovedGate(c, dep1GateId, dep1Revision);

            // Transitive dependent (depth 2): DERIVES_FROM dep1, holds its OWN APPROVED gate that must
            // stay untouched (SC-003) — depth>1 candidates are never gate-linked.
            ins(c, "INSERT INTO artifact (id,workspace_id,case_instance_id,template_definition_id,template_definition_version,artifact_type) "
                    + "VALUES (?,?,?,?,?,?)", dep2ArtifactId, WORKSPACE_ID, caseId, UUID.randomUUID(), "1.0.0", "dep2-artifact");
            ins(c, "INSERT INTO artifact_revision (id,workspace_id,artifact_id,revision_no,storage_ref,content_hash) "
                    + "VALUES (?,?,?,1,?,?)", dep2Revision, WORKSPACE_ID, dep2ArtifactId, "s3://x/dep2", "hashdep2");
            insertTraceLink(c, dep2Revision, dep1Revision);
            insertApprovedGate(c, dep2GateId, dep2Revision);
        }
    }

    @Test
    void directDependentReopensAfterImpactAssessmentWhileTransitiveStaysUntouched() throws Exception {
        WorkspaceContext.set(WORKSPACE_ID);
        List<GateReopenCandidate> created;
        try {
            created = reopenCandidateService.identifyReopenCandidates(WORKSPACE_ID, upstreamV1);
        } finally {
            WorkspaceContext.clear();
        }

        assertEquals(2, created.size(), "one depth-1 candidate (dep1) and one depth-2 candidate (dep2)");
        GateReopenCandidate depth1 = created.stream().filter(c -> c.getDepth() == 1).findFirst().orElseThrow();
        GateReopenCandidate depth2 = created.stream().filter(c -> c.getDepth() == 2).findFirst().orElseThrow();

        assertEquals(dep1Revision, depth1.getDependentArtifactRevisionId());
        assertEquals(dep1GateId, depth1.getGateInstanceId(), "the depth-1 candidate names the gate to reopen");
        assertEquals(GateReopenCandidate.Disposition.PENDING, depth1.getDisposition());

        assertEquals(dep2Revision, depth2.getDependentArtifactRevisionId());
        assertNull(depth2.getGateInstanceId(), "transitive candidates carry no gate — nothing is directly reopenable (Q3/AD-5)");
        assertEquals(GateReopenCandidate.Disposition.MANUAL_REVIEW, depth2.getDisposition());

        // The DMN-driven transition: dep1's gate flips APPROVED -> REOPEN_CANDIDATE (a system move).
        GateInstance dep1Gate = gateInstanceRepository.findById(dep1GateId).orElseThrow();
        assertEquals(GateStatus.REOPEN_CANDIDATE, dep1Gate.status());

        // SC-003: the transitive dependent's OWN gate is never touched by identification alone.
        GateInstance dep2Gate = gateInstanceRepository.findById(dep2GateId).orElseThrow();
        assertEquals(GateStatus.APPROVED, dep2Gate.status(), "depth>1 gates stay untouched");

        // Reopen without an impact assessment yet -> 409.
        HttpHeaders h = headers();
        ResponseEntity<Map> blocked = rest.exchange(url("/api/v1/gates/" + dep1GateId + "/reopen"),
                HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
        assertEquals(409, blocked.getStatusCode().value(), "reopen without an impact assessment must 409 (FR-007)");

        // Record the impact assessment.
        ResponseEntity<Map> assessed = rest.exchange(url("/api/v1/gates/" + dep1GateId + "/impact-assessment"),
                HttpMethod.POST, new HttpEntity<>(Map.of(
                        "upstreamArtifactRevisionId", upstreamV1.toString(),
                        "reason", "upstream artifact revised", "scope", "dep1 only", "risk", "low"), h), Map.class);
        assertEquals(201, assessed.getStatusCode().value());

        // Reopen now succeeds.
        ResponseEntity<Map> reopened = rest.exchange(url("/api/v1/gates/" + dep1GateId + "/reopen"),
                HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
        assertEquals(200, reopened.getStatusCode().value(), () -> "reopen body: " + reopened.getBody());
        assertEquals("REOPENED", reopened.getBody().get("status"));
        assertNotNull(reopened.getBody().get("decisionId"), "the reopen must be recorded as a Decision");

        GateInstance finalGate = gateInstanceRepository.findById(dep1GateId).orElseThrow();
        assertNotNull(finalGate.getDeltaReportId(), "the delta report (upstream v1 -> v2) must be attached");

        GateReopenCandidate reopenedCandidate = candidateRepository.findById(depth1.getId()).orElseThrow();
        assertEquals(GateReopenCandidate.Disposition.REOPENED, reopenedCandidate.getDisposition());

        // dep2's gate is STILL untouched after the reopen action too.
        assertEquals(GateStatus.APPROVED, gateInstanceRepository.findById(dep2GateId).orElseThrow().status());
    }

    private void insertTraceLink(Connection c, UUID fromRevisionId, UUID toRevisionId) throws Exception {
        ins(c, "INSERT INTO trace_link (id,workspace_id,from_type,from_id,to_type,to_id,link_type) "
                + "VALUES (?,?, 'artifact_revision', ?, 'artifact_revision', ?, 'DERIVES_FROM')",
                UUID.randomUUID(), WORKSPACE_ID, fromRevisionId, toRevisionId);
    }

    private void insertApprovedGate(Connection c, UUID gateId, UUID subjectRevisionId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO gate_instance (id,workspace_id,case_instance_id,gate_type,gate_definition_key,"
                        + "gate_definition_version,subject_artifact_revision_id,subject_type,subject_id,inputs_ref,status) "
                        + "VALUES (?,?,?,'REVIEW','review-gate',1,?,'ARTIFACT_REVISION',?,'{}'::jsonb,'APPROVED')")) {
            ps.setObject(1, gateId);
            ps.setObject(2, WORKSPACE_ID);
            ps.setObject(3, caseId);
            ps.setObject(4, subjectRevisionId);
            ps.setObject(5, subjectRevisionId);
            ps.executeUpdate();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", WORKSPACE_ID.toString());
        h.set("X-Actor", "reviewer-2");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
    }

    private void ins(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof UUID u) ps.setObject(i + 1, u);
                else ps.setString(i + 1, params[i].toString());
            }
            ps.executeUpdate();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
