package com.d2os.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same-transaction audit + outbox write helper for {@code catalog} (Phase 6 US3/US4, research
 * R4/R6, FR-012/015/017). Mirrors {@code casecore.AuditWriter} exactly (raw JDBC over {@code
 * audit_entry}/{@code event_outbox}) since {@code catalog} has no dependency on {@code casecore} —
 * same cross-module-raw-write convention {@code ArtifactService}/{@code ConsistencyService} already
 * establish elsewhere in this codebase. {@code MANDATORY} propagation: only callable from within an
 * already-open transaction, so the audit/event rows commit or roll back atomically with the state
 * change they describe (fork/deprecate/subscribe are each audited events per FR-017).
 */
@Component
public class CatalogAuditWriter {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public CatalogAuditWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID workspaceId,
      String subjectType,
      UUID subjectId,
      String action,
      String actor,
      Map<String, Object> details) {
    String detailsJson = toJson(details);
    jdbcTemplate.update(
        "INSERT INTO audit_entry (id, workspace_id, subject_type, subject_id, action, actor, details) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
        UUID.randomUUID(),
        workspaceId,
        subjectType,
        subjectId,
        action,
        actor,
        detailsJson);
    jdbcTemplate.update(
        "INSERT INTO event_outbox (id, workspace_id, aggregate_type, aggregate_id, event_type, payload) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
        UUID.randomUUID(),
        workspaceId,
        subjectType,
        subjectId,
        action,
        detailsJson);
  }

  private String toJson(Map<String, Object> details) {
    try {
      return objectMapper.writeValueAsString(details == null ? Map.of() : details);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unserializable audit details", e);
    }
  }
}
