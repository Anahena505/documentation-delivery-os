package com.d2os.knowledge;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Seeds an initial governed KnowledgeItem set on startup, with provenance discipline (T025/T038,
 * FR-021, Principle I). Lives in the {@code knowledge} module (not {@code CatalogSeedLoader}) because
 * publishing an item goes through {@link EmbeddingIndexer} — and {@code catalog} cannot depend on
 * {@code knowledge} (the dependency is one-way: knowledge → catalog).
 *
 * <p>Items seed into the reserved system-global workspace, whose {@code knowledge_item} partition V13
 * already created — so no partition provisioning is needed here. Idempotent by {@code (key, version)}:
 * re-running the app must not duplicate a seed item (the V13 {@code uq_knowledge_item_key_version} would
 * otherwise reject it). Runs after {@link com.d2os.catalog.CatalogSeedLoader} (default order) since it
 * needs no catalog rows, but ordered late defensively.
 */
@Component
@Order(100)
public class KnowledgeSeedLoader implements ApplicationRunner {

    private static final UUID SYSTEM_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * Stable id of the seed "demo project" scope target (T038). {@code knowledge_item.scope_ref} is a
     * soft reference (no FK, V13), so this project-scoped fixture is publishable at bootstrap before any
     * real project exists; it is only ever retrieved by a query that passes exactly this projectId —
     * which also makes it a standing fixture proving project-scope filtering (a query with a different
     * or null projectId must never see it).
     */
    public static final UUID DEMO_PROJECT_REF = UUID.fromString("00000000-0000-0000-0000-00000000d000");

    /** One seed item: stable key + human title + injectable content + retrieval tags + scope + status. */
    private record Seed(String key, String title, String content, List<String> tags,
                        KnowledgeScope scope, UUID scopeRef, boolean deprecated) {}

    // The initial governed set (T038, FR-021, quickstart scenario 1): workspace- and project-scoped
    // items, matching ('security'/'governance'/'documentation') and non-matching ('ux') tags, plus a
    // DEPRECATED item — so the retrieval fixtures (status/tag/scope predicates) all have a standing
    // counter-example from first boot.
    private static final List<Seed> SEEDS = List.of(
            new Seed("global-security-baseline", "Security baseline",
                    "Encrypt data at rest and in transit; rotate credentials on a fixed schedule; never "
                            + "embed secrets in artifacts.",
                    List.of("security", "governance"),
                    KnowledgeScope.WORKSPACE, SYSTEM_WORKSPACE, false),
            new Seed("global-doc-standard", "Documentation standard",
                    "Every artifact declares an index block of the ids it defines and references so the "
                            + "consistency checker can cross-validate the package.",
                    List.of("documentation", "governance"),
                    KnowledgeScope.WORKSPACE, SYSTEM_WORKSPACE, false),
            // Non-matching tag: no seeded persona profile carries 'ux', so retrieval must skip it.
            new Seed("global-ux-guideline", "UX writing guideline",
                    "Prefer progressive disclosure for complex forms; lead with the user's goal.",
                    List.of("ux"),
                    KnowledgeScope.WORKSPACE, SYSTEM_WORKSPACE, false),
            // Project-scoped: retrievable only by a query carrying DEMO_PROJECT_REF as its projectId.
            new Seed("demo-project-convention", "Demo project convention",
                    "This demo project pins its API style to REST level 2 and dates to RFC 3339.",
                    List.of("governance"),
                    KnowledgeScope.PROJECT, DEMO_PROJECT_REF, false),
            // Deprecated from birth: the standing status-predicate fixture — never retrievable, but
            // present so listings/inspection show a DEPRECATED item without manual setup.
            new Seed("global-legacy-standard", "Legacy documentation standard",
                    "Superseded guidance retained for audit: artifacts used to inline their index ids.",
                    List.of("documentation", "governance"),
                    KnowledgeScope.WORKSPACE, SYSTEM_WORKSPACE, true));

    private final EmbeddingIndexer embeddingIndexer;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeSeedLoader(EmbeddingIndexer embeddingIndexer, JdbcTemplate jdbcTemplate) {
        this.embeddingIndexer = embeddingIndexer;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Seed seed : SEEDS) {
            // Idempotent by (workspace, key, version): skip if the seed version already exists. The seed
            // reads/writes as the system workspace (the WorkspaceAwareDataSource stamps the nil system
            // workspace when no request context is bound — exactly this startup-job case).
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM knowledge_item WHERE workspace_id = ? AND key = ? AND version = 1",
                    Integer.class, SYSTEM_WORKSPACE, seed.key());
            if (existing != null && existing > 0) {
                continue;
            }
            UUID itemId = embeddingIndexer.publish(
                    SYSTEM_WORKSPACE, UUID.randomUUID(), seed.key(), 1,
                    seed.scope(), seed.scopeRef(), seed.tags(), "en",
                    seed.title(), seed.content(),
                    null,   // source_candidate_id: bootstrap seed has no capture provenance
                    null);
            if (seed.deprecated()) {
                // The status fixture: published then immediately retired, same transaction — matching
                // the real lifecycle (an item is only ever DEPRECATED from PUBLISHED, never born so).
                jdbcTemplate.update(
                        "UPDATE knowledge_item SET status = 'DEPRECATED', "
                                + "deprecation_reason = 'seed fixture: superseded', deprecated_at = now() "
                                + "WHERE workspace_id = ? AND id = ?",
                        SYSTEM_WORKSPACE, itemId);
            }
        }
    }
}
