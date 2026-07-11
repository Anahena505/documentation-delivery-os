package com.d2os.knowledge;

import com.d2os.persona.HashUtil;
import com.d2os.persona.gateway.AiGatewayClient;
import com.d2os.persona.gateway.EmbedRequest;
import com.d2os.persona.gateway.EmbedResult;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the full-row {@code knowledge_item} INSERT (FR-004, research R3). Because {@code embedding}
 * is {@code NOT NULL} and pgvector's {@code vector(384)} type does not map cleanly to JPA, this
 * class writes the row via {@code JdbcTemplate} (not the JPA repository) so it can bind the vector
 * + tag-array literals.
 *
 * <p>US2's promotion pipeline calls {@link #publish} to publish an approved candidate; US1
 * integration tests call it directly to seed items. The embedding is produced through the AI
 * Gateway (the sole provider choke point, AS-5) and its model identity/version is recorded in
 * {@code embed_model} (Principle II).
 */
@Service
public class EmbeddingIndexer {

  private static final String INSERT_SQL =
      """
            INSERT INTO knowledge_item(
                id, workspace_id, key, version, scope_level, scope_ref, tags, locale, title, content,
                content_hash, embedding, embed_model, source_candidate_id, supersedes_version, status)
            VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?::vector, ?, ?, ?, 'PUBLISHED')
            """;

  private final JdbcTemplate jdbcTemplate;
  private final AiGatewayClient aiGatewayClient;

  public EmbeddingIndexer(JdbcTemplate jdbcTemplate, AiGatewayClient aiGatewayClient) {
    this.jdbcTemplate = jdbcTemplate;
    this.aiGatewayClient = aiGatewayClient;
  }

  /**
   * Publish one KnowledgeItem version: hash the content, embed it through the gateway, and insert
   * the full row (status PUBLISHED). Returns the new item id. The write runs in the caller's
   * transaction so it participates in the same RLS/workspace context.
   */
  @Transactional
  public UUID publish(
      UUID workspaceId,
      UUID id,
      String key,
      int version,
      KnowledgeScope scopeLevel,
      UUID scopeRef,
      List<String> tags,
      String locale,
      String title,
      String content,
      UUID sourceCandidateId,
      Integer supersedesVersion) {
    String contentHash = HashUtil.sha256Hex(content);
    EmbedResult embedded = aiGatewayClient.embed(new EmbedRequest(content));

    String vectorLiteral = PgLiterals.vector(embedded.vector());
    String tagsLiteral = PgLiterals.textArray(tags == null ? List.of() : tags);
    String embedModel = embedded.modelId() + ":" + embedded.modelVersion();

    jdbcTemplate.update(
        INSERT_SQL,
        id,
        workspaceId,
        key,
        version,
        scopeLevel.name(),
        scopeRef,
        tagsLiteral,
        locale,
        title,
        content,
        contentHash,
        vectorLiteral,
        embedModel,
        sourceCandidateId,
        supersedesVersion);

    return id;
  }
}
