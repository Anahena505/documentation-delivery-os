package com.d2os.governance.reopen;

import com.d2os.casecore.spi.ArtifactRevisionListener;
import com.d2os.governance.GateEventPublisher;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identifies which already-APPROVED gates a revised upstream artifact invalidates (Phase 5 US3,
 * T025, research R3, FR-006/008). Walks {@code trace_link} outward from the superseded revision via
 * BFS, classifying each dependent by depth: {@code depth=1} (direct) is reopenable and, if an
 * APPROVED gate exists on that exact dependent revision, flips it {@code APPROVED ->
 * REOPEN_CANDIDATE} (a system transition, never a human decision — {@link GateStatus}'s own
 * state-machine doc). {@code depth>1} (transitive) is always {@code MANUAL_REVIEW} — never
 * auto-reopened (Q3/AD-5) — and carries no {@code gate_instance_id}, since "the approved gate to
 * reopen" only makes sense one hop away.
 */
@Service
public class ReopenCandidateService implements ArtifactRevisionListener {

  /** Guards against a pathological/cyclic trace_link graph — real dependency chains are shallow. */
  private static final int MAX_DEPTH = 50;

  private final JdbcTemplate jdbcTemplate;
  private final ReopenDmnPort reopenDmnPort;
  private final GateInstanceRepository gateInstanceRepository;
  private final GateReopenCandidateRepository candidateRepository;
  private final GateEventPublisher gateEventPublisher;

  public ReopenCandidateService(
      JdbcTemplate jdbcTemplate,
      ReopenDmnPort reopenDmnPort,
      GateInstanceRepository gateInstanceRepository,
      GateReopenCandidateRepository candidateRepository,
      GateEventPublisher gateEventPublisher) {
    this.jdbcTemplate = jdbcTemplate;
    this.reopenDmnPort = reopenDmnPort;
    this.gateInstanceRepository = gateInstanceRepository;
    this.candidateRepository = candidateRepository;
    this.gateEventPublisher = gateEventPublisher;
  }

  @Override
  @Transactional
  public void onNewRevision(UUID workspaceId, UUID supersededRevisionId) {
    identifyReopenCandidates(workspaceId, supersededRevisionId);
  }

  /**
   * BFS outward from {@code upstreamRevisionId} over {@code trace_link} edges whose kind the {@code
   * reopenDirectDependents} DMN says triggers a reopen. Returns every {@link GateReopenCandidate}
   * row written (depth-1 and transitive alike).
   */
  @Transactional
  public List<GateReopenCandidate> identifyReopenCandidates(
      UUID workspaceId, UUID upstreamRevisionId) {
    List<GateReopenCandidate> created = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    visited.add(upstreamRevisionId);

    List<UUID> frontier = List.of(upstreamRevisionId);
    int depth = 1;
    while (!frontier.isEmpty() && depth <= MAX_DEPTH) {
      List<UUID> nextFrontier = new ArrayList<>();
      for (UUID node : frontier) {
        for (Map<String, Object> edge : directDependents(workspaceId, node)) {
          UUID dependentRevisionId = (UUID) edge.get("from_id");
          String edgeKind = (String) edge.get("link_type");
          if (dependentRevisionId == null || !visited.add(dependentRevisionId)) {
            continue; // already visited — avoid infinite loops on a cyclic graph
          }
          if (!reopenDmnPort.triggersReopen(edgeKind)) {
            continue; // e.g. CONFLICTS_WITH — not a reopen-triggering edge kind
          }

          GateInstance approvedGate = depth == 1 ? approvedGateFor(dependentRevisionId) : null;
          GateReopenCandidate.Disposition disposition =
              depth == 1
                  ? GateReopenCandidate.Disposition.PENDING
                  : GateReopenCandidate.Disposition.MANUAL_REVIEW;

          GateReopenCandidate candidate =
              new GateReopenCandidate(
                  UUID.randomUUID(),
                  workspaceId,
                  upstreamRevisionId,
                  dependentRevisionId,
                  approvedGate == null ? null : approvedGate.getId(),
                  depth,
                  disposition);
          candidateRepository.save(candidate);
          created.add(candidate);

          if (depth == 1 && approvedGate != null) {
            approvedGate.transitionTo(GateStatus.REOPEN_CANDIDATE);
            gateInstanceRepository.save(approvedGate);
            gateEventPublisher.publishReopenCandidate(approvedGate, candidate);
          }

          nextFrontier.add(dependentRevisionId);
        }
      }
      frontier = nextFrontier;
      depth++;
    }
    return created;
  }

  /**
   * Every {@code trace_link} edge pointing AT {@code revisionId} from another artifact_revision.
   */
  private List<Map<String, Object>> directDependents(UUID workspaceId, UUID revisionId) {
    return jdbcTemplate.queryForList(
        "SELECT from_id, link_type FROM trace_link WHERE workspace_id = ? "
            + "AND to_type = 'artifact_revision' AND to_id = ? AND from_type = 'artifact_revision'",
        workspaceId,
        revisionId);
  }

  /** The APPROVED gate (if any) whose subject is exactly this ArtifactRevision. */
  private GateInstance approvedGateFor(UUID artifactRevisionId) {
    return gateInstanceRepository
        .findBySubjectTypeAndSubjectId(
            GateInstance.GateSubjectType.ARTIFACT_REVISION.name(), artifactRevisionId)
        .stream()
        .filter(g -> g.status() == GateStatus.APPROVED)
        .findFirst()
        .orElse(null);
  }
}
