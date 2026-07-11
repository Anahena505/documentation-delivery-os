package com.d2os.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The projector's own incremental-run bookkeeping (V28, data-model.md ProjectionCheckpoint,
 * research R4) — NOT part of the graph itself. {@code outboxWatermark} tracks the highest {@code
 * event_outbox.seq} (V28's ALTER on casecore's event_outbox table) this consumer has processed
 * per workspace; a rebuild (T009, a later phase) ignores this and replays from zero.
 */
@Entity
@Table(name = "projection_checkpoint")
@IdClass(ProjectionCheckpointId.class)
public class ProjectionCheckpoint {

    @Id
    @Column(nullable = false)
    private String consumer;

    @Id
    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "outbox_watermark", nullable = false)
    private long outboxWatermark;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected ProjectionCheckpoint() {}

    public ProjectionCheckpoint(String consumer, UUID workspaceId, long outboxWatermark, OffsetDateTime updatedAt) {
        this.consumer = consumer;
        this.workspaceId = workspaceId;
        this.outboxWatermark = outboxWatermark;
        this.updatedAt = updatedAt;
    }

    public String getConsumer() { return consumer; }
    public UUID getWorkspaceId() { return workspaceId; }
    public long getOutboxWatermark() { return outboxWatermark; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
