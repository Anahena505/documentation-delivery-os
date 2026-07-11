package com.d2os.persona.gateway;

import com.d2os.casecore.AuditWriter;
import com.d2os.persona.spi.KnowledgeProvider.InjectedItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 008 T011 — fast unit test of {@link WorkspaceScopeGuard#assertSameWorkspace}. No Spring context, no
 * DB: the {@link AuditWriter} {@link ObjectProvider} is a tiny hand-rolled stub whose
 * {@code getIfAvailable()} returns {@code null}, exercising the guard's "slice test without casecore"
 * path (the ERROR log is the audit signal). This isolates the real security decision — the
 * workspace-equality assertion and fail-closed throw.
 */
class WorkspaceScopeGuardTest {

    /** Minimal ObjectProvider that supplies no AuditWriter (getIfAvailable -> null). */
    private static ObjectProvider<AuditWriter> noAuditWriter() {
        return new ObjectProvider<>() {
            @Override
            public AuditWriter getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AuditWriter getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public AuditWriter getIfAvailable() {
                return null;
            }

            @Override
            public AuditWriter getIfUnique() {
                return null;
            }

            @Override
            public Iterator<AuditWriter> iterator() {
                return List.<AuditWriter>of().iterator();
            }
        };
    }

    private static InjectedItem itemInWorkspace(UUID workspaceId) {
        return new InjectedItem(workspaceId, UUID.randomUUID(), "policy.key", 3,
                "some content", "sha256:deadbeef");
    }

    @Test
    void foreignWorkspaceItem_isRejected() {
        WorkspaceScopeGuard guard = new WorkspaceScopeGuard(noAuditWriter());
        UUID caller = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();

        KnowledgeScopeViolationException ex = assertThrows(KnowledgeScopeViolationException.class,
                () -> guard.assertSameWorkspace(caller, List.of(itemInWorkspace(foreign))));
        // Fail-closed: the message names the offending item's foreign workspace.
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains(foreign.toString()));
    }

    @Test
    void sameWorkspaceItem_passes() {
        WorkspaceScopeGuard guard = new WorkspaceScopeGuard(noAuditWriter());
        UUID caller = UUID.randomUUID();

        assertDoesNotThrow(() -> guard.assertSameWorkspace(caller, List.of(itemInWorkspace(caller))));
    }

    @Test
    void mixedItems_rejectOnFirstForeignItem() {
        WorkspaceScopeGuard guard = new WorkspaceScopeGuard(noAuditWriter());
        UUID caller = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();

        assertThrows(KnowledgeScopeViolationException.class,
                () -> guard.assertSameWorkspace(caller,
                        List.of(itemInWorkspace(caller), itemInWorkspace(foreign))));
    }

    @Test
    void emptyItemList_passes() {
        WorkspaceScopeGuard guard = new WorkspaceScopeGuard(noAuditWriter());
        assertDoesNotThrow(() -> guard.assertSameWorkspace(UUID.randomUUID(), List.of()));
    }
}
