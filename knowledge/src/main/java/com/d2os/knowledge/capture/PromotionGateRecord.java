package com.d2os.knowledge.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per promotion-gate outcome per candidate (T024, FR-013, V13 {@code
 * promotion_gate_record}). The auditable pipeline trail alongside the Decision/AuditEntry rows.
 * Append-only (V13 REVOKEs UPDATE/DELETE); the partial unique index {@code uq_promotion_gate_pass}
 * enforces at-most-one {@code PASS} per {@code (candidate, gate)} at the DB layer, so the pipeline
 * cannot double-pass a gate.
 */
@Entity
@Table(name = "promotion_gate_record")
public class PromotionGateRecord {

  public enum Gate {
    PREFILTER,
    CURATION,
    D4
  }

  public enum Outcome {
    PASS,
    REJECT
  }

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "candidate_id", nullable = false)
  private UUID candidateId;

  @Column(nullable = false)
  private String gate;

  @Column(nullable = false)
  private String outcome;

  @Column(name = "decision_id")
  private UUID decisionId;

  @Column(nullable = false)
  private String actor;

  @Column private String detail;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  protected PromotionGateRecord() {}

  public PromotionGateRecord(
      UUID id,
      UUID workspaceId,
      UUID candidateId,
      Gate gate,
      Outcome outcome,
      UUID decisionId,
      String actor,
      String detail) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.candidateId = candidateId;
    this.gate = gate.name();
    this.outcome = outcome.name();
    this.decisionId = decisionId;
    this.actor = actor;
    this.detail = detail;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getCandidateId() {
    return candidateId;
  }

  public String getGate() {
    return gate;
  }

  public String getOutcome() {
    return outcome;
  }

  public UUID getDecisionId() {
    return decisionId;
  }

  public String getActor() {
    return actor;
  }

  public String getDetail() {
    return detail;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
