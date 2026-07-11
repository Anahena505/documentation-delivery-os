package com.d2os.governance.escalation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A visible, durable record of one advisory SLA timer firing (V20 {@code escalation_activation},
 * Phase 6 US4, T033, research R4, Q9). Append-only at the DB grant level (no UPDATE/DELETE for
 * {@code d2os_app}) — firing is a fact, never edited.
 */
@Entity
@Table(name = "escalation_activation")
public class EscalationActivation {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "gate_instance_id", nullable = false)
  private UUID gateInstanceId;

  @Column(name = "policy_key", nullable = false)
  private String policyKey;

  @Column(name = "policy_version", nullable = false)
  private int policyVersion;

  @Column(name = "step_index", nullable = false)
  private int stepIndex;

  @Column(nullable = false)
  private String role;

  @Column(name = "assignee_resolved", nullable = false)
  private boolean assigneeResolved;

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Column(name = "fired_at", nullable = false)
  private OffsetDateTime firedAt = OffsetDateTime.now();

  protected EscalationActivation() {}

  public EscalationActivation(
      UUID id,
      UUID workspaceId,
      UUID gateInstanceId,
      String policyKey,
      int policyVersion,
      int stepIndex,
      String role,
      boolean assigneeResolved) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.gateInstanceId = gateInstanceId;
    this.policyKey = policyKey;
    this.policyVersion = policyVersion;
    this.stepIndex = stepIndex;
    this.role = role;
    this.assigneeResolved = assigneeResolved;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getGateInstanceId() {
    return gateInstanceId;
  }

  public String getPolicyKey() {
    return policyKey;
  }

  public int getPolicyVersion() {
    return policyVersion;
  }

  public int getStepIndex() {
    return stepIndex;
  }

  public String getRole() {
    return role;
  }

  public boolean isAssigneeResolved() {
    return assigneeResolved;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getFiredAt() {
    return firedAt;
  }
}
