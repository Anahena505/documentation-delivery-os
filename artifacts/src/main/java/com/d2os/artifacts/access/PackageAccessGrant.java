package com.d2os.artifacts.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A role's access grant on a delivered package (V22 {@code package_access_grant}, Phase 7 US5, T040,
 * research R6, T3-d). Default-deny beyond active (non-revoked) grants — {@link
 * PackageAccessService#checkAccess} is the sole enforcement point.
 */
@Entity
@Table(name = "package_access_grant")
public class PackageAccessGrant {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(nullable = false)
    private String role;

    @Column(name = "granted_by", nullable = false)
    private String grantedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected PackageAccessGrant() {}

    public PackageAccessGrant(UUID id, UUID workspaceId, UUID packageId, String role, String grantedBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.packageId = packageId;
        this.role = role;
        this.grantedBy = grantedBy;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getPackageId() { return packageId; }
    public String getRole() { return role; }
    public String getGrantedBy() { return grantedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
}
