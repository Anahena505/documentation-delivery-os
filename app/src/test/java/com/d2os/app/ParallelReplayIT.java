package com.d2os.app;

import com.d2os.app.support.StubAiGatewayClient;
import com.d2os.replay.ReplayHarness;
import com.d2os.replay.ReplayReport;
import com.d2os.replay.SnapshotCompletenessCheck;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.testsupport.ContainerFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay coverage for the Phase 2 surface (T049, SC-008, FR-016). A parallel Case (four concurrent
 * specialists + the semantic Consistency-Check reviewer) with an uploaded attachment is run to
 * Delivered, then replay-audited:
 * <ul>
 *   <li>every recorded OperationExecution — including the four parallel specialists and the semantic
 *       consistency reviewer — replays <b>byte-identically</b> from stored output (mismatched == 0);</li>
 *   <li>the semantic Consistency-Check reviewer is present as a recorded, replayable operation;</li>
 *   <li>the attachment summary carries a complete inline reproducibility snapshot (model id/version +
 *       extracted-text/summary hashes) — verified via {@link SnapshotCompletenessCheck}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(StubAiGatewayClient.class)
class ParallelReplayIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired DataSource dataSource;
    @Autowired ReplayHarness replayHarness;
    @Autowired SnapshotCompletenessCheck completenessCheck;

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
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

    @BeforeAll
    static void bucket() {
        ContainerFixtures.startAll();
        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(ContainerFixtures.MINIO.getS3URL()))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ContainerFixtures.MINIO.getUserName(), ContainerFixtures.MINIO.getPassword())))
                .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket("d2os-artifacts").build());
        } catch (Exception ignored) {
        }
    }

    @BeforeEach
    void seed() throws Exception {
        UUID projectId = UUID.randomUUID(), versionId = UUID.randomUUID();
        featureId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            ins(c, "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING", WORKSPACE_ID, "ws", "t");
            ins(c, "INSERT INTO project (id,workspace_id,name,created_by) VALUES (?,?,?,?)", projectId, WORKSPACE_ID, "p", "t");
            ins(c, "INSERT INTO project_version (id,workspace_id,project_id,label,created_by) VALUES (?,?,?,?,?)", versionId, WORKSPACE_ID, projectId, "v1", "t");
            ins(c, "INSERT INTO feature (id,workspace_id,project_version_id,name,created_by) VALUES (?,?,?,?,?)", featureId, WORKSPACE_ID, versionId, "f", "t");
        }
    }

    @Test
    void parallelCaseWithAttachmentReplaysByteIdentical() throws Exception {
        HttpHeaders h = headers();

        String submissionId = (String) post("/api/v1/submissions",
                Map.of("formData", Map.of("category", "initiation", "description", "replay IT")), h).getBody().get("id");
        // Attach a file so attachment-summary reproducibility is exercised too.
        uploadTextAttachment(submissionId, "notes.txt", "Some supporting project notes for replay.");
        post("/api/v1/submissions/" + submissionId + "/confirm-classification", Map.of("confirmedCaseType", "initiation"), h);
        String caseId = (String) post("/api/v1/cases",
                Map.of("submissionId", submissionId, "featureId", featureId.toString()), h).getBody().get("id");
        rest.exchange(url("/api/v1/cases/" + caseId + "/start"), HttpMethod.POST, new HttpEntity<>(null, h), Void.class);

        assertEquals("Delivered", poll(caseId, h, Duration.ofSeconds(200)), "the parallel case should deliver");

        // Replay-audit: every operation (incl. the 4 specialists + consistency reviewer) is byte-identical.
        // Bind the workspace so the harness's repository reads see this workspace's rows under RLS
        // (in production this runs inside a workspace-bound request; here we set it explicitly).
        WorkspaceContext.set(WORKSPACE_ID);
        ReplayReport report;
        try {
            report = replayHarness.replay(UUID.fromString(caseId));
        } finally {
            WorkspaceContext.clear();
        }
        assertTrue(report.totalOperations() > 0, "expected recorded operations to replay");
        assertEquals(0, report.mismatched(),
                () -> "a parallel case must replay byte-identically; mismatched=" + report.mismatched()
                        + " of " + report.totalOperations());

        // The semantic Consistency-Check reviewer ran and was recorded (hence replayed above).
        assertTrue(scalarLong("SELECT count(*) FROM persona_invocation WHERE case_instance_id = '" + caseId
                + "' AND persona_key = 'consistency-reviewer'") >= 1,
                "the semantic consistency reviewer should be a recorded, replayable operation");

        // The attachment summary carries a complete inline reproducibility snapshot.
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            String sql = "SELECT s.model_id, s.model_version, s.extracted_text_hash, s.summary_hash "
                    + "FROM attachment_summary s JOIN attachment a ON a.id = s.attachment_id "
                    + "WHERE a.submission_id = '" + submissionId + "'";
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected an attachment summary to replay");
                assertNotNull(rs.getString("summary_hash"));
                assertTrue(completenessCheck.attachmentSummarySnapshotComplete(
                                rs.getString("model_id"), rs.getString("model_version"),
                                rs.getString("extracted_text_hash"), rs.getString("summary_hash")),
                        "the attachment-summary snapshot must be complete enough to reproduce (FR-016)");
            }
        }
    }

    private void uploadTextAttachment(String submissionId, String filename, String content) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", WORKSPACE_ID.toString());
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> up = rest.exchange(url("/api/v1/submissions/" + submissionId + "/attachments"),
                HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertEquals(201, up.getStatusCode().value());
        assertEquals("SUMMARIZED", up.getBody().get("status"));
    }

    private String poll(String caseId, HttpHeaders h, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<Map> r = rest.exchange(url("/api/v1/cases/" + caseId), HttpMethod.GET, new HttpEntity<>(h), Map.class);
            status = (String) r.getBody().get("status");
            if ("Delivered".equals(status) || "Escalated".equals(status) || "Suspended".equals(status)) return status;
            Thread.sleep(500);
        }
        return status;
    }

    private long scalarLong(String sql) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
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

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Workspace-Id", WORKSPACE_ID.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
