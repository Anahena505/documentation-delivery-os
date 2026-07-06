package com.d2os.persona;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Enforces the per-Case AI token budget (T031, NFR-7, FR-012). The check happens <em>before</em>
 * the AI call — a Case is suspended before it breaches its budget, never after, so no call is ever
 * made once the ceiling would be crossed.
 */
@Component
public class TokenBudgetGuard {

    private final CaseInstanceRepository caseRepository;
    private final CaseService caseService;

    public TokenBudgetGuard(CaseInstanceRepository caseRepository, CaseService caseService) {
        this.caseRepository = caseRepository;
        this.caseService = caseService;
    }

    /** @throws TokenBudgetExceededException if the estimated call would exceed budget; the Case is suspended as a side effect. */
    @Transactional
    public void checkBeforeCall(UUID caseId, long estimatedTokens) {
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));
        if (kase.wouldExceedBudget(estimatedTokens)) {
            caseService.suspend(caseId, "token budget would be exceeded by estimated " + estimatedTokens + " tokens");
            throw new TokenBudgetExceededException("case " + caseId + " suspended: token budget exceeded");
        }
    }

    @Transactional
    public void recordUsage(UUID caseId, long actualTokens) {
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));
        kase.recordTokensSpent(actualTokens);
        caseRepository.save(kase);
    }
}
