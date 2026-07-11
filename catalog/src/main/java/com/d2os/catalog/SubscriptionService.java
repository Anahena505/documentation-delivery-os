package com.d2os.catalog;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy-on-subscribe: a workspace gets its OWN copy of a Global (system-seeded) definition on
 * subscribe, insulated from any future change to the source (Phase 6 US4, T025, research R6,
 * FR-013/014/015, T4-d). Checksum equality between the source and the copy is the copy-integrity
 * proof (T4-d) — since {@code markPublished}/{@code markPublishedFromReview} both set {@code
 * checksum = sha256(body)}, copying the body verbatim and recomputing the SAME checksum function
 * over it necessarily reproduces the source's checksum unless the copy diverged.
 */
@Service
public class SubscriptionService {

  private static final UUID SYSTEM_WORKSPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000000");

  private final DefinitionAssetRepository repository;
  private final CatalogAuditWriter auditWriter;
  private final JdbcTemplate jdbcTemplate;

  public SubscriptionService(
      DefinitionAssetRepository repository,
      CatalogAuditWriter auditWriter,
      JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.auditWriter = auditWriter;
    this.jdbcTemplate = jdbcTemplate;
  }

  public record SubscriptionResult(DefinitionAsset copy, boolean checksumVerified) {}

  @Transactional
  public SubscriptionResult subscribe(UUID sourceDefinitionId, UUID workspaceId, String actor) {
    DefinitionAsset source =
        repository
            .findById(sourceDefinitionId)
            .orElseThrow(() -> new NoSuchElementException("definition " + sourceDefinitionId));
    if (!SYSTEM_WORKSPACE.equals(source.getWorkspaceId())) {
      throw new IllegalStateException(
          "only Global (system-seeded) definitions may be subscribed to: "
              + sourceDefinitionId
              + " belongs to workspace "
              + source.getWorkspaceId());
    }
    if (alreadySubscribed(workspaceId, sourceDefinitionId)) {
      throw new SubscriptionConflictException(
          "workspace " + workspaceId + " has already subscribed to " + sourceDefinitionId);
    }

    DefinitionAsset copy =
        new DefinitionAsset(
            UUID.randomUUID(),
            workspaceId,
            source.getKey(),
            source.getVersion(),
            source.getType(),
            "en",
            source.getBody(),
            actor);
    copy.recordCopyProvenance(sourceDefinitionId);
    String checksum = ChecksumUtil.sha256Hex(source.getBody());
    copy.markPublished(
        checksum); // the copy is immediately Published — resolution prefers it (research R6)
    repository.save(copy);

    boolean checksumVerified = checksum.equals(source.getChecksum());

    jdbcTemplate.update(
        "INSERT INTO library_subscription (id, workspace_id, source_definition_id, copied_definition_id, subscribed_by) "
            + "VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        workspaceId,
        sourceDefinitionId,
        copy.getId(),
        actor);

    auditWriter.record(
        workspaceId,
        "definition_asset",
        copy.getId(),
        "LIBRARY_SUBSCRIBED",
        actor,
        Map.of(
            "sourceDefinitionId",
            sourceDefinitionId.toString(),
            "checksumVerified",
            checksumVerified,
            "key",
            source.getKey(),
            "version",
            source.getVersion()));
    return new SubscriptionResult(copy, checksumVerified);
  }

  /**
   * Global (system-seeded) rows browsable in the library, with the caller workspace's own
   * subscription state.
   */
  public List<Map<String, Object>> browseGlobal(UUID callerWorkspaceId) {
    return jdbcTemplate.queryForList(
        "SELECT d.id, d.type, d.key, d.version, "
            + "(SELECT ls.copied_definition_id FROM library_subscription ls "
            + " WHERE ls.workspace_id = ? AND ls.source_definition_id = d.id) AS copied_definition_id "
            + "FROM definition_asset d WHERE d.workspace_id = ? AND d.status = 'Published'",
        callerWorkspaceId,
        SYSTEM_WORKSPACE);
  }

  private boolean alreadySubscribed(UUID workspaceId, UUID sourceDefinitionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM library_subscription WHERE workspace_id = ? AND source_definition_id = ?",
            Long.class,
            workspaceId,
            sourceDefinitionId);
    return count != null && count > 0;
  }
}
