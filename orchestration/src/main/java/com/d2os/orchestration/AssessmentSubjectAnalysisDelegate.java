package com.d2os.orchestration;

import com.d2os.casecore.progress.ProgressEmitter;
import com.d2os.casecore.progress.ProgressEvent;
import com.d2os.persona.PersonaExecutionService;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the Assessment case type's subject-analysis personas (capability, gap, risk) concurrently as
 * one BPMN step (T014, research R7, US2). Structurally identical to Initiation's {@link
 * ParallelSpecialistsDelegate} (US2/Phase 2, whose own javadoc explains why an app-level fan-out is
 * used instead of a native BPMN parallel gateway — embedded-Flowable process-instance
 * optimistic-lock contention would otherwise serialize the branches) — the pattern is reused
 * exactly, only the persona-key roster differs, since {@code ParallelSpecialistsDelegate}'s roster
 * is hardcoded to Initiation's four specialists and isn't parameterized.
 *
 * <p>The exclusive gateway that follows this step and the {@code specialists-wait} receiveTask it
 * routes a partial failure to reuse the SAME activity id convention as {@code
 * initiation-v2.bpmn20.xml} (both processes name their wait state {@code specialists-wait}), so
 * {@link EscalationBridge} — whose {@code findWaitExecution} resolves by process-instance (caseId)
 * + that activity id — needs no changes to resolve escalations raised by an Assessment case's
 * parallel block.
 */
@Component("assessmentSubjectAnalysisDelegate")
public class AssessmentSubjectAnalysisDelegate implements JavaDelegate {

  private static final List<String> SUBJECT_ANALYSTS =
      List.of("capability-analyst", "gap-analyst", "risk-analyst");

  private final PersonaExecutionService personaExecutionService;
  private final WorkspaceRlsBinder workspaceRlsBinder;
  private final ProgressEmitter progressEmitter;
  private final PlatformTransactionManager transactionManager;
  private final ExecutorService executor;

  public AssessmentSubjectAnalysisDelegate(
      PersonaExecutionService personaExecutionService,
      WorkspaceRlsBinder workspaceRlsBinder,
      ProgressEmitter progressEmitter,
      PlatformTransactionManager transactionManager,
      @org.springframework.beans.factory.annotation.Value(
              "${d2os.orchestration.specialist-pool-size:16}")
          int poolSize) {
    this.personaExecutionService = personaExecutionService;
    this.workspaceRlsBinder = workspaceRlsBinder;
    this.progressEmitter = progressEmitter;
    this.transactionManager = transactionManager;
    this.executor =
        Executors.newFixedThreadPool(
            poolSize,
            r -> {
              Thread t = new Thread(r, "assessment-subject-analyst");
              t.setDaemon(true);
              return t;
            });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

  @Override
  public void execute(DelegateExecution execution) {
    UUID caseId = UUID.fromString(execution.getProcessInstanceBusinessKey());
    UUID workspaceId = UUID.fromString((String) execution.getVariable("workspaceId"));

    workspaceRlsBinder.bindCurrentTransaction(workspaceId);
    progressEmitter.emit(
        workspaceId,
        caseId,
        ProgressEvent.Kind.BRANCH_FORKED,
        "subject-analysis-specialists",
        null);

    List<Future<Boolean>> futures =
        SUBJECT_ANALYSTS.stream()
            .map(
                key ->
                    executor.submit((Callable<Boolean>) () -> runAnalyst(caseId, workspaceId, key)))
            .toList();

    boolean allValidated = true;
    for (Future<Boolean> f : futures) {
      try {
        allValidated &= f.get();
      } catch (Exception e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("subject-analysis branch failed to complete", e);
      }
    }

    progressEmitter.emit(
        workspaceId,
        caseId,
        ProgressEvent.Kind.BRANCH_JOINED,
        "subject-analysis-specialists",
        null);
    execution.setVariableLocal("validated", allValidated);
  }

  private boolean runAnalyst(UUID caseId, UUID workspaceId, String personaKey) {
    WorkspaceContext.set(workspaceId);
    try {
      DefaultTransactionDefinition def = new DefaultTransactionDefinition();
      def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      TransactionTemplate tx = new TransactionTemplate(transactionManager, def);
      return Boolean.TRUE.equals(
          tx.execute(
              status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                return personaExecutionService.executePersona(
                    caseId, personaKey, "branch-" + personaKey);
              }));
    } finally {
      WorkspaceContext.clear();
    }
  }
}
