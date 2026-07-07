package com.d2os.persona;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.WorkspaceBudgetService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Enforces the per-Case AI token budget (T031, NFR-7, FR-012) and the per-workspace budget rollup
 * (T036, FR-017, T5-b). Both checks happen <em>before</em> the AI call — a Case is suspended before it
 * breaches either ceiling, never after, so no call is ever made once a ceiling would be crossed.
 * Actual usage is recorded against both the Case and its workspace in the same transaction.
 */
@Component
public class TokenBudgetGuard {

    private final CaseInstanceRepository caseRepository;
    private final CaseService caseService;
    private final WorkspaceBudgetService workspaceBudgetService;

    public TokenBudgetGuard(CaseInstanceRepository caseRepository, CaseService caseService,
                            WorkspaceBudgetService workspaceBudgetService) {
        this.caseRepository = caseRepository;
        this.caseService = caseService;
        this.workspaceBudgetService = workspaceBudgetService;
    }

    /** @throws TokenBudgetExceededException if the estimated call would exceed the Case or workspace budget; the Case is suspended as a side effect. */
    @Transactional
    public void checkBeforeCall(UUID caseId, long estimatedTokens) {
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));
        if (kase.wouldExceedBudget(estimatedTokens)) {
            caseService.suspend(caseId, "token budget would be exceeded by estimated " + estimatedTokens + " tokens");
            throw new TokenBudgetExceededException("case " + caseId + " suspended: token budget exceeded");
        }
        if (workspaceBudgetService.wouldExceed(kase.getWorkspaceId(), estimatedTokens)) {
            caseService.suspend(caseId, "workspace token budget would be exceeded by estimated " + estimatedTokens + " tokens");
            throw new TokenBudgetExceededException("case " + caseId + " suspended: workspace token budget exceeded");
        }
    }

    @Transactional
    public void recordUsage(UUID caseId, long actualTokens) {
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));
        // Atomic increment — must NOT persist the whole entity, or a concurrent specialist could
        // overwrite a sibling's just-committed status change (e.g. an escalation) with stale state.
        caseRepository.addTokensSpent(caseId, actualTokens);
        workspaceBudgetService.recordConsumption(kase.getWorkspaceId(), actualTokens);
    }
}
