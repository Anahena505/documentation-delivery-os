package com.d2os.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The immutable, versioned unit of governed knowledge (FR-001, data-model.md, V13). One row per
 * {@code (key, version)}; a new revision is a NEW row, never an in-place edit — so a snapshot that
 * pinned an older version stays byte-identical for replay (FR-007). Deprecation flips {@code status}
 * to {@code DEPRECATED} (append-only for DELETE; see V13 REVOKE) but never rewrites the content.
 *
 * <p>Every V13 column is mapped here EXCEPT {@code embedding}: pgvector's {@code vector(384)} type does
 * not map cleanly to JPA, so embeddings are written/queried exclusively through {@code JdbcTemplate}
 * ({@link EmbeddingIndexer} for the full-row INSERT, {@link KnowledgeRetrievalService} for the ANN
 * query). This entity is therefore read-focused; the only mutator is {@link #deprecate(String)}
 * (reserved for US3), keeping the item effectively immutable for US1's retrieval + injection path.
 */
@Entity
@Table(name = "knowledge_item")
public class KnowledgeItem {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_level", nullable = false)
    private KnowledgeScope scopeLevel;

    @Column(name = "scope_ref", nullable = false)
    private UUID scopeRef;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] tags;

    @Column(nullable = false)
    private String locale;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String content;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "embed_model", nullable = false)
    private String embedModel;

    @Column(nullable = false)
    private String status = "PUBLISHED";

    @Column(name = "source_candidate_id")
    private UUID sourceCandidateId;

    @Column(name = "supersedes_version")
    private Integer supersedesVersion;

    @Column(name = "deprecation_reason")
    private String deprecationReason;

    @Column(name = "deprecated_at")
    private OffsetDateTime deprecatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    protected KnowledgeItem() {}

    /**
     * Full constructor mirroring the V13 column set (minus {@code embedding}, which is JdbcTemplate-only).
     * Items are constructed already-published; deprecation is the sole later transition ({@link #deprecate}).
     */
    public KnowledgeItem(UUID id, UUID workspaceId, String key, int version,
                         KnowledgeScope scopeLevel, UUID scopeRef, String[] tags, String locale,
                         String title, String content, String contentHash, String embedModel,
                         UUID sourceCandidateId, Integer supersedesVersion) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.key = key;
        this.version = version;
        this.scopeLevel = scopeLevel;
        this.scopeRef = scopeRef;
        this.tags = tags;
        this.locale = locale;
        this.title = title;
        this.content = content;
        this.contentHash = contentHash;
        this.embedModel = embedModel;
        this.status = "PUBLISHED";
        this.sourceCandidateId = sourceCandidateId;
        this.supersedesVersion = supersedesVersion;
    }

    /**
     * The one permitted mutation (US3): retire this item version. Content/hash are never touched, so any
     * snapshot that pinned it still replays byte-identically (FR-007). Reserved for US3 — US1 never calls it.
     */
    public void deprecate(String reason) {
        this.status = "DEPRECATED";
        this.deprecationReason = reason;
        this.deprecatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getKey() { return key; }
    public int getVersion() { return version; }
    public KnowledgeScope getScopeLevel() { return scopeLevel; }
    public UUID getScopeRef() { return scopeRef; }
    public String[] getTags() { return tags; }
    public String getLocale() { return locale; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public String getEmbedModel() { return embedModel; }
    public String getStatus() { return status; }
    public UUID getSourceCandidateId() { return sourceCandidateId; }
    public Integer getSupersedesVersion() { return supersedesVersion; }
    public String getDeprecationReason() { return deprecationReason; }
    public OffsetDateTime getDeprecatedAt() { return deprecatedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
