package com.d2os.projection.cycle;

import com.d2os.governance.notification.NotificationService;
import com.d2os.observability.JobMetrics;
import com.d2os.projection.GraphEdge;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.sql.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T021 — whole-graph, cross-case dependency-cycle detection over {@code DEPENDS_ON} edges (research
 * R8, FR-008/009). Two complementary checks:
 *
 * <ol>
 *   <li><b>Incremental</b> ({@link #checkIncremental}): {@code Projector} calls this right after
 *       upserting each sweep's newly-projected edges, passing only the {@code DEPENDS_ON} ones. For
 *       each new edge {@code from -> to}, a bounded recursive CTE ({@link #reachablePath}) checks
 *       whether {@code to} can already reach {@code from} — if so, this edge just closed a cycle.
 *   <li><b>Scheduled</b> ({@link #scheduledSweep}): a full per-workspace sweep, cadence {@code
 *       d2os.projection.cycle-sweep.cadence} (T003, daily default), the completeness backstop for
 *       edges that arrived during a rebuild window or any other gap in the incremental path. Uses
 *       Kahn-style peeling to find every node that participates in at least one cycle, whole-graph
 *       and cross-case (not per-case subgraphs) per FR-008's "spans the whole workspace graph".
 * </ol>
 *
 * <p>Alerts persist through the Phase 5 {@link NotificationService} (T022) — {@code source_module =
 * "projection"}, {@code type = "CYCLE_DETECTED"}, {@code subject_ref} carrying the ordered member
 * node ids (the finding's actual persisted record; no separate {@code cycle_finding} table exists
 * in data-model.md) — plus the outbox event {@link NotificationService} already writes in the same
 * transaction. {@code projection} already depends on {@code governance} (read-only per this
 * module's build.gradle header, for {@code gate_instance}/{@code GateEventPayload}); this is the
 * one write use of that dependency, reusing the real notification store rather than duplicating its
 * insert logic via raw JDBC (unlike, e.g., {@code catalog}'s {@code CatalogAuditWriter} — that
 * duplication was needed there because {@code catalog} has NO dependency on the module owning the
 * audit table; here the dependency already exists, so reuse is the smaller, more honest move).
 *
 * <h2>Whole-graph completeness vs. exhaustive cycle enumeration (a deliberate scope decision)</h2>
 *
 * The scheduled sweep's Kahn peel identifies EVERY node that lies on at least one cycle — that part
 * is exact and complete (FR-009's "acyclic ⇒ zero findings" holds exactly: peeling an acyclic graph
 * empties it completely). Turning that node set into named cycle findings is where a real trade-off
 * is made: enumerating literally every simple cycle in a densely-overlapping subgraph is
 * combinatorially unbounded in general (a complete graph on N nodes has exponentially many simple
 * cycles). Instead, {@link #dfsFindCycle} runs one bounded DFS per not-yet-covered cyclic node and
 * reports the first back-edge it finds, continuing until every cyclic node has been named in at
 * least one finding. Every node the peel flags is guaranteed to show up in some alert; a graph with
 * multiple genuinely distinct, overlapping cycles may be reported as fewer findings than the
 * mathematical count of simple cycles — the alerting contract ("a cycle exists here, these are its
 * members") is met either way.
 */
@Component
public class CycleDetector {

  private static final Logger log = LoggerFactory.getLogger(CycleDetector.class);

  /**
   * Bounded recursive CTE / DFS depth (research R8's "bounded recursive CTE" instruction) — deep
   * enough for any realistic case-to-case dependency chain, shallow enough that a pathological or
   * adversarial graph cannot make either check run away.
   */
  private static final int MAX_PATH_DEPTH = 50;

  private final JdbcTemplate jdbcTemplate;
  private final WorkspaceRlsBinder workspaceRlsBinder;
  private final PlatformTransactionManager transactionManager;
  private final NotificationService notificationService;
  private final String catalogOwnerRole;
  private final JobMetrics jobMetrics;

  public CycleDetector(
      JdbcTemplate jdbcTemplate,
      WorkspaceRlsBinder workspaceRlsBinder,
      PlatformTransactionManager transactionManager,
      NotificationService notificationService,
      @Value("${d2os.studio.roles.catalog-owner:catalog-owner}") String catalogOwnerRole,
      JobMetrics jobMetrics) {
    this.jdbcTemplate = jdbcTemplate;
    this.workspaceRlsBinder = workspaceRlsBinder;
    this.transactionManager = transactionManager;
    this.notificationService = notificationService;
    this.catalogOwnerRole = catalogOwnerRole;
    this.jobMetrics = jobMetrics;
  }

  // ==== (a) incremental — called by Projector right after upserting a sweep's new edges ==========

  /** {@code newDependsOnEdges} must already be filtered to {@code edge_type = DEPENDS_ON}. */
  public void checkIncremental(
      UUID workspaceId, int generation, List<GraphEdge> newDependsOnEdges) {
    for (GraphEdge edge : newDependsOnEdges) {
      List<UUID> memberNodes =
          closesCycle(workspaceId, generation, edge.getFromNode(), edge.getToNode());
      if (memberNodes != null) {
        raiseAlert(workspaceId, memberNodes, "incremental");
      }
    }
  }

  /**
   * Returns the closed cycle's member nodes (path order, first node repeated at the end to make the
   * loop explicit) if edge {@code fromNode -> toNode} closes a cycle — i.e. {@code toNode} can
   * already reach {@code fromNode} via existing {@code DEPENDS_ON} edges — else {@code null}.
   */
  private List<UUID> closesCycle(UUID workspaceId, int generation, UUID fromNode, UUID toNode) {
    if (fromNode.equals(toNode)) {
      return List.of(fromNode, fromNode); // self-loop: the trivial 1-node cycle
    }
    WorkspaceContext.set(workspaceId);
    try {
      TransactionTemplate tx = requiresNew(transactionManager);
      return tx.execute(
          status -> {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            List<UUID> path = reachablePath(workspaceId, generation, toNode, fromNode);
            if (path == null) return null;
            List<UUID> memberNodes = new ArrayList<>();
            memberNodes.add(fromNode);
            memberNodes.addAll(path);
            return memberNodes;
          });
    } finally {
      WorkspaceContext.clear();
    }
  }

  /**
   * Bounded recursive CTE: is {@code targetNode} reachable from {@code startNode} via {@code
   * DEPENDS_ON} edges?
   */
  private List<UUID> reachablePath(
      UUID workspaceId, int generation, UUID startNode, UUID targetNode) {
    String sql =
        "WITH RECURSIVE reach(node_id, path, depth) AS ("
            + "  SELECT ge.to_node, ARRAY[ge.to_node], 1 FROM graph_edge ge "
            + "  WHERE ge.workspace_id = ? AND ge.generation = ? AND ge.edge_type = 'DEPENDS_ON' AND ge.from_node = ? "
            + "  UNION ALL "
            + "  SELECT ge.to_node, r.path || ge.to_node, r.depth + 1 "
            + "  FROM graph_edge ge JOIN reach r ON ge.from_node = r.node_id "
            + "  WHERE ge.workspace_id = ? AND ge.generation = ? AND ge.edge_type = 'DEPENDS_ON' "
            + "    AND r.depth < ? AND NOT ge.to_node = ANY(r.path)"
            + ") SELECT path FROM reach WHERE node_id = ? ORDER BY depth LIMIT 1";
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            sql,
            workspaceId,
            generation,
            startNode,
            workspaceId,
            generation,
            MAX_PATH_DEPTH,
            targetNode);
    if (rows.isEmpty()) {
      return null;
    }
    return toUuidList(rows.get(0).get("path"));
  }

  // ==== (b) scheduled full-graph sweep — Kahn-style peeling, per workspace
  // =========================

  @Scheduled(cron = "${d2os.projection.cycle-sweep.cadence:0 0 4 * * *}")
  @SchedulerLock(name = "cycle-sweep", lockAtMostFor = "PT10M")
  public void scheduledSweep() {
    jobMetrics.time(
        "cycle-sweep",
        () -> {
          List<UUID> workspaceIds =
              jdbcTemplate.queryForList("SELECT id FROM list_active_workspace_ids()", UUID.class);
          for (UUID workspaceId : workspaceIds) {
            try {
              sweepWorkspace(workspaceId);
            } catch (Exception e) {
              log.warn("cycle sweep failed for workspace {}: {}", workspaceId, e.toString());
            }
          }
        });
  }

  /**
   * Package-visible for direct test invocation, same convention as {@code
   * Projector#processWorkspace}.
   */
  void sweepWorkspace(UUID workspaceId) {
    WorkspaceContext.set(workspaceId);
    List<List<UUID>> cycles;
    try {
      TransactionTemplate readTx = requiresNew(transactionManager);
      cycles =
          readTx.execute(
              status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                Integer generation = currentLiveGeneration(workspaceId);
                if (generation == null) {
                  return List.of();
                }
                return findAllCycles(workspaceId, generation);
              });
    } finally {
      WorkspaceContext.clear();
    }
    for (List<UUID> cycle : cycles) {
      raiseAlert(workspaceId, cycle, "scheduled");
    }
  }

  private Integer currentLiveGeneration(UUID workspaceId) {
    List<Integer> rows =
        jdbcTemplate.queryForList(
            "SELECT live_generation FROM projection_state WHERE workspace_id = ?",
            Integer.class,
            workspaceId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** See class javadoc's "Whole-graph completeness vs. exhaustive cycle enumeration" section. */
  private List<List<UUID>> findAllCycles(UUID workspaceId, int generation) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT from_node, to_node FROM graph_edge "
                + "WHERE workspace_id = ? AND generation = ? AND edge_type = 'DEPENDS_ON'",
            workspaceId,
            generation);

    List<UUID[]> edges = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      edges.add(new UUID[] {(UUID) row.get("from_node"), (UUID) row.get("to_node")});
    }
    return detectCycles(edges);
  }

  /**
   * Pure, JDBC-free graph logic extracted verbatim from {@link #findAllCycles} for direct unit
   * testing (008 T007) and reuse: Kahn-style peeling to find every node on {@code >= 1} cycle, then
   * one bounded DFS per uncovered cyclic node to name findings. Behavior-preserving extraction —
   * see the class javadoc's "Whole-graph completeness vs. exhaustive cycle enumeration" section.
   *
   * @param edges each element is a {@code {fromNode, toNode}} DEPENDS_ON pair.
   */
  static List<List<UUID>> detectCycles(List<UUID[]> edges) {
    Map<UUID, List<UUID>> adjacency = new LinkedHashMap<>();
    Map<UUID, Integer> inDegree = new HashMap<>();
    Set<UUID> allNodes = new LinkedHashSet<>();
    for (UUID[] edge : edges) {
      UUID from = edge[0];
      UUID to = edge[1];
      adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
      inDegree.merge(to, 1, Integer::sum);
      allNodes.add(from);
      allNodes.add(to);
    }

    // Kahn's algorithm: repeatedly peel every zero-in-degree node. What remains lies on >= 1 cycle.
    Deque<UUID> zeroIndegree = new ArrayDeque<>();
    Map<UUID, Integer> remainingInDegree = new HashMap<>(inDegree);
    for (UUID node : allNodes) {
      if (remainingInDegree.getOrDefault(node, 0) == 0) {
        zeroIndegree.add(node);
      }
    }
    Set<UUID> peeled = new HashSet<>();
    while (!zeroIndegree.isEmpty()) {
      UUID node = zeroIndegree.poll();
      peeled.add(node);
      for (UUID neighbor : adjacency.getOrDefault(node, List.of())) {
        int updated = remainingInDegree.merge(neighbor, -1, Integer::sum);
        if (updated == 0) {
          zeroIndegree.add(neighbor);
        }
      }
    }
    Set<UUID> cyclic = new LinkedHashSet<>(allNodes);
    cyclic.removeAll(peeled);
    if (cyclic.isEmpty()) {
      return List.of(); // acyclic — zero findings (FR-009)
    }

    List<List<UUID>> cycles = new ArrayList<>();
    Set<UUID> covered = new HashSet<>();
    for (UUID root : cyclic) {
      if (covered.contains(root)) {
        continue;
      }
      List<UUID> cycle = dfsFindCycle(root, adjacency, cyclic);
      if (cycle != null) {
        cycles.add(cycle);
        covered.addAll(cycle);
      }
    }
    return cycles;
  }

  /**
   * DFS with an explicit recursion stack, restricted to {@code cyclic} nodes, bounded by {@link
   * #MAX_PATH_DEPTH}.
   */
  private static List<UUID> dfsFindCycle(
      UUID root, Map<UUID, List<UUID>> adjacency, Set<UUID> cyclic) {
    List<UUID> stack = new ArrayList<>();
    Set<UUID> onStack = new LinkedHashSet<>();
    Deque<Iterator<UUID>> iterStack = new ArrayDeque<>();
    stack.add(root);
    onStack.add(root);
    iterStack.push(adjacency.getOrDefault(root, List.of()).iterator());

    while (!stack.isEmpty() && stack.size() <= MAX_PATH_DEPTH) {
      Iterator<UUID> it = iterStack.peek();
      if (it.hasNext()) {
        UUID next = it.next();
        if (!cyclic.contains(next)) {
          continue;
        }
        if (onStack.contains(next)) {
          int idx = stack.indexOf(next);
          List<UUID> cyclePath = new ArrayList<>(stack.subList(idx, stack.size()));
          cyclePath.add(next);
          return cyclePath;
        }
        stack.add(next);
        onStack.add(next);
        iterStack.push(adjacency.getOrDefault(next, List.of()).iterator());
      } else {
        UUID done = stack.remove(stack.size() - 1);
        onStack.remove(done);
        iterStack.pop();
      }
    }
    return null;
  }

  // ==== alerting (T022)
  // ============================================================================

  private void raiseAlert(UUID workspaceId, List<UUID> memberNodes, String detectionMethod) {
    WorkspaceContext.set(workspaceId);
    try {
      TransactionTemplate tx = requiresNew(transactionManager);
      tx.executeWithoutResult(
          status -> {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            Map<String, Object> subjectRef = new LinkedHashMap<>();
            subjectRef.put("memberNodeIds", memberNodes.stream().map(UUID::toString).toList());
            subjectRef.put("detectionMethod", detectionMethod);
            String message =
                "Dependency cycle detected spanning "
                    + (memberNodes.size() - 1)
                    + " node(s) ("
                    + detectionMethod
                    + " detection)";
            notificationService.notifyRole(
                workspaceId, catalogOwnerRole, "projection", "CYCLE_DETECTED", subjectRef, message);
          });
      log.warn(
          "dependency cycle detected for workspace {} ({} detection): {} member node(s)",
          workspaceId,
          detectionMethod,
          memberNodes.size() - 1);
    } finally {
      WorkspaceContext.clear();
    }
  }

  // ==== helpers
  // =====================================================================================

  private List<UUID> toUuidList(Object arrayObj) {
    try {
      Array sqlArray = (Array) arrayObj;
      Object[] raw = (Object[]) sqlArray.getArray();
      List<UUID> result = new ArrayList<>(raw.length);
      for (Object o : raw) {
        result.add(UUID.fromString(o.toString()));
      }
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Malformed path array from recursive CTE", e);
    }
  }

  private TransactionTemplate requiresNew(PlatformTransactionManager manager) {
    DefaultTransactionDefinition def = new DefaultTransactionDefinition();
    def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new TransactionTemplate(manager, def);
  }
}
