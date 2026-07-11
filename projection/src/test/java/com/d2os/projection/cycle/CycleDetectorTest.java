package com.d2os.projection.cycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 008 T007 — fast, pure unit test of {@link CycleDetector#detectCycles(List)} (Kahn peel + bounded
 * DFS). No Spring, no JDBC, no Testcontainers: exercises the package-private static graph helper
 * extracted from {@code CycleDetector#findAllCycles}.
 */
class CycleDetectorTest {

  private static UUID[] edge(UUID from, UUID to) {
    return new UUID[] {from, to};
  }

  private static Set<UUID> flatten(List<List<UUID>> cycles) {
    Set<UUID> nodes = new HashSet<>();
    for (List<UUID> cycle : cycles) {
      nodes.addAll(cycle);
    }
    return nodes;
  }

  @Test
  void threeNodeCycle_reportsEveryMemberNode() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<UUID[]> edges = List.of(edge(a, b), edge(b, c), edge(c, a));

    List<List<UUID>> cycles = CycleDetector.detectCycles(edges);

    assertEquals(1, cycles.size(), "one overlapping 3-node cycle expected");
    Set<UUID> members = flatten(cycles);
    assertTrue(
        members.contains(a) && members.contains(b) && members.contains(c),
        "every member node must appear in a reported cycle");
    // Path form: start node repeated at the end to make the loop explicit.
    List<UUID> path = cycles.get(0);
    assertEquals(path.get(0), path.get(path.size() - 1), "cycle path closes on its start node");
  }

  @Test
  void acyclicGraph_reportsZeroCycles() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    // a -> b -> c, plus a fan-out a -> c: a DAG, no back edge.
    List<UUID[]> edges = List.of(edge(a, b), edge(b, c), edge(a, c));

    List<List<UUID>> cycles = CycleDetector.detectCycles(edges);

    assertTrue(cycles.isEmpty(), "an acyclic graph must yield zero findings (FR-009)");
  }

  @Test
  void selfLoop_isReportedAsSingleNodeCycle() {
    UUID a = UUID.randomUUID();
    List<UUID[]> edges = new ArrayList<>();
    edges.add(edge(a, a));

    List<List<UUID>> cycles = CycleDetector.detectCycles(edges);

    assertEquals(1, cycles.size(), "a self-loop is a cycle");
    assertTrue(cycles.get(0).contains(a), "the self-looping node must be reported");
  }
}
