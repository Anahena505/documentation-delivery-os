package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Transactional outbox row (AD-6) — written in the same tx as the aggregate change it describes.
 */
@Entity
@Table(name = "event_outbox")
public class EventOutboxRecord {

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "aggregate_type", nullable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String payload;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  protected EventOutboxRecord() {}

  public EventOutboxRecord(
      UUID id,
      UUID workspaceId,
      String aggregateType,
      UUID aggregateId,
      String eventType,
      String payload) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.payload = payload;
  }
}
