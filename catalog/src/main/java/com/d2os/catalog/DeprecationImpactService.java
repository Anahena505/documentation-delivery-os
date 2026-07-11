package com.d2os.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computed (never stored) deprecation impact for a DefinitionAsset (Phase 6 US3, T020, research R5,
 * FR-010, SC-005). Three independent sources, each queried fresh every call — the report can never
 * go stale because nothing about it is cached:
 * <ol>
 *   <li><b>Pinned active cases</b> — exact-pin JSONB containment over {@code
 *       case_definition_snapshot.entries} (GIN-indexed, V25), joined to non-terminal {@code
 *       case_instance} rows. This is the authoritative "who is this actually running under right
 *       now" answer — a pinned snapshot never re-resolves (AD-4), so a case here keeps executing
 *       unaffected by deprecation (Principle I) regardless of what this report says.</li>
 *   <li><b>Definition-graph dependents</b> — other Published definitions whose {@code dependsOn}
 *       list names this {@code (type, key)}.</li>
 *   <li><b>Subscription copies</b> — {@code library_subscription} rows whose {@code
 *       source_definition_id} is this definition (Phase 6 US4, T025's copy-on-subscribe).</li>
 * </ol>
 */
@Service
public class DeprecationImpactService {

    public record PinnedCase(UUID caseInstanceId, String status) {}
    public record DependentDefinition(UUID definitionId, String type, String key, String version) {}
    public record SubscriptionCopy(UUID workspaceId, UUID copiedDefinitionId, java.time.OffsetDateTime subscribedAt) {}

    public record ImpactReport(UUID definitionId, String type, String key, String version,
                               List<PinnedCase> pinnedActiveCases, List<DependentDefinition> dependents,
                               List<SubscriptionCopy> subscriptionCopies) {
        public int totalImpact() {
            return pinnedActiveCases.size() + dependents.size() + subscriptionCopies.size();
        }
    }

    private final DefinitionAssetRepository definitionAssetRepository;
    private final JdbcTemplate jdbcTemplate;

    public DeprecationImpactService(DefinitionAssetRepository definitionAssetRepository, JdbcTemplate jdbcTemplate) {
        this.definitionAssetRepository = definitionAssetRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ImpactReport compute(UUID definitionId) {
        DefinitionAsset def = definitionAssetRepository.findById(definitionId)
                .orElseThrow(() -> new java.util.NoSuchElementException("definition " + definitionId));

        List<PinnedCase> pinnedCases = pinnedActiveCases(def);
        List<DependentDefinition> dependents = dependentDefinitions(def);
        List<SubscriptionCopy> copies = subscriptionCopies(definitionId);

        return new ImpactReport(definitionId, def.getType(), def.getKey(), def.getVersion(),
                pinnedCases, dependents, copies);
    }

    /** Exact-pin JSONB array containment over the GIN-indexed snapshot entries (V25, SC-005). */
    private List<PinnedCase> pinnedActiveCases(DefinitionAsset def) {
        String pinPredicate = "[{\"type\":\"" + def.getType() + "\",\"key\":\"" + def.getKey()
                + "\",\"version\":\"" + def.getVersion() + "\"}]";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ci.id AS case_id, ci.status FROM case_definition_snapshot cds "
                        + "JOIN case_instance ci ON ci.id = cds.case_instance_id "
                        + "WHERE cds.entries @> ?::jsonb AND ci.status NOT IN ('Delivered','Cancelled')",
                pinPredicate);
        return rows.stream()
                .map(r -> new PinnedCase((UUID) r.get("case_id"), (String) r.get("status")))
                .toList();
    }

    /** Other Published definitions whose {@code dependsOn} list names this {@code type:key}. */
    private List<DependentDefinition> dependentDefinitions(DefinitionAsset def) {
        String needle = "\"" + def.getType() + ":" + def.getKey() + "\"";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, type, key, version FROM definition_asset "
                        + "WHERE status = 'Published' AND id <> ? AND body::text LIKE ?",
                def.getId(), "%" + needle + "%");
        return rows.stream()
                .map(r -> new DependentDefinition((UUID) r.get("id"), (String) r.get("type"),
                        (String) r.get("key"), (String) r.get("version")))
                .toList();
    }

    private List<SubscriptionCopy> subscriptionCopies(UUID definitionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT workspace_id, copied_definition_id, created_at FROM library_subscription "
                        + "WHERE source_definition_id = ?", definitionId);
        return rows.stream()
                .map(r -> new SubscriptionCopy((UUID) r.get("workspace_id"), (UUID) r.get("copied_definition_id"),
                        ((java.sql.Timestamp) r.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC)))
                .toList();
    }
}
