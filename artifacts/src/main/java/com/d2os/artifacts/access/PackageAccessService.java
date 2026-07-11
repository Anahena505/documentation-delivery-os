package com.d2os.artifacts.access;

import com.d2os.casecore.AuditWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Role-scoped, default-deny access to delivered packages (Phase 7 US5, T040, research R6, T3-d,
 * FR-015). Reading a package requires a grant for one of the caller's roles — there is no
 * workspace-wide default; {@link #seedParticipantGrant} seeds exactly one grant at delivery time
 * (the pragmatic {@code reviewer} role every gate-decision endpoint in this codebase already treats
 * as its default caller role, pending a real auth/role model).
 */
@Service
public class PackageAccessService {

    private final PackageAccessGrantRepository grantRepository;
    private final AuditWriter auditWriter;

    public PackageAccessService(PackageAccessGrantRepository grantRepository, AuditWriter auditWriter) {
        this.grantRepository = grantRepository;
        this.auditWriter = auditWriter;
    }

    /** True if any of {@code callerRoles} holds an active grant on {@code packageId}. Default-deny. */
    public boolean checkAccess(UUID packageId, List<String> callerRoles) {
        return grantRepository.findByPackageId(packageId).stream()
                .filter(PackageAccessGrant::isActive)
                .anyMatch(g -> callerRoles.contains(g.getRole()));
    }

    public List<PackageAccessGrant> listGrants(UUID packageId) {
        return grantRepository.findByPackageId(packageId);
    }

    /** Explicit, audited grant (T042, contracts/api.yaml {@code POST /packages/{packageId}/grants}). */
    @Transactional
    public PackageAccessGrant grant(UUID workspaceId, UUID packageId, String role, String grantedBy) {
        PackageAccessGrant grant = new PackageAccessGrant(UUID.randomUUID(), workspaceId, packageId, role, grantedBy);
        grantRepository.save(grant);
        // 008 US5 (T051): a package grant is a trust-sensitive decision (FR-013) — stamp the
        // authenticated granter + the approver role they act under (no-op/NULL in default mode; the
        // system-seeded participant grant runs with no per-user principal, so it stays unstamped).
        auditWriter.recordDecision(workspaceId, "execution_package", packageId, "PACKAGE_ACCESS_GRANTED",
                grantedBy, "approver", Map.of("role", role));
        return grant;
    }

    /** Seeded automatically at delivery (research R6) — nothing workspace-wide, one participant role. */
    @Transactional
    public void seedParticipantGrant(UUID workspaceId, UUID packageId) {
        if (grantRepository.findByPackageIdAndRole(packageId, "reviewer").isPresent()) {
            return;   // idempotent — a re-materialization at package assembly must not duplicate the grant
        }
        grant(workspaceId, packageId, "reviewer", "system:delivery");
    }
}
