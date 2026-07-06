package com.d2os.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The DefinitionAsset supertype (Principle I). Read-focused JPA mapping; publish/immutability is
 * enforced by the DB trigger (V3). Authoring/publish writes go through {@link DefinitionPublishService}.
 */
@Entity
@Table(name = "definition_asset")
public class DefinitionAsset {

    public enum Status { Draft, Published, Deprecated }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body;

    @Column
    private String checksum;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected DefinitionAsset() {}

    public DefinitionAsset(UUID id, UUID workspaceId, String key, String version, String type,
                           String locale, String body, String createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.key = key;
        this.version = version;
        this.type = type;
        this.status = Status.Draft.name();
        this.locale = locale;
        this.body = body;
        this.createdBy = createdBy;
    }

    /** Publish this definition, computing its checksum (T012, T4-a). Only legal from Draft. */
    public void markPublished(String checksum) {
        this.checksum = checksum;
        this.status = Status.Published.name();
        this.publishedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getVersion() { return version; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getBody() { return body; }
    public String getChecksum() { return checksum; }
}
