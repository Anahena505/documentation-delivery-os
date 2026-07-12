package com.d2os.orchestration;

import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseStatus;
import com.d2os.observability.JobMetrics;
import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the standalone {@code knowledge-capture} process when a Case reaches {@code Delivered}
 * (T020, FR-008, research R6). The Case's {@code Delivered} transition writes an {@code
 * event_outbox} row in the same transaction (via {@code AuditWriter}); this consumer reacts to that
 * delivery.
 *
 * <p><b>Why an engine-driven sweep, not a generic outbox relay:</b> {@code event_outbox} is
 * SELECT/INSERT-only for the least-privilege {@code d2os_app} role (V8 REVOKEs UPDATE/DELETE), so a
 * consumer cannot flip {@code published_at} to mark a row consumed, and the outbox is RLS-scoped so
 * a scheduler thread cannot read every workspace's rows at once. So — exactly as {@link
 * ReconciliationJob} does — this sweep discovers work from Flowable's engine tables (which carry no
 * RLS), reading the {@code workspaceId} process variable to bind the correct RLS context before
 * touching any domain table. The initiation-v2 process instance completes precisely when the Case
 * is delivered, so its finished instances are the delivery signal.
 *
 * <p><b>Idempotency (no double-start):</b> both processes use the Case id as their business key, so
 * a Case already has a {@code knowledge-capture} instance (running or historic) with that key iff
 * capture was already triggered. The sweep skips those; {@code CaptureService} is additionally
 * idempotent per case (it returns existing candidates rather than re-harvesting), so an
 * at-least-once trigger is safe.
 */
@Component
public class CaseDeliveredKnowledgeTrigger {

  private static final Logger log = LoggerFactory.getLogger(CaseDeliveredKnowledgeTrigger.class);

  private static final String INITIATION_PROCESS_KEY = "initiation-v2";
  private static final String CAPTURE_PROCESS_KEY = "knowledge-capture";

  private final HistoryService historyService;
  private final RuntimeService runtimeService;
  private final CaseInstanceRepository caseRepository;
  private final WorkspaceRlsBinder workspaceRlsBinder;
  private final JobMetrics jobMetrics;

  public CaseDeliveredKnowledgeTrigger(
      HistoryService historyService,
      RuntimeService runtimeService,
      CaseInstanceRepository caseRepository,
      WorkspaceRlsBinder workspaceRlsBinder,
      JobMetrics jobMetrics) {
    this.historyService = historyService;
    this.runtimeService = runtimeService;
    this.caseRepository = caseRepository;
    this.workspaceRlsBinder = workspaceRlsBinder;
    this.jobMetrics = jobMetrics;
  }

  @Scheduled(
      fixedDelayString = "${d2os.knowledge.capture-trigger-interval-ms:30000}",
      initialDelayString = "${d2os.knowledge.capture-trigger-interval-ms:30000}")
  @SchedulerLock(name = "delivered-knowledge-trigger", lockAtMostFor = "PT2M")
  public void sweep() {
    jobMetrics.time(
        "delivered-knowledge-trigger",
        () -> {
          List<HistoricProcessInstance> finished =
              historyService
                  .createHistoricProcessInstanceQuery()
                  .processDefinitionKey(INITIATION_PROCESS_KEY)
                  .finished()
                  .list();
          for (HistoricProcessInstance pi : finished) {
            try {
              maybeStartCapture(pi.getBusinessKey());
            } catch (Exception e) {
              log.warn(
                  "knowledge-capture trigger failed for case {}: {}",
                  pi.getBusinessKey(),
                  e.toString());
            }
          }
        });
  }

  /**
   * Start {@code knowledge-capture} for one delivered case if it is Delivered and has no capture
   * instance yet. Own transaction per case so one failure never blocks the rest of the sweep.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void maybeStartCapture(String businessKey) {
    if (businessKey == null) return;
    UUID caseId = UUID.fromString(businessKey);

    // Already triggered? A running or historic knowledge-capture instance keyed by this case id.
    boolean already =
        runtimeService
                    .createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey)
                    .processDefinitionKey(CAPTURE_PROCESS_KEY)
                    .count()
                > 0
            || historyService
                    .createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey)
                    .processDefinitionKey(CAPTURE_PROCESS_KEY)
                    .count()
                > 0;
    if (already) return;

    // Resolve the case's workspace from the finished initiation instance's variable (no RLS on the
    // engine tables), then bind it before reading the RLS-scoped case row.
    HistoricProcessInstance initiation =
        historyService
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .processDefinitionKey(INITIATION_PROCESS_KEY)
            .includeProcessVariables()
            .finished()
            .list()
            .stream()
            .findFirst()
            .orElse(null);
    if (initiation == null) return;
    Object wsVar = initiation.getProcessVariables().get("workspaceId");
    if (wsVar == null) return;
    UUID workspaceId = UUID.fromString(wsVar.toString());

    WorkspaceContext.set(workspaceId);
    try {
      workspaceRlsBinder.bindCurrentTransaction(workspaceId);
      Optional<CaseInstance> found = caseRepository.findById(caseId);
      if (found.isEmpty() || found.get().status() != CaseStatus.Delivered) {
        return; // not (yet) delivered in the domain — nothing to capture
      }

      runtimeService.startProcessInstanceByKey(
          CAPTURE_PROCESS_KEY,
          businessKey, // businessKey = case id (correlation, and the idempotency key)
          Map.of("caseInstanceId", businessKey, "workspaceId", workspaceId.toString()));
      log.info("started knowledge-capture for delivered case {}", caseId);
    } finally {
      WorkspaceContext.clear();
    }
  }
}
