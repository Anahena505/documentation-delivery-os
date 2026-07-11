package com.d2os.persona;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseService;
import com.d2os.casecore.WorkspaceBudgetService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 008 T010 — fast unit test of {@link TokenBudgetGuard#checkBeforeCall} boundary behavior. No
 * Spring context, no DB: the repository/services are Mockito mocks, but the actual admit/reject
 * decision is driven by the REAL {@link CaseInstance#wouldExceedBudget(long)} logic ({@code
 * tokensSpent + estimatedTokens > tokenBudget}), constructed with a known budget.
 *
 * <p>Boundary: a fresh case with budget {@code 1000} and {@code 0} spent admits an estimate of
 * exactly {@code 1000} (at cap) and rejects {@code 1001} (one over). The workspace rollup is mocked
 * to never exceed, so the Case-budget branch alone governs each assertion.
 */
class TokenBudgetGuardTest {

  private static final long BUDGET = 1000L;

  private CaseInstance caseWithBudget() {
    // tokensSpent defaults to 0 in the entity; budget is BUDGET.
    return new CaseInstance(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "assessment",
        "1.0.0",
        BUDGET,
        "user:tester");
  }

  @Test
  void usageAtCap_isAdmitted() {
    CaseInstanceRepository repo = mock(CaseInstanceRepository.class);
    CaseService caseService = mock(CaseService.class);
    WorkspaceBudgetService workspaceBudget = mock(WorkspaceBudgetService.class);
    CaseInstance kase = caseWithBudget();
    UUID caseId = kase.getId();
    when(repo.findById(caseId)).thenReturn(Optional.of(kase));
    when(workspaceBudget.wouldExceed(any(), anyLong())).thenReturn(false);

    TokenBudgetGuard guard = new TokenBudgetGuard(repo, caseService, workspaceBudget);

    // 0 spent + 1000 estimated == 1000 budget: NOT over cap -> admitted, no suspend.
    assertDoesNotThrow(() -> guard.checkBeforeCall(caseId, BUDGET));
    verify(caseService, never()).suspend(any(), anyString());
  }

  @Test
  void usageOneUnderCap_isAdmitted() {
    CaseInstanceRepository repo = mock(CaseInstanceRepository.class);
    CaseService caseService = mock(CaseService.class);
    WorkspaceBudgetService workspaceBudget = mock(WorkspaceBudgetService.class);
    CaseInstance kase = caseWithBudget();
    UUID caseId = kase.getId();
    when(repo.findById(caseId)).thenReturn(Optional.of(kase));
    when(workspaceBudget.wouldExceed(any(), anyLong())).thenReturn(false);

    TokenBudgetGuard guard = new TokenBudgetGuard(repo, caseService, workspaceBudget);

    assertDoesNotThrow(() -> guard.checkBeforeCall(caseId, BUDGET - 1));
    verify(caseService, never()).suspend(any(), anyString());
  }

  @Test
  void usageOverCap_isRejectedAndCaseSuspended() {
    CaseInstanceRepository repo = mock(CaseInstanceRepository.class);
    CaseService caseService = mock(CaseService.class);
    WorkspaceBudgetService workspaceBudget = mock(WorkspaceBudgetService.class);
    CaseInstance kase = caseWithBudget();
    UUID caseId = kase.getId();
    when(repo.findById(caseId)).thenReturn(Optional.of(kase));
    when(workspaceBudget.wouldExceed(any(), anyLong())).thenReturn(false);

    TokenBudgetGuard guard = new TokenBudgetGuard(repo, caseService, workspaceBudget);

    // 0 spent + 1001 estimated == 1001 > 1000 budget: over cap -> rejected, case suspended.
    assertThrows(
        TokenBudgetExceededException.class, () -> guard.checkBeforeCall(caseId, BUDGET + 1));
    verify(caseService).suspend(eq(caseId), anyString());
  }
}
