package com.d2os.persona.consistency;

import com.d2os.persona.PersonaExecutionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Orchestrates the two-tier Consistency-Check (US3, FR-006/007/008) and owns the finding lifecycle.
 *
 * <ul>
 *   <li><b>Deterministic</b> ({@link DeterministicCrossChecks}) — pure cross-output checks; every
 *       finding is DETERMINISTIC and blocking (FR-007). Each contradiction (two outputs) also writes
 *       a {@code trace_link} CONFLICTS_WITH edge (AD-7).</li>
 *   <li><b>Semantic</b> — a real AI review run through the standard persona machinery
 *       ({@code consistency-reviewer}), so it is snapshot-recorded and replayable (Principle II). If
 *       its rubric fails, it escalates the Case (its own path) and a SEMANTIC finding is recorded as
 *       advisory (FR-008), never a hard block.</li>
 * </ul>
 */
@Service
public class ConsistencyService {

    private final DeterministicCrossChecks deterministicCrossChecks;
    private final ConsistencyFindingRepository findingRepository;
    private final PersonaExecutionService personaExecutionService;
    private final JdbcTemplate jdbcTemplate;

    public ConsistencyService(DeterministicCrossChecks deterministicCrossChecks,
                              ConsistencyFindingRepository findingRepository,
                              PersonaExecutionService personaExecutionService,
                              JdbcTemplate jdbcTemplate) {
        this.deterministicCrossChecks = deterministicCrossChecks;
        this.findingRepository = findingRepository;
        this.personaExecutionService = personaExecutionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs both tiers. Returns true if the case is clean (no OPEN deterministic finding, semantic passed). */
    @Transactional
    public boolean runCheck(UUID caseId, UUID workspaceId) {
        List<ConsistencyFinding> deterministic = deterministicCrossChecks.check(caseId, workspaceId);
        for (ConsistencyFinding f : deterministic) {
            findingRepository.save(f);
            if (f.getTargetOperationId() != null) {
                writeConflictEdge(workspaceId, f.getSourceOperationId(), f.getTargetOperationId());
            }
        }

        // Semantic tier: an AI review recorded as an operation snapshot. A failing review escalates the
        // Case (executePersona's own path) and we log it as an advisory SEMANTIC finding.
        boolean semanticOk = personaExecutionService.executePersona(caseId, "consistency-reviewer");
        UUID reviewOp = lastConsistencyReviewerOp(caseId);
        if (!semanticOk && reviewOp != null) {
            findingRepository.save(new ConsistencyFinding(
                    UUID.randomUUID(), workspaceId, caseId,
                    ConsistencyFinding.Tier.SEMANTIC, ConsistencyFinding.Kind.SEMANTIC_INCOHERENCE,
                    "cross-artifact-coherence", reviewOp, null,
                    "{\"advisory\":true}"));
        }

        return !deterministicBlockingRemains(caseId) && semanticOk;
    }

    /** FR-007 blocking invariant: any OPEN DETERMINISTIC finding stops the package advancing. */
    public boolean deterministicBlockingRemains(UUID caseId) {
        return findingRepository.countByCaseIdAndTierAndStatus(
                caseId, ConsistencyFinding.Tier.DETERMINISTIC.name(), ConsistencyFinding.Status.OPEN.name()) > 0;
    }

    public List<ConsistencyFinding> findings(UUID caseId) {
        return findingRepository.findByCaseId(caseId);
    }

    /**
     * Human decision on a finding (Principle V). A DETERMINISTIC finding cannot be WAIVED — it must be
     * fixed (RESOLVED after regeneration); WAIVE is rejected so a real contradiction can never be
     * waved through. Returns false if the waive rule is violated.
     */
    @Transactional
    public boolean resolveFinding(UUID caseId, UUID findingId, ConsistencyFinding.Status resolution, String actor) {
        ConsistencyFinding finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new NoSuchElementException("finding " + findingId));
        if (!finding.getCaseId().equals(caseId)) {
            throw new NoSuchElementException("finding " + findingId + " not in case " + caseId);
        }
        if (resolution == ConsistencyFinding.Status.WAIVED
                && ConsistencyFinding.Tier.DETERMINISTIC.name().equals(finding.getTier())) {
            return false;   // deterministic contradictions are non-waivable (→ 409)
        }
        finding.resolve(resolution, actor);
        findingRepository.save(finding);
        return true;
    }

    private void writeConflictEdge(UUID workspaceId, UUID fromOp, UUID toOp) {
        jdbcTemplate.update(
                "INSERT INTO trace_link (workspace_id, from_type, from_id, to_type, to_id, link_type) "
                        + "VALUES (?, 'operation_execution', ?, 'operation_execution', ?, 'CONFLICTS_WITH')",
                workspaceId, fromOp, toOp);
    }

    private UUID lastConsistencyReviewerOp(UUID caseId) {
        List<UUID> ids = jdbcTemplate.queryForList(
                "SELECT oe.id FROM operation_execution oe "
                        + "JOIN persona_invocation pi ON pi.id = oe.persona_invocation_id "
                        + "WHERE pi.case_instance_id = ? AND pi.persona_key = 'consistency-reviewer' "
                        + "ORDER BY oe.created_at DESC LIMIT 1",
                UUID.class, caseId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
