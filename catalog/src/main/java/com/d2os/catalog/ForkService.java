package com.d2os.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Fork a source definition (any status, including Deprecated) into a new independent {@code Draft}
 * row (Phase 6 US3, T021, research R4, FR-012/017, SC-008). {@code derived_from_id} (V25) records
 * provenance; forking never mutates the source and never installs a runtime override — the result is
 * an ordinary Draft that must go through the normal submit-review/publish flow (Phase 6 US2) like any
 * other authored content before anything can ever resolve it.
 */
@Service
public class ForkService {

    private final DefinitionAssetRepository repository;
    private final CatalogAuditWriter auditWriter;

    public ForkService(DefinitionAssetRepository repository, CatalogAuditWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public DefinitionAsset fork(UUID sourceId, String newVersion, UUID workspaceId, String actor) {
        DefinitionAsset source = repository.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("definition " + sourceId));

        DefinitionAsset forked = new DefinitionAsset(UUID.randomUUID(), workspaceId, source.getKey(),
                newVersion, source.getType(), "en", source.getBody(), actor);
        forked.recordForkProvenance(sourceId);
        repository.save(forked);

        auditWriter.record(workspaceId, "definition_asset", forked.getId(), "DEFINITION_FORKED", actor,
                Map.of("sourceDefinitionId", sourceId.toString(), "sourceStatus", source.getStatus(),
                        "key", source.getKey(), "newVersion", newVersion));
        return forked;
    }
}
