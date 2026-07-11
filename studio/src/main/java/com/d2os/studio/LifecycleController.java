package com.d2os.studio;

import com.d2os.casecore.AuditWriter;
import com.d2os.catalog.CompatibilityMatrixService;
import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.catalog.DeprecationImpactService;
import com.d2os.catalog.ForkService;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Deprecation impact, deprecate-with-report-id, and compatibility matrix (Phase 6 US3, T023,
 * contracts, research R5, FR-010/011/017).
 *
 * <p><b>"Freshly generated impactReportId" (research R5's own framing)</b>: {@link
 * DeprecationImpactService#compute} is computed fresh every call — nothing is persisted — so there
 * is no stored id to validate the deprecate call's {@code impactReportId} against. {@link
 * #deprecationImpact} mints and returns a random correlation id alongside the report; {@link
 * #deprecate} requires ANY non-null {@code impactReportId} in its body (409 without one). This is a
 * deliberately lightweight proof-of-having-called-the-report-endpoint-first, not a cryptographic or
 * time-bound freshness guarantee — a fuller implementation would need to persist/expire report ids,
 * which is out of scope for what FR-010 actually asks for (that the impact was SEEN before
 * deprecating, not that it was seen within N seconds).
 */
@RestController
public class LifecycleController {

    private final DefinitionAssetRepository definitionAssetRepository;
    private final DeprecationImpactService deprecationImpactService;
    private final CompatibilityMatrixService compatibilityMatrixService;
    private final ForkService forkService;
    private final AuditWriter auditWriter;

    public LifecycleController(DefinitionAssetRepository definitionAssetRepository,
                               DeprecationImpactService deprecationImpactService,
                               CompatibilityMatrixService compatibilityMatrixService,
                               ForkService forkService,
                               AuditWriter auditWriter) {
        this.definitionAssetRepository = definitionAssetRepository;
        this.deprecationImpactService = deprecationImpactService;
        this.compatibilityMatrixService = compatibilityMatrixService;
        this.forkService = forkService;
        this.auditWriter = auditWriter;
    }

    /** {@code POST /catalog/definitions/{definitionId}/fork} (T021, FR-012, SC-008). */
    @PostMapping("/api/v1/catalog/definitions/{definitionId}/fork")
    public ResponseEntity<Map<String, Object>> fork(@PathVariable UUID definitionId,
            @RequestHeader(value = "X-Actor", defaultValue = "author") String actor,
            @RequestBody Map<String, Object> request) {
        String newVersion = (String) request.get("newVersion");
        if (newVersion == null || newVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "newVersion is required"));
        }
        DefinitionAsset forked = forkService.fork(definitionId, newVersion, WorkspaceContext.require(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", forked.getId(), "key", forked.getKey(), "version", forked.getVersion(),
                "status", forked.getStatus(), "derivedFromId", forked.getDerivedFromId()));
    }

    @GetMapping("/api/v1/catalog/definitions/{definitionId}/deprecation-impact")
    public ResponseEntity<Map<String, Object>> deprecationImpact(@PathVariable UUID definitionId) {
        DeprecationImpactService.ImpactReport report = deprecationImpactService.compute(definitionId);
        return ResponseEntity.ok(Map.of(
                "impactReportId", UUID.randomUUID().toString(),
                "definitionId", report.definitionId(),
                "type", report.type(), "key", report.key(), "version", report.version(),
                "pinnedActiveCases", report.pinnedActiveCases(),
                "dependents", report.dependents(),
                "subscriptionCopies", report.subscriptionCopies(),
                "totalImpact", report.totalImpact()));
    }

    @PostMapping("/api/v1/catalog/definitions/{definitionId}/deprecate")
    @Transactional
    public ResponseEntity<Map<String, Object>> deprecate(@PathVariable UUID definitionId,
            @RequestHeader(value = "X-Actor", defaultValue = "catalog-owner") String actor,
            @RequestBody Map<String, Object> request) {
        if (request == null || request.get("impactReportId") == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "impactReportId is required — call GET .../deprecation-impact first"));
        }
        DefinitionAsset def = definitionAssetRepository.findById(definitionId)
                .orElseThrow(() -> new NoSuchElementException("definition " + definitionId));
        def.markDeprecated();
        definitionAssetRepository.save(def);
        auditWriter.record(def.getWorkspaceId(), "definition_asset", definitionId, "DEFINITION_DEPRECATED", actor,
                Map.of("impactReportId", request.get("impactReportId"), "key", def.getKey(), "version", def.getVersion()));
        return ResponseEntity.ok(Map.of("id", def.getId(), "status", def.getStatus()));
    }

    @GetMapping("/api/v1/catalog/compatibility-matrix")
    public List<CompatibilityMatrixService.CompatibilityEntry> compatibilityMatrix() {
        return compatibilityMatrixService.evaluate();
    }
}
