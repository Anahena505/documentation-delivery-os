package com.d2os.knowledge;

import com.d2os.persona.gateway.AiGatewayClient;
import com.d2os.persona.gateway.EmbedRequest;
import com.d2os.persona.gateway.EmbedResult;
import com.d2os.persona.spi.KnowledgeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Open Host Service for governed-knowledge retrieval (research R10, FR-002/FR-003): the {@code
 * knowledge} module's implementation of the persona-owned {@link KnowledgeProvider} SPI. Wired to
 * the port purely by component scan (T012) — {@code D2osApplication} scans {@code com.d2os}, so
 * this {@code @Service} is discovered and injected wherever {@code KnowledgeProvider} is required,
 * with no bootstrap edit and no persona → knowledge dependency (the arrow stays persona ←
 * knowledge).
 *
 * <p>Retrieval uses {@code JdbcTemplate} rather than JPA because the ranking needs pgvector's
 * {@code <=>} cosine-distance operator, which JPA cannot express. The query always carries the
 * workspace_id predicate so the partitioned {@code knowledge_item} prunes to a single partition
 * (T2-b), making a cross-workspace ANN scan structurally impossible.
 *
 * <p>Budget: retrieval is expected to complete within the {@code
 * d2os.knowledge.retrieval-budget-ms} (default 500ms, p95) latency budget so injected knowledge
 * adds no user-visible latency (research R10).
 *
 * <p>Deprecation (US3, T029, FR-014): the {@code status = 'PUBLISHED'} predicate below drops {@code
 * DEPRECATED} items out of every NEW-operation retrieval the moment they are retired. In-flight
 * envelopes are unaffected — their injection snapshots are decoupled soft references pinned to the
 * exact {@code (key, version)}, so already-snapshotted knowledge still replays byte-identically
 * (FR-016).
 *
 * <p>Embed-model isolation (research R3, re-index contract): cosine distance is only meaningful
 * between vectors from the SAME embedding model. The query embeds the operation context through the
 * gateway and ranks only items whose {@code embed_model} equals the model that just produced the
 * query vector — an {@code embed_model = ?} predicate. After a model/dimension swap, items embedded
 * under the prior model fall out of the entitled set (retrieval returns fewer/zero items — a
 * visible, safe signal to re-index) instead of being mis-ranked against a foreign-space query
 * vector. No in-place re-embed job ships in v1.
 */
@Service
public class KnowledgeRetrievalService implements KnowledgeProvider {

  // projectId is bound as text and cast (?::uuid) at both sites so a null binds cleanly — an
  // untyped
  // JDBC null in "? IS NOT NULL" would otherwise make Postgres unable to infer the parameter type.
  // embed_model pins ranking to the current model's vector space (research R3 re-index contract).
  private static final String RETRIEVAL_SQL =
      """
            SELECT id, workspace_id, key, version, content, content_hash
              FROM knowledge_item
             WHERE workspace_id = ?
               AND status = 'PUBLISHED'
               AND embed_model = ?
               AND (scope_level = 'WORKSPACE'
                    OR (scope_level = 'PROJECT' AND ?::uuid IS NOT NULL AND scope_ref = ?::uuid))
               AND tags && ?::text[]
             ORDER BY embedding <=> ?::vector
             LIMIT ?
            """;

  private final JdbcTemplate jdbcTemplate;
  private final AiGatewayClient aiGatewayClient;

  public KnowledgeRetrievalService(JdbcTemplate jdbcTemplate, AiGatewayClient aiGatewayClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.aiGatewayClient = aiGatewayClient;
  }

  @Override
  public List<KnowledgeProvider.InjectedItem> retrieve(KnowledgeProvider.KnowledgeQuery query) {
    // Entitlement gate: a persona with no knowledge profile is entitled to nothing (no injection).
    if (query.personaKnowledgeProfile() == null || query.personaKnowledgeProfile().isEmpty()) {
      return List.of();
    }

    // Context string to embed: operation tags + persona profile (dedup order not significant — the
    // embedding is a single ranking anchor). Falls back to the profile alone if no operation tags.
    List<String> profileTags = query.personaKnowledgeProfile();
    List<String> contextTerms = new ArrayList<>();
    if (query.operationTags() != null) contextTerms.addAll(query.operationTags());
    contextTerms.addAll(profileTags);
    String contextString = String.join(" ", contextTerms);

    EmbedResult embedded = aiGatewayClient.embed(new EmbedRequest(contextString));
    // The model string that produced this query vector, in the exact shape EmbeddingIndexer stored
    // on
    // each row ("modelId:modelVersion") — so the embed_model predicate ranks only same-space
    // vectors.
    String queryEmbedModel = embedded.modelId() + ":" + embedded.modelVersion();

    String vectorLiteral = PgLiterals.vector(embedded.vector());
    String tagsLiteral = PgLiterals.textArray(profileTags);
    String projectIdLiteral = query.projectId() == null ? null : query.projectId().toString();

    return jdbcTemplate.query(
        RETRIEVAL_SQL,
        (rs, rowNum) ->
            new KnowledgeProvider.InjectedItem(
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getString("key"),
                rs.getInt("version"),
                rs.getString("content"),
                rs.getString("content_hash")),
        query.workspaceId(),
        queryEmbedModel,
        projectIdLiteral,
        projectIdLiteral,
        tagsLiteral,
        vectorLiteral,
        query.maxItems());
  }
}
