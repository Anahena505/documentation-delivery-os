package com.d2os.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * A Feature — the aggregate a mutating Case attaches to. {@code aggVersion} is the
 * optimistic-concurrency token (FR-016); the one-active-mutating-Case invariant is additionally
 * enforced by a partial unique index at the DB layer.
 */
@Entity
@Table(name = "feature")
public class Feature {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "project_version_id", nullable = false)
    private UUID projectVersionId;

    @Column(nullable = false)
    private String name;

    @Version
    @Column(name = "agg_version", nullable = false)
    private long aggVersion;

    protected Feature() {}

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public long getAggVersion() { return aggVersion; }
}
