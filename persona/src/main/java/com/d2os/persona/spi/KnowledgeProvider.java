package com.d2os.persona.spi;

import java.util.List;
import java.util.UUID;

/**
 * Port letting persona retrieve governed knowledge for injection WITHOUT depending on the knowledge
 * module (dependency inversion — research R1). The {@code knowledge} module implements this; {@code app}
 * wires the implementation to this port (T012). Keeping the interface here is what keeps the module
 * graph acyclic: persona → (this SPI) ← knowledge, never persona → knowledge (enforced by ArchUnit, T039).
 */
public interface KnowledgeProvider {

    /**
     * Resolve the KnowledgeItems a persona operation is entitled to, ranked for injection (research R10).
     * Returns at most {@code query.maxItems()} items, already scope/tag/profile filtered and
     * workspace-isolated by the implementation.
     */
    List<InjectedItem> retrieve(KnowledgeQuery query);

    /** Everything the retrieval predicate needs (workspace + project scope, tags, persona profile, cap). */
    record KnowledgeQuery(
            UUID workspaceId,
            UUID projectId,
            List<String> operationTags,
            List<String> personaKnowledgeProfile,
            int maxItems) {
    }

    /**
     * One resolved item to inject. Carries its owning {@code workspaceId} (so the injection-seam guard
     * {@code WorkspaceScopeGuard} — T015 — can assert no cross-workspace item ever reaches a persona)
     * plus the exact {@code (itemId, key, version)} + {@code contentHash} so {@code
     * OperationExecutionRecorder} (T014) can write an exact, replayable injection snapshot.
     */
    record InjectedItem(
            UUID workspaceId,
            UUID itemId,
            String key,
            int version,
            String content,
            String contentHash) {
    }
}
