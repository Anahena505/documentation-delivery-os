package com.d2os.projection.api;

import com.d2os.projection.GraphNode;
import com.d2os.projection.GraphNodeRepository;
import com.d2os.projection.influence.InfluenceAnalyticsService;
import com.d2os.projection.influence.InfluenceAnalyticsService.InfluenceEntry;
import com.d2os.projection.influence.InfluenceAnalyticsService.Reading;
import com.d2os.projection.query.TraceabilityQueryService;
import com.d2os.projection.query.TraceabilityQueryService.GraphNodeView;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T027 &mdash; {@code GET /graph/influence} (contracts/api.yaml, FR-010, US4). Resolves {@link
 * InfluenceAnalyticsService}'s operation/artifact id lists into full {@link GraphNodeView}s (the
 * contract's {@code touchedOperations}/{@code touchedArtifacts} are {@code GraphNode} arrays, not
 * bare ids), reusing {@link TraceabilityQueryService#toView} &mdash; same reuse convention {@link
 * CycleController} already follows.
 */
@RestController
@RequestMapping("/api/v1/graph")
public class InfluenceController {

    private final InfluenceAnalyticsService influenceAnalyticsService;
    private final GraphNodeRepository graphNodeRepository;
    private final TraceabilityQueryService traceabilityQueryService;

    public InfluenceController(InfluenceAnalyticsService influenceAnalyticsService,
                               GraphNodeRepository graphNodeRepository,
                               TraceabilityQueryService traceabilityQueryService) {
        this.influenceAnalyticsService = influenceAnalyticsService;
        this.graphNodeRepository = graphNodeRepository;
        this.traceabilityQueryService = traceabilityQueryService;
    }

    public record InfluenceEntryView(String knowledgeKey, int knowledgeVersion, String state,
                                     List<Reading> readings, List<GraphNodeView> touchedOperations,
                                     List<GraphNodeView> touchedArtifacts) {}

    @GetMapping("/influence")
    public ResponseEntity<List<InfluenceEntryView>> influence(
            @RequestParam(required = false) String knowledgeKey,
            @RequestParam(required = false) UUID caseId) {
        UUID workspaceId = WorkspaceContext.require();
        List<InfluenceEntry> entries = influenceAnalyticsService.analyze(workspaceId, knowledgeKey, caseId);

        List<InfluenceEntryView> views = new ArrayList<>();
        for (InfluenceEntry entry : entries) {
            views.add(new InfluenceEntryView(entry.knowledgeKey(), entry.knowledgeVersion(),
                    entry.state().name(), entry.readings(),
                    resolveNodes(workspaceId, entry.touchedOperations()),
                    resolveNodes(workspaceId, entry.touchedArtifacts())));
        }
        return ResponseEntity.ok(views);
    }

    private List<GraphNodeView> resolveNodes(UUID workspaceId, List<UUID> nodeIds) {
        List<GraphNodeView> views = new ArrayList<>();
        for (UUID nodeId : nodeIds) {
            GraphNode node = graphNodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId).orElse(null);
            if (node != null) {
                views.add(traceabilityQueryService.toView(node));
            }
        }
        return views;
    }
}
