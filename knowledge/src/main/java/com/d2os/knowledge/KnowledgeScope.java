package com.d2os.knowledge;

/**
 * The reach of a {@code knowledge_item} (V13 {@code scope_level}, research R4). {@code WORKSPACE} items
 * are visible to every case in the workspace; {@code PROJECT} items only when the retrieval query carries
 * the matching {@code scope_ref} project id; {@code GLOBAL} is reserved and structurally unreachable in v1.
 */
public enum KnowledgeScope {
    WORKSPACE,
    PROJECT,
    GLOBAL
}
