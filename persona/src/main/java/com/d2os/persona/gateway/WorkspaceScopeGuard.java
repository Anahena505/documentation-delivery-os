package com.d2os.persona.gateway;

import com.d2os.casecore.AuditWriter;
import com.d2os.persona.spi.KnowledgeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Injection-seam defense-in-depth (T015, T2-c): asserts that every KnowledgeItem about to be injected
 * into a persona operation belongs to the executing case's workspace. This is independent of the
 * retrieval filter (which already scopes by {@code workspace_id} and prunes to one partition) — if a
 * future retrieval bug ever leaked a foreign item, this seam refuses to render it.
 *
 * <p>It lives in the gateway package because the raw {@link AiGatewayClient#call} only ever sees a
 * rendered String — by then workspace provenance is lost. The assertion therefore runs one step earlier,
 * at the point where structured {@link KnowledgeProvider.InjectedItem}s are assembled into the envelope
 * ({@code ExecutionEnvelopeBuilder}, T013), which is the last place provenance is still available.
 *
 * <p>On violation it refuses (throws, fail closed) AND audits (Phase 3 Polish, T036): an ERROR log plus
 * a durable {@code AuditEntry} written via {@link AuditWriter#recordDetached} in its own transaction —
 * REQUIRES_NEW, because the throw aborts the caller's transaction and a same-transaction audit row
 * would be rolled back along with it. The writer is an {@link ObjectProvider} so persona-only slice
 * tests (no casecore beans on the path) still wire this guard; there the ERROR log remains the signal.
 */
@Component
public class WorkspaceScopeGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopeGuard.class);

    private final ObjectProvider<AuditWriter> auditWriter;

    public WorkspaceScopeGuard(ObjectProvider<AuditWriter> auditWriter) {
        this.auditWriter = auditWriter;
    }

    /**
     * Verify every item's owning workspace equals {@code callerWorkspaceId}. Throws
     * {@link KnowledgeScopeViolationException} on the first mismatch (fail closed), after durably
     * auditing the blocked attempt.
     */
    public void assertSameWorkspace(UUID callerWorkspaceId, List<KnowledgeProvider.InjectedItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (KnowledgeProvider.InjectedItem item : items) {
            if (!callerWorkspaceId.equals(item.workspaceId())) {
                log.error("Cross-workspace knowledge injection blocked: callerWorkspace={} itemWorkspace={} "
                                + "itemId={} key={} v{}",
                        callerWorkspaceId, item.workspaceId(), item.itemId(), item.key(), item.version());
                audit(callerWorkspaceId, item);
                throw new KnowledgeScopeViolationException(
                        "KnowledgeItem " + item.itemId() + " (key=" + item.key() + ") belongs to workspace "
                                + item.workspaceId() + ", not the executing case's workspace " + callerWorkspaceId);
            }
        }
    }

    /**
     * Durable audit of the blocked attempt (T2-c), committed independently of the aborting caller.
     * Best-effort by design: an audit failure must not mask the security refusal about to be thrown.
     */
    private void audit(UUID callerWorkspaceId, KnowledgeProvider.InjectedItem item) {
        AuditWriter writer = auditWriter.getIfAvailable();
        if (writer == null) {
            return;   // slice test without casecore — the ERROR log above is the signal
        }
        try {
            writer.recordDetached(callerWorkspaceId, "knowledge_item", item.itemId(),
                    "SCOPE_VIOLATION_BLOCKED", "system:scope-guard",
                    Map.of("itemWorkspaceId", item.workspaceId().toString(),
                            "key", item.key(), "version", item.version()));
        } catch (Exception e) {
            log.error("Failed to write scope-violation audit entry (refusal still enforced)", e);
        }
    }
}
