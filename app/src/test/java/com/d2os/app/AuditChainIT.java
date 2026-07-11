package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.audit.AuditChainSealer;
import com.d2os.casecore.audit.AuditChainVerifier;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.testsupport.ContainerFixtures;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The audit hash chain, tamper-evident end to end (T043, US5, research R5, T6-b, FR-013, SC-005).
 * {@code d2os_app} cannot UPDATE/DELETE {@code audit_entry} at all (T6-a grants) — simulating
 * tampering therefore requires a direct connection as the schema-owner role (the same credentials
 * {@code spring.flyway.user} uses), bypassing the app's own runtime role entirely, exactly the kind
 * of out-of-band write this feature exists to detect.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditChainIT {

  @Autowired DataSource dataSource;
  @Autowired AuditWriter auditWriter;
  @Autowired AuditChainSealer auditChainSealer;
  @Autowired AuditChainVerifier auditChainVerifier;

  private static final UUID WORKSPACE_ID = UUID.randomUUID();

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
  void seedWorkspace() throws Exception {
    try (Connection c = dataSource.getConnection()) {
      exec(c, "SET app.workspace_id = '" + WORKSPACE_ID + "'");
      ins(
          c,
          "INSERT INTO workspace (id,name,created_by) VALUES (?,?,?) ON CONFLICT DO NOTHING",
          WORKSPACE_ID,
          "ws",
          "t");
    }
  }

  @Test
  void untamperedChainVerifiesIntact() {
    WorkspaceContext.set(WORKSPACE_ID);
    try {
      for (int i = 0; i < 5; i++) {
        auditWriter.record(
            WORKSPACE_ID, "workspace", WORKSPACE_ID, "TEST_EVENT_" + i, "test", Map.of("i", i));
      }
      var segment = auditChainSealer.sealWorkspace(WORKSPACE_ID);
      assertEquals(5, segment.getEntryCount());

      AuditChainVerifier.ChainResult result = auditChainVerifier.verifyWorkspace(WORKSPACE_ID);
      assertTrue(result.intact(), "an untampered chain must verify intact");
      assertEquals(1, result.segmentsVerified());
    } finally {
      WorkspaceContext.clear();
    }
  }

  @Test
  void tamperingWithASealedEntryBreaksTheChain() throws Exception {
    WorkspaceContext.set(WORKSPACE_ID);
    UUID tamperedEntryId;
    try {
      auditWriter.record(
          WORKSPACE_ID, "workspace", WORKSPACE_ID, "BEFORE_TAMPER", "test", Map.of());
      var segment = auditChainSealer.sealWorkspace(WORKSPACE_ID);
      assertEquals(1, segment.getEntryCount());
      tamperedEntryId = segment.getFromEntryId();
    } finally {
      WorkspaceContext.clear();
    }

    // Out-of-band tamper: connect as the schema owner (never through d2os_app, which has no
    // UPDATE grant on audit_entry at all) and alter the sealed entry's action after the fact.
    try (Connection owner =
        DriverManager.getConnection(
            ContainerFixtures.POSTGRES.getJdbcUrl(),
            ContainerFixtures.POSTGRES.getUsername(),
            ContainerFixtures.POSTGRES.getPassword())) {
      try (PreparedStatement ps =
          owner.prepareStatement("UPDATE audit_entry SET action = 'TAMPERED' WHERE id = ?")) {
        ps.setObject(1, tamperedEntryId);
        assertEquals(1, ps.executeUpdate());
      }
    }

    WorkspaceContext.set(WORKSPACE_ID);
    try {
      AuditChainVerifier.ChainResult result = auditChainVerifier.verifyWorkspace(WORKSPACE_ID);
      assertEquals(false, result.intact(), "an altered sealed entry must break the chain (SC-005)");
      assertEquals(1L, result.firstBrokenSegment());
    } finally {
      WorkspaceContext.clear();
    }
  }

  private void exec(Connection c, String sql) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.execute();
    }
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
}
