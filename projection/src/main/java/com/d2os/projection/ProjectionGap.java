package com.d2os.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A projection-sufficiency finding (V28, data-model.md ProjectionGap, research R6/FR-011), written
 * by {@code PayloadSufficiencyAuditor} (T011, a later phase) when a consumed outbox event is
 * missing fields the projector's mapping contract requires. {@code status} transitions {@code OPEN
 * -> RESOLVED} (e.g. after an emitter fix + rebuild) — see V28's migration note for why this table
 * is NOT append-only despite data-model.md's "append-only" label.
 */
@Entity
@Table(name = "projection_gap")
public class ProjectionGap {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "missing_fields", nullable = false)
  private String[] missingFields;

  @Column(name = "detected_at", nullable = false)
  private OffsetDateTime detectedAt = OffsetDateTime.now();

  @Column(nullable = false)
  private String status = Status.OPEN.name();

  protected ProjectionGap() {}

  public ProjectionGap(
      UUID id,
      UUID workspaceId,
      UUID eventId,
      String eventType,
      String[] missingFields,
      OffsetDateTime detectedAt,
      Status status) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.eventId = eventId;
    this.eventType = eventType;
    this.missingFields = missingFields;
    this.detectedAt = detectedAt != null ? detectedAt : OffsetDateTime.now();
    this.status = (status != null ? status : Status.OPEN).name();
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public String[] getMissingFields() {
    return missingFields;
  }

  public OffsetDateTime getDetectedAt() {
    return detectedAt;
  }

  public String getStatus() {
    return status;
  }

  public enum Status {
    OPEN,
    RESOLVED
  }
}
