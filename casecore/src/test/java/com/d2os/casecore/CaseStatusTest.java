package com.d2os.casecore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CaseStatusTest {

  @Test
  void happyPathTransitionsAreLegal() {
    assertTrue(CaseStatus.Submitted.canTransitionTo(CaseStatus.Classified));
    assertTrue(CaseStatus.Classified.canTransitionTo(CaseStatus.Planned));
    assertTrue(CaseStatus.Planned.canTransitionTo(CaseStatus.Running));
    assertTrue(CaseStatus.Running.canTransitionTo(CaseStatus.Delivered));
  }

  @Test
  void budgetAndEscalationBranchesAreLegal() {
    assertTrue(CaseStatus.Running.canTransitionTo(CaseStatus.Suspended)); // FR-012
    assertTrue(CaseStatus.Running.canTransitionTo(CaseStatus.Escalated)); // FR-005
    assertTrue(CaseStatus.Suspended.canTransitionTo(CaseStatus.Running));
    assertTrue(CaseStatus.Escalated.canTransitionTo(CaseStatus.Cancelled));
  }

  @Test
  void terminalStatesAllowNoFurtherTransition() {
    assertTrue(CaseStatus.Delivered.isTerminal());
    assertTrue(CaseStatus.Cancelled.isTerminal());
    assertFalse(CaseStatus.Delivered.canTransitionTo(CaseStatus.Running));
  }

  @Test
  void illegalJumpsAreRejected() {
    assertFalse(CaseStatus.Submitted.canTransitionTo(CaseStatus.Delivered));
    assertFalse(CaseStatus.Planned.canTransitionTo(CaseStatus.Delivered));
    assertFalse(CaseStatus.Classified.canTransitionTo(CaseStatus.Running));
  }
}
