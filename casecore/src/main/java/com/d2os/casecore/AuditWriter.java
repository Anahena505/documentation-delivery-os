package com.d2os.casecore;

import com.d2os.tenancy.security.AuthenticatedPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same-transaction audit + outbox write helper (T017, Principle V). {@code MANDATORY} propagation
 * means this can only be called from within an already-open transaction — it never opens its own,
 * so the audit/event rows are guaranteed to commit or roll back atomically with the state change
 * they describe.
 */
@Component
public class AuditWriter {

  private final AuditEntryRepository auditRepository;
  private final EventOutboxRepository outboxRepository;
  private final ObjectMapper objectMapper;

  public AuditWriter(
      AuditEntryRepository auditRepository,
      EventOutboxRepository outboxRepository,
      ObjectMapper objectMapper) {
    this.auditRepository = auditRepository;
    this.outboxRepository = outboxRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(
      UUID workspaceId,
      String subjectType,
      UUID subjectId,
      String action,
      String actor,
      Map<String, Object> details) {
    String detailsJson = toJson(details);
    auditRepository.save(
        new AuditEntryRecord(
            UUID.randomUUID(), workspaceId, subjectType, subjectId, action, actor, detailsJson));
    outboxRepository.save(
        new EventOutboxRecord(
            UUID.randomUUID(), workspaceId, subjectType, subjectId, action, detailsJson));
  }

  /**
   * 008 US5 (T051): audit a <b>trust-sensitive decision</b> (gate approve/reject/reopen, catalog
   * publish, cross-boundary promotion, package grant), additionally stamping the AUTHENTICATED
   * decision-maker's {@code actor_user_id} and the {@code actor_role} they were authorized under
   * (FR-013, data-model.md §2). {@code requiredRole} is the role the action is gated on; the stamp
   * is recorded only if the principal actually holds it (else 403 via {@link
   * com.d2os.tenancy.security.ActorRoleNotHeldException}).
   *
   * <p><b>Default-mode no-op:</b> in the workspace-scoping posture there is no per-user principal,
   * so {@link AuthenticatedPrincipal#resolveActor} returns empty, both actor columns stay NULL, and
   * the persisted row + its audit-hash canonicalization are byte-identical to a plain {@link
   * #record} write — every existing (non-OIDC) integration suite is unaffected. The {@code
   * event_outbox} row is identical to {@link #record}'s (the projection carries no actor columns).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordDecision(
      UUID workspaceId,
      String subjectType,
      UUID subjectId,
      String action,
      String actor,
      String requiredRole,
      Map<String, Object> details) {
    AuthenticatedPrincipal.ActorStamp stamp =
        AuthenticatedPrincipal.resolveActor(requiredRole).orElse(null);
    String actorUserId = stamp == null ? null : stamp.userId();
    String actorRole = stamp == null ? null : stamp.role();
    String detailsJson = toJson(details);
    auditRepository.save(
        new AuditEntryRecord(
            UUID.randomUUID(),
            workspaceId,
            subjectType,
            subjectId,
            action,
            actor,
            actorUserId,
            actorRole,
            detailsJson));
    outboxRepository.save(
        new EventOutboxRecord(
            UUID.randomUUID(), workspaceId, subjectType, subjectId, action, detailsJson));
  }

  /**
   * Audit an event in its OWN committed transaction (Phase 3, T036, T2-c). For security-violation
   * audits the semantics are inverted from {@link #record}: the audit row must SURVIVE the caller's
   * rollback — a blocked cross-workspace injection aborts the surrounding work by throwing, and a
   * MANDATORY-propagated write would be swept away with it. {@code REQUIRES_NEW} commits the audit
   * independently, so the attempt is durably recorded even though the operation itself was refused.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordDetached(
      UUID workspaceId,
      String subjectType,
      UUID subjectId,
      String action,
      String actor,
      Map<String, Object> details) {
    String detailsJson = toJson(details);
    auditRepository.save(
        new AuditEntryRecord(
            UUID.randomUUID(), workspaceId, subjectType, subjectId, action, actor, detailsJson));
    outboxRepository.save(
        new EventOutboxRecord(
            UUID.randomUUID(), workspaceId, subjectType, subjectId, action, detailsJson));
  }

  private String toJson(Map<String, Object> details) {
    try {
      return objectMapper.writeValueAsString(details == null ? Map.of() : details);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unserializable audit details", e);
    }
  }
}
