package com.d2os.projection.api;

import com.d2os.projection.GraphEdge;
import com.d2os.projection.GraphEdgeRepository;
import com.d2os.projection.GraphNode;
import com.d2os.projection.GraphNodeRepository;
import com.d2os.projection.query.TraceabilityQueryService;
import com.d2os.projection.query.TraceabilityQueryService.Direction;
import com.d2os.projection.query.TraceabilityQueryService.GraphEdgeRefView;
import com.d2os.projection.query.TraceabilityQueryService.GraphNodeView;
import com.d2os.projection.query.TraceabilityQueryService.LineageResult;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T017 &mdash; {@code contracts/api.yaml}'s {@code traceability} tag: {@code GET
 * /graph/traceability} ({@link TraceabilityQueryService}, FR-005/007/013) and {@code GET
 * /graph/nodes/{nodeId}} (single-node inspection with full adjacency + owning-resource link,
 * FR-006, US2 navigability). Same {@code WorkspaceContext.require()} / transparent-RLS-datasource
 * convention as {@link GraphAdminController} and {@link com.d2os.governance.api.GateController}.
 */
@RestController
@RequestMapping("/api/v1/graph")
public class TraceabilityController {

    private final TraceabilityQueryService traceabilityQueryService;
    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;

    public TraceabilityController(TraceabilityQueryService traceabilityQueryService,
                                  GraphNodeRepository graphNodeRepository, GraphEdgeRepository graphEdgeRepository) {
        this.traceabilityQueryService = traceabilityQueryService;
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
    }

    /**
     * {@code GET /graph/traceability?nodeType=&naturalKey=&relation=TRACES_TO|DEPENDS_ON
     * &direction=UPSTREAM|DOWNSTREAM|BOTH&maxDepth=&pageToken=}. 404 when the starting node is
     * absent from this workspace's live graph (including when the workspace has no projected graph
     * at all, or a resumed {@code pageToken} points at a since-purged generation &mdash; see {@link
     * TraceabilityQueryService}'s javadoc).
     */
    @GetMapping("/traceability")
    public ResponseEntity<LineageResult> traceability(@RequestParam String nodeType,
                                                       @RequestParam String naturalKey,
                                                       @RequestParam String relation,
                                                       @RequestParam(defaultValue = "DOWNSTREAM") String direction,
                                                       @RequestParam(defaultValue = "10") int maxDepth,
                                                       @RequestParam(required = false) String pageToken) {
        UUID workspaceId = WorkspaceContext.require();
        Direction dir;
        try {
            dir = Direction.valueOf(direction);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        var result = switch (relation) {
            case "TRACES_TO" -> traceabilityQueryService.tracesTo(workspaceId, nodeType, naturalKey, dir, maxDepth, pageToken);
            case "DEPENDS_ON" -> traceabilityQueryService.dependsOn(workspaceId, nodeType, naturalKey, dir, maxDepth, pageToken);
            default -> null;
        };
        if (result == null) return ResponseEntity.badRequest().build();
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * {@code GET /graph/nodes/{nodeId}} &mdash; attributes, provenance, and every adjacent edge
     * (both directions, any type), plus a best-effort link back to the owning API resource (see
     * {@link TraceabilityQueryService#owningResourcePath}). Workspace-scoped via RLS + the explicit
     * {@code workspace_id} predicate in {@link GraphNodeRepository#findByIdAndWorkspaceId}.
     */
    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<GraphNodeView> node(@PathVariable UUID nodeId) {
        UUID workspaceId = WorkspaceContext.require();
        GraphNode node = graphNodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId).orElse(null);
        if (node == null) return ResponseEntity.notFound().build();

        List<GraphEdge> outEdges = graphEdgeRepository
                .findByWorkspaceIdAndGenerationAndFromNode(workspaceId, node.getGeneration(), node.getId());
        List<GraphEdge> inEdges = graphEdgeRepository
                .findByWorkspaceIdAndGenerationAndToNode(workspaceId, node.getGeneration(), node.getId());

        Set<UUID> peerIds = new LinkedHashSet<>();
        outEdges.forEach(e -> peerIds.add(e.getToNode()));
        inEdges.forEach(e -> peerIds.add(e.getFromNode()));
        Map<UUID, GraphNode> peers = peerIds.isEmpty() ? Map.of()
                : graphNodeRepository.findAllById(peerIds).stream().collect(Collectors.toMap(GraphNode::getId, n -> n));

        List<GraphEdgeRefView> edges = new ArrayList<>();
        for (GraphEdge e : outEdges) {
            GraphNode peer = peers.get(e.getToNode());
            edges.add(new GraphEdgeRefView(e.getEdgeType(), "OUT", e.getToNode(),
                    peer == null ? null : peer.getLabel(), e.getSourceRef()));
        }
        for (GraphEdge e : inEdges) {
            GraphNode peer = peers.get(e.getFromNode());
            edges.add(new GraphEdgeRefView(e.getEdgeType(), "IN", e.getFromNode(),
                    peer == null ? null : peer.getLabel(), e.getSourceRef()));
        }

        GraphNodeView baseView = traceabilityQueryService.toView(node);
        GraphNodeView withEdges = new GraphNodeView(baseView.id(), baseView.nodeType(), baseView.naturalKey(),
                baseView.label(), baseView.attributes(), baseView.sourceKind(), baseView.sourceRef(),
                baseView.owningResourcePath(), edges);
        return ResponseEntity.ok(withEdges);
    }

    /** Malformed {@code pageToken} (see {@code TraceabilityQueryService.PageCursor#decode}) -&gt; 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> onBadRequest() {
        return ResponseEntity.badRequest().build();
    }
}
