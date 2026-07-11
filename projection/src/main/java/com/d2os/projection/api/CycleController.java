package com.d2os.projection.api;

import com.d2os.projection.GraphNode;
import com.d2os.projection.GraphNodeRepository;
import com.d2os.projection.query.TraceabilityQueryService;
import com.d2os.projection.query.TraceabilityQueryService.GraphNodeView;
import com.d2os.tenancy.WorkspaceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T023 &mdash; {@code GET /graph/cycles} (contracts/api.yaml, FR-008/009, US3). {@link
 * com.d2os.projection.cycle.CycleDetector}'s findings are persisted as {@code in_app_notification}
 * rows (T022, {@code source_module='projection'}, {@code type='CYCLE_DETECTED')} — there is no
 * separate {@code cycle_finding} table in data-model.md — so this endpoint reads those rows back
 * and resolves each finding's {@code subject_ref.memberNodeIds} into full {@link GraphNodeView}s
 * via {@link GraphNodeRepository}, reusing {@link TraceabilityQueryService#toView} rather than
 * duplicating its attribute-parsing/owning-resource-link logic (same reuse convention {@link
 * TraceabilityController}'s node-detail endpoint already follows).
 *
 * <p>A member node id that no longer resolves (e.g. its generation was purged by a rebuild flip
 * since the finding was raised) drops that ENTIRE finding rather than returning a partial/broken
 * member list &mdash; a stale finding pointing at dead rows is not a useful answer to "what cycle
 * exists right now."
 */
@RestController
@RequestMapping("/api/v1/graph")
public class CycleController {

  private final JdbcTemplate jdbcTemplate;
  private final GraphNodeRepository graphNodeRepository;
  private final TraceabilityQueryService traceabilityQueryService;
  private final ObjectMapper objectMapper;

  public CycleController(
      JdbcTemplate jdbcTemplate,
      GraphNodeRepository graphNodeRepository,
      TraceabilityQueryService traceabilityQueryService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.graphNodeRepository = graphNodeRepository;
    this.traceabilityQueryService = traceabilityQueryService;
    this.objectMapper = objectMapper;
  }

  public record CycleFinding(UUID id, OffsetDateTime detectedAt, List<GraphNodeView> memberNodes) {}

  @GetMapping("/cycles")
  public ResponseEntity<List<CycleFinding>> cycles() {
    UUID workspaceId = WorkspaceContext.require();
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT id, subject_ref::text AS subject_ref_text, created_at FROM in_app_notification "
                + "WHERE workspace_id = ? AND source_module = 'projection' AND type = 'CYCLE_DETECTED' "
                + "ORDER BY created_at DESC",
            workspaceId);

    List<CycleFinding> findings = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      CycleFinding finding = toCycleFinding(workspaceId, row);
      if (finding != null) {
        findings.add(finding);
      }
    }
    return ResponseEntity.ok(findings);
  }

  private CycleFinding toCycleFinding(UUID workspaceId, Map<String, Object> row) {
    Map<String, Object> subjectRef;
    try {
      subjectRef =
          objectMapper.readValue(
              (String) row.get("subject_ref_text"), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return null; // malformed subject_ref — defensive only, every write path here is our own
    }
    @SuppressWarnings("unchecked")
    List<String> memberNodeIds = (List<String>) subjectRef.get("memberNodeIds");
    if (memberNodeIds == null) {
      return null;
    }

    List<GraphNodeView> memberNodes = new ArrayList<>();
    for (String idString : memberNodeIds) {
      UUID nodeId = UUID.fromString(idString);
      GraphNode node = graphNodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId).orElse(null);
      if (node == null) {
        return null; // stale finding — see class javadoc
      }
      memberNodes.add(traceabilityQueryService.toView(node));
    }

    return new CycleFinding(
        (UUID) row.get("id"), toOffsetDateTime(row.get("created_at")), memberNodes);
  }

  private static OffsetDateTime toOffsetDateTime(Object value) {
    if (value instanceof OffsetDateTime odt) return odt;
    if (value instanceof java.sql.Timestamp ts)
      return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    return null;
  }
}
