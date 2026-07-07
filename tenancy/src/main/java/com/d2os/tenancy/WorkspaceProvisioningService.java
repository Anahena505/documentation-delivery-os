package com.d2os.tenancy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Provisions a workspace and, in the SAME transaction, its {@code knowledge_item} LIST partition + HNSW
 * index (Phase 3, T008, research R2, T2-b) — so knowledge retrieval for the new workspace is structurally
 * confined to its own partition from the very first insert (partition pruning on the mandatory
 * {@code workspace_id} predicate), not merely filtered by RLS after the fact.
 *
 * <p>The partition is created through the {@code create_knowledge_item_partition(uuid)} SECURITY DEFINER
 * function (V13): the least-privilege {@code d2os_app} role does not own {@code knowledge_item} and so
 * cannot {@code CREATE ... PARTITION OF} it directly; the definer function (owned by the schema owner)
 * closes that gap while the app role stays least-privilege.
 *
 * <p>Workspace writes are admin-only (V2 — the {@code workspace} table has no RLS INSERT policy for
 * {@code d2os_app}); {@link #provisionWorkspace} therefore runs in a provisioning/admin-capable context.
 * {@link #ensureKnowledgePartition} is safe to call from any context (the definer function is idempotent)
 * and is the hook to invoke after a workspace is created by other means.
 */
@Service
public class WorkspaceProvisioningService {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceProvisioningService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Create the workspace row and its knowledge partition atomically; returns the workspace id.
     * Idempotent end to end: the row insert is {@code ON CONFLICT DO NOTHING} and the partition function
     * is a no-op when the partition exists — re-provisioning an existing workspace is safe (and exercised
     * by the IT suites, which re-provision their static workspace before every test).
     */
    @Transactional
    public UUID provisionWorkspace(UUID id, String name, String createdBy) {
        jdbcTemplate.update(
                "INSERT INTO workspace (id, name, status, created_by) VALUES (?, ?, 'active', ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                id, name, createdBy);
        ensureKnowledgePartition(id);
        return id;
    }

    /**
     * Idempotent hook: ensure the workspace's {@code knowledge_item} partition (+ HNSW index) exists.
     * Returns {@code true} when this call created it, {@code false} when it was already present.
     */
    @Transactional
    public boolean ensureKnowledgePartition(UUID workspaceId) {
        Boolean created = jdbcTemplate.queryForObject(
                "SELECT create_knowledge_item_partition(?)", Boolean.class, workspaceId);
        return Boolean.TRUE.equals(created);
    }
}
