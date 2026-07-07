package com.d2os.orchestration;

import com.d2os.casecore.CaseService;
import com.d2os.persona.PersonaExecutionService;
import com.d2os.persona.PersonaInvocation;
import com.d2os.persona.PersonaInvocationRepository;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves a branch-level persona escalation in the parallel block (T022, FR-005). A failed
 * specialist branch is parked at its {@code <persona>-wait} receiveTask while its siblings finish and
 * the parallel join waits — no sibling output is discarded. A human resolution:
 *
 * <ul>
 *   <li><b>RETRY</b> — re-run the persona (a fresh validated invocation supersedes the failed one),
 *       then release the parked branch to the join;</li>
 *   <li><b>ACCEPT_LAST_OUTPUT</b> — accept the last (failed) output as-is (mark the invocation
 *       validated so it still materializes into the package), then release the branch.</li>
 * </ul>
 *
 * <p>Called on the HTTP request thread, where {@code WorkspaceContextFilter} has already bound the
 * caller's workspace RLS context, so the repository reads and the {@code runtimeService.trigger}
 * continuation run correctly scoped. The trigger uses the per-execution id (not a broadcast signal),
 * so only this branch resumes — sibling cases/branches are untouched.
 */
@Service
public class EscalationBridge {

    public enum Action { RETRY, ACCEPT_LAST_OUTPUT }

    private final RuntimeService runtimeService;
    private final PersonaInvocationRepository invocationRepository;
    private final PersonaExecutionService personaExecutionService;
    private final CaseService caseService;

    public EscalationBridge(RuntimeService runtimeService,
                            PersonaInvocationRepository invocationRepository,
                            PersonaExecutionService personaExecutionService,
                            CaseService caseService) {
        this.runtimeService = runtimeService;
        this.invocationRepository = invocationRepository;
        this.personaExecutionService = personaExecutionService;
        this.caseService = caseService;
    }

    private static final String WAIT_ACTIVITY = "specialists-wait";

    /** @return true if the escalated branch was resolved (and the join released if it was the last). */
    @Transactional
    public boolean resolveBranch(UUID caseId, UUID invocationId, Action action, String rationale) {
        PersonaInvocation invocation = invocationRepository.findById(invocationId)
                .orElseThrow(() -> new NoSuchElementException("invocation " + invocationId));
        if (!PersonaInvocation.Status.escalated.name().equals(invocation.getStatus())) {
            return false;   // not an escalated branch — nothing to resolve
        }
        String personaKey = invocation.getPersonaKey();

        // The branch must already be parked at the wait state, else there is nothing to release yet —
        // return false (→ 409) rather than mutate state, so the caller retries once it has parked.
        Execution waiting = findWaitExecution(caseId);
        if (waiting == null) {
            return false;
        }

        if (action == Action.RETRY) {
            // Fresh generation; on success it becomes the validated output for this persona.
            personaExecutionService.executePersona(caseId, personaKey, invocation.getBranchId());
        } else {
            // Accept the last output: keep it in the validated set so the package stays complete.
            invocation.markStatus(PersonaInvocation.Status.validated);
            invocationRepository.save(invocation);
        }

        // Release the parallel block only once every escalated specialist has a validated output — a
        // persona is resolved when it has any validated invocation, regardless of an earlier escalation.
        if (unresolvedEscalationsRemain(caseId)) {
            return true;   // this branch resolved, but the block stays parked for the others
        }

        caseService.resume(caseId, "human:branch-escalation:" + action + ":" + safe(rationale));
        runtimeService.trigger(waiting.getId());
        return true;
    }

    /**
     * The execution parked at the specialists escalation wait, or null if not parked. Resolves the
     * process instance by business key (only the root execution carries it) first, then queries
     * executions by process-instance id — a child execution at the receiveTask has no business key of
     * its own, so a combined {@code processInstanceBusinessKey().activityId()} query misses it.
     */
    private Execution findWaitExecution(UUID caseId) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(caseId.toString())
                .singleResult();
        if (pi == null) {
            return null;
        }
        return runtimeService.createExecutionQuery()
                .processInstanceId(pi.getId())
                .activityId(WAIT_ACTIVITY)
                .singleResult();
    }

    /** A persona is unresolved if it has an escalated invocation but no validated one. */
    private boolean unresolvedEscalationsRemain(UUID caseId) {
        List<PersonaInvocation> all = invocationRepository.findByCaseInstanceIdOrderBySequenceNoAsc(caseId);
        Set<String> validatedKeys = all.stream()
                .filter(i -> PersonaInvocation.Status.validated.name().equals(i.getStatus()))
                .map(PersonaInvocation::getPersonaKey)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(i -> PersonaInvocation.Status.escalated.name().equals(i.getStatus()))
                .anyMatch(i -> !validatedKeys.contains(i.getPersonaKey()));
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
