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
 * Runs the four analysis specialists (security, ux, data, infrastructure) concurrently as one BPMN
 * step (US2, FR-002/003/004). Each specialist executes on the bounded {@code specialistExecutor}
 * pool in its own {@code REQUIRES_NEW} transaction with the workspace RLS context bound, so they
 * run with genuine wall-clock overlap and never block one another. The step joins on all four
 * (FR-004), then exposes {@code validated = all four passed} so the following gateway routes a
 * partial failure to the escalation wait state (FR-005) — every specialist's output is already
 * persisted, so no sibling work is lost.
 */
@Component("parallelSpecialistsDelegate")
public class ParallelSpecialistsDelegate implements JavaDelegate {

  private static final List<String> SPECIALISTS =
      List.of("security-architect", "ux-architect", "data-architect", "infrastructure-engineer");

  private final PersonaExecutionService personaExecutionService;
  private final WorkspaceRlsBinder workspaceRlsBinder;
  private final ProgressEmitter progressEmitter;
  private final PlatformTransactionManager transactionManager;
  // Plain executor (not a Spring bean) so it can't collide with Flowable's async-executor
  // auto-configuration, which wires any Spring AsyncTaskExecutor bean as the engine's job executor.
  // Sized so the four specialists of many concurrent cases don't serialize behind too few threads.
  private final ExecutorService executor;

  public ParallelSpecialistsDelegate(
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
              Thread t = new Thread(r, "specialist");
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

    // Emit under the delegate's own bound transaction context (this job's connection).
    workspaceRlsBinder.bindCurrentTransaction(workspaceId);
    progressEmitter.emit(
        workspaceId, caseId, ProgressEvent.Kind.BRANCH_FORKED, "analysis-specialists", null);

    List<Future<Boolean>> futures =
        SPECIALISTS.stream()
            .map(
                key ->
                    executor.submit(
                        (Callable<Boolean>) () -> runSpecialist(caseId, workspaceId, key)))
            .toList();

    boolean allValidated = true;
    for (Future<Boolean> f : futures) {
      try {
        allValidated &= f.get(); // JOIN — wait for every branch (FR-004)
      } catch (Exception e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("specialist branch failed to complete", e);
      }
    }

    progressEmitter.emit(
        workspaceId, caseId, ProgressEvent.Kind.BRANCH_JOINED, "analysis-specialists", null);
    // Branch-local so the following exclusive gateway routes a partial failure to the wait state.
    execution.setVariableLocal("validated", allValidated);
  }

  /**
   * One specialist on a pool thread: its own tx + RLS binding so it never contends with siblings.
   */
  private boolean runSpecialist(UUID caseId, UUID workspaceId, String personaKey) {
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
