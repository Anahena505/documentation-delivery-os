package com.d2os.projection.query;

import com.d2os.projection.GraphNode;
import com.d2os.projection.GraphNodeRepository;
import com.d2os.projection.ProjectionState;
import com.d2os.projection.ProjectionStateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T016 &mdash; multi-hop TRACES_TO / DEPENDS_ON lineage queries over {@code graph_edge} (research
 * R7, FR-005/007/013). Both query families are {@code WITH RECURSIVE} CTEs run on the app's normal
 * ({@code d2os_app}, SELECT-only on the graph tables per V28) datasource &mdash; the plain,
 * {@code @Primary}-bound {@link JdbcTemplate} every other read path in this repo uses, injected
 * here with no qualifier (same convention as {@link com.d2os.projection.api.GraphAdminController}).
 *
 * <h2>RLS + explicit predicate (Principle IV, defense in depth)</h2>
 * Every query below carries an explicit {@code workspace_id = ?} predicate in addition to relying
 * on Postgres RLS (already engaged transparently by {@code WorkspaceAwareDataSource} for this
 * request-scoped connection) &mdash; per this task's own instruction, never RLS alone.
 *
 * <h2>relation &rarr; edge_type mapping (a documented interpretation, not literally spelled out
 * in data-model.md)</h2>
 * {@code contracts/api.yaml} only names two logical relations, {@code TRACES_TO} and {@code
 * DEPENDS_ON}, but {@link com.d2os.projection.NodeEdgeMapper#mapTraceLink} preserves the specific
 * {@code trace_link.link_type} as the edge's {@code edge_type} rather than collapsing every
 * trace_link-derived edge to the literal string {@code "TRACES_TO"} (its own javadoc's
 * "CONFLICTS_WITH mapping decision"). A {@code relation=TRACES_TO} query that only matched
 * {@code edge_type = 'TRACES_TO'} would therefore be blind to most real trace_link rows (the
 * {@code DERIVES_FROM}/{@code SATISFIES} kinds). {@link #TRACES_TO_EDGE_TYPES} is the whole
 * trace_link-derived family; {@link #DEPENDS_ON_EDGE_TYPES} is the literal {@code DEPENDS_ON} type
 * only (the sole edge_type {@code dependency} rows ever map to).
 *
 * <h2>Pagination design (real, resumable &mdash; not a fake token)</h2>
 * {@link PageCursor} base64-encodes {@code "<generation>|<offset>"}. The FIRST page (no
 * {@code pageToken}) always resolves against the workspace's CURRENT live generation; every
 * SUBSEQUENT page resumes against the generation embedded in its token, not whatever happens to be
 * live when the follow-up request arrives &mdash; so a multi-page traversal stays internally
 * consistent even if a {@code RebuildJob} flip lands mid-traversal. Honest caveat: {@code
 * RebuildJob} purges the OLD generation only after a passing flip, so a token minted against a
 * generation that has since been purged simply resolves to "no root node found" on resume (the
 * same 404/empty outcome as a first request against an unknown node) &mdash; not a crash, but also
 * not a seamless continuation; documented here rather than silently assumed away.
 *
 * <h2>One path per reached node (not full path enumeration)</h2>
 * The recursive CTEs de-duplicate to the SHORTEST discovered path per distinct node (ties broken
 * by a deterministic edge id ordering), not every possible path to that node. For a DAG with
 * diamond-shaped fan-in this returns one representative lineage path per node rather than a
 * combinatorial blow-up of every route &mdash; a deliberate, documented scope decision matching the
 * node-budget's per-NODE pagination semantics (a "how many DISTINCT nodes have I paginated through"
 * budget, not a "how many paths" one).
 */
@Service
public class TraceabilityQueryService {

    public enum Direction { UPSTREAM, DOWNSTREAM, BOTH }

    private static final Set<String> TRACES_TO_EDGE_TYPES = Set.of("TRACES_TO", "DERIVES_FROM", "SATISFIES");
    private static final Set<String> DEPENDS_ON_EDGE_TYPES = Set.of("DEPENDS_ON");

    private final JdbcTemplate jdbcTemplate;
    private final GraphNodeRepository graphNodeRepository;
    private final ProjectionStateRepository projectionStateRepository;
    private final ObjectMapper objectMapper;
    private final int nodeBudget;

    public TraceabilityQueryService(JdbcTemplate jdbcTemplate, GraphNodeRepository graphNodeRepository,
                                     ProjectionStateRepository projectionStateRepository, ObjectMapper objectMapper,
                                     @Value("${d2os.projection.node-budget:500}") int nodeBudget) {
        this.jdbcTemplate = jdbcTemplate;
        this.graphNodeRepository = graphNodeRepository;
        this.projectionStateRepository = projectionStateRepository;
        this.objectMapper = objectMapper;
        this.nodeBudget = nodeBudget;
    }

    /** {@code relation=TRACES_TO} &mdash; see class javadoc for the edge-type family this covers. */
    public Optional<LineageResult> tracesTo(UUID workspaceId, String nodeType, String naturalKey,
                                             Direction direction, int maxDepth, String pageToken) {
        return query(workspaceId, nodeType, naturalKey, TRACES_TO_EDGE_TYPES, direction, maxDepth, pageToken);
    }

    /** {@code relation=DEPENDS_ON}. */
    public Optional<LineageResult> dependsOn(UUID workspaceId, String nodeType, String naturalKey,
                                              Direction direction, int maxDepth, String pageToken) {
        return query(workspaceId, nodeType, naturalKey, DEPENDS_ON_EDGE_TYPES, direction, maxDepth, pageToken);
    }

    private Optional<LineageResult> query(UUID workspaceId, String nodeType, String naturalKey,
                                           Set<String> edgeTypes, Direction direction, int maxDepth,
                                           String pageToken) {
        int generation;
        int offset;
        if (pageToken != null && !pageToken.isBlank()) {
            PageCursor cursor = PageCursor.decode(pageToken);
            generation = cursor.generation();
            offset = cursor.offset();
        } else {
            ProjectionState state = projectionStateRepository.findById(workspaceId).orElse(null);
            if (state == null) return Optional.empty();
            generation = state.getLiveGeneration();
            offset = 0;
        }

        GraphNode root = graphNodeRepository
                .findByWorkspaceIdAndGenerationAndNodeTypeAndNaturalKey(workspaceId, generation, nodeType, naturalKey)
                .orElse(null);
        if (root == null) return Optional.empty();

        String[] edgeTypeArray = edgeTypes.toArray(new String[0]);
        int fetchLimit = offset + nodeBudget + 1; // +1 sentinel row past the budget => truncated

        List<HopRow> rows = new ArrayList<>();
        if (direction == Direction.DOWNSTREAM || direction == Direction.BOTH) {
            rows.addAll(runDirectional(true, "DOWNSTREAM", workspaceId, generation, root.getId(), edgeTypeArray,
                    maxDepth, fetchLimit));
        }
        if (direction == Direction.UPSTREAM || direction == Direction.BOTH) {
            rows.addAll(runDirectional(false, "UPSTREAM", workspaceId, generation, root.getId(), edgeTypeArray,
                    maxDepth, fetchLimit));
        }
        // Deterministic merge order across both directions (BOTH): shallowest first, tie-broken by
        // direction name then the reached node's id, so pagination is stable across pages.
        rows.sort(Comparator.comparingInt(HopRow::depth)
                .thenComparing(HopRow::direction)
                .thenComparing(r -> lastNode(r).toString()));

        boolean truncated = rows.size() > offset + nodeBudget;
        int end = Math.min(rows.size(), offset + nodeBudget);
        List<HopRow> page = offset >= rows.size() ? List.of() : rows.subList(offset, end);

        Set<UUID> nodeIds = new LinkedHashSet<>();
        for (HopRow r : page) nodeIds.addAll(r.pathNodes());
        Map<UUID, GraphNode> nodesById = nodeIds.isEmpty() ? Map.of()
                : graphNodeRepository.findAllById(nodeIds).stream()
                        .collect(Collectors.toMap(GraphNode::getId, n -> n));

        List<List<PathHop>> paths = new ArrayList<>();
        for (HopRow r : page) {
            List<PathHop> hops = new ArrayList<>();
            List<UUID> pathNodes = r.pathNodes();
            for (int i = 1; i < pathNodes.size(); i++) {
                GraphNode gn = nodesById.get(pathNodes.get(i));
                if (gn == null) continue; // defensive: node ids come straight from graph_edge's own FKs
                hops.add(new PathHop(r.pathEdgeTypes().get(i - 1), toView(gn), i));
            }
            paths.add(hops);
        }

        String nextPageToken = truncated ? PageCursor.encode(generation, offset + nodeBudget) : null;
        return Optional.of(new LineageResult(toView(root), paths, truncated, nextPageToken));
    }

    private UUID lastNode(HopRow r) {
        return r.pathNodes().get(r.pathNodes().size() - 1);
    }

    /**
     * Runs one direction's recursive CTE. {@code downstream=true} walks {@code from_node -> to_node}
     * (the {@code idx_graph_edge_from} index); {@code false} walks backward ({@code
     * idx_graph_edge_to}). Both branches de-duplicate to one (shortest) path per reached node before
     * the caller merges/paginates (see class javadoc).
     */
    private List<HopRow> runDirectional(boolean downstream, String directionLabel, UUID workspaceId, int generation,
                                         UUID rootId, String[] edgeTypes, int maxDepth, int limit) {
        String placeholders = String.join(",", Collections.nCopies(edgeTypes.length, "?"));
        String startCol = downstream ? "from_node" : "to_node";
        String reachedCol = downstream ? "to_node" : "from_node";

        String sql = "WITH RECURSIVE walk AS (" +
                "SELECT ge.id AS edge_id, ge.edge_type, ge." + reachedCol + " AS reached_node, 1 AS depth, " +
                "ARRAY[ge." + startCol + ", ge." + reachedCol + "] AS path_nodes, " +
                "ARRAY[ge.edge_type]::text[] AS path_edge_types " +
                "FROM graph_edge ge " +
                "WHERE ge.workspace_id = ? AND ge.generation = ? AND ge." + startCol + " = ? " +
                "AND ge.edge_type IN (" + placeholders + ") " +
                "UNION ALL " +
                "SELECT ge.id, ge.edge_type, ge." + reachedCol + ", w.depth + 1, " +
                "w.path_nodes || ge." + reachedCol + ", w.path_edge_types || ge.edge_type " +
                "FROM graph_edge ge JOIN walk w ON ge." + startCol + " = w.reached_node " +
                "WHERE ge.workspace_id = ? AND ge.generation = ? AND ge.edge_type IN (" + placeholders + ") " +
                "AND w.depth < ? AND NOT (ge." + reachedCol + " = ANY (w.path_nodes))" +
                ") " +
                "SELECT reached_node, depth, path_nodes, path_edge_types FROM (" +
                "  SELECT DISTINCT ON (reached_node) reached_node, depth, path_nodes, path_edge_types, edge_id " +
                "  FROM walk ORDER BY reached_node, depth ASC, edge_id ASC" +
                ") dedup ORDER BY depth ASC, reached_node ASC LIMIT ?";

        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        params.add(generation);
        params.add(rootId);
        params.addAll(List.of(edgeTypes));
        params.add(workspaceId);
        params.add(generation);
        params.addAll(List.of(edgeTypes));
        params.add(maxDepth);
        params.add(limit);

        RowMapper<HopRow> mapper = (ResultSet rs, int rowNum) -> new HopRow(
                directionLabel,
                rs.getInt("depth"),
                toUuidList(rs.getArray("path_nodes")),
                toStringList(rs.getArray("path_edge_types")));
        return jdbcTemplate.query(sql, mapper, params.toArray());
    }

    private static List<UUID> toUuidList(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] raw = (Object[]) array.getArray();
        List<UUID> result = new ArrayList<>(raw.length);
        for (Object o : raw) {
            result.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
        }
        return result;
    }

    private static List<String> toStringList(Array array) throws SQLException {
        if (array == null) return List.of();
        Object[] raw = (Object[]) array.getArray();
        List<String> result = new ArrayList<>(raw.length);
        for (Object o : raw) result.add(o == null ? null : o.toString());
        return result;
    }

    /**
     * Builds the shared {@link GraphNodeView} for a root/hop node &mdash; adjacency omitted (see
     * class javadoc). Public so {@link com.d2os.projection.api.TraceabilityController}'s node-detail
     * endpoint can reuse the same attribute-parsing/owning-resource-link logic rather than
     * duplicating it, then overlay the full adjacency list that endpoint alone needs.
     */
    public GraphNodeView toView(GraphNode node) {
        Map<String, Object> attributes = readAttributes(node.getAttributes());
        return new GraphNodeView(node.getId(), node.getNodeType(), node.getNaturalKey(), node.getLabel(),
                attributes, node.getSourceKind(), node.getSourceRef(),
                owningResourcePath(node.getNodeType(), node.getNaturalKey(), attributes), List.of());
    }

    private Map<String, Object> readAttributes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * Best-effort link back to the owning API resource per node type (T017, FR-006). Only node
     * types this repo actually exposes a single-resource GET endpoint for get a real link;
     * everything else (ARTIFACT_REVISION/REQUIREMENT/PACKAGE/FEATURE/PROJECT/DEFINITION_VERSION/
     * OPERATION_EXECUTION) has NO dedicated by-id REST endpoint anywhere in this codebase today
     * (verified) &mdash; left {@code null} rather than a guessed/broken path.
     */
    static String owningResourcePath(String nodeType, String naturalKey, Map<String, Object> attributes) {
        return switch (nodeType) {
            case "CASE" -> "/api/v1/cases/" + naturalKey;
            case "GATE" -> "/api/v1/gates/" + naturalKey;
            case "SUBMISSION" -> "/api/v1/submissions/" + naturalKey;
            case "KNOWLEDGE_ITEM_VERSION" -> {
                Object itemId = attributes == null ? null : attributes.get("knowledgeItemId");
                yield itemId != null ? "/api/v1/knowledge/items/" + itemId : null;
            }
            default -> null;
        };
    }

    // ---- internal row shape ----------------------------------------------------------------------

    private record HopRow(String direction, int depth, List<UUID> pathNodes, List<String> pathEdgeTypes) {}

    // ---- pagination token --------------------------------------------------------------------------

    record PageCursor(int generation, int offset) {
        static PageCursor decode(String token) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 2);
                return new PageCursor(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            } catch (Exception e) {
                throw new IllegalArgumentException("Malformed pageToken: " + token, e);
            }
        }

        static String encode(int generation, int offset) {
            String raw = generation + "|" + offset;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---- DTOs (contracts/api.yaml GraphNode / GraphEdgeRef / LineageResult) ------------------------

    public record GraphNodeView(UUID id, String nodeType, String naturalKey, String label,
                                Map<String, Object> attributes, String sourceKind, String sourceRef,
                                String owningResourcePath, List<GraphEdgeRefView> edges) {}

    public record GraphEdgeRefView(String edgeType, String direction, UUID peerNodeId, String peerLabel,
                                   String sourceRef) {}

    public record PathHop(String edgeType, GraphNodeView node, int depth) {}

    public record LineageResult(GraphNodeView root, List<List<PathHop>> paths, boolean truncated,
                                String nextPageToken) {}
}
