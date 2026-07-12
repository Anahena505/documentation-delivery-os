package com.d2os.casecore.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A periodic tamper-evidence seal over a contiguous range of the append-only {@code audit_entry}
 * stream (V21 {@code audit_chain_segment}, Phase 7 US5, T6-b, research R5). Per-workspace hash
 * chain: {@code segmentHash} covers this range's canonical serialization; {@code prevSegmentHash}
 * chains to the prior segment (genesis = 64 zero characters). Append-only except {@code
 * lastVerifiedAt}, which {@link AuditChainVerifier} updates.
 */
@Entity
@Table(name = "audit_chain_segment")
public class AuditChainSegment {

  public static final String GENESIS_HASH = "0".repeat(64);

  @Id private UUID id;

  @Column(name = "workspace_id", nullable = false)
  private UUID workspaceId;

  @Column(name = "segment_seq", nullable = false)
  private long segmentSeq;

  @Column(name = "from_entry_id", nullable = false)
  private UUID fromEntryId;

  @Column(name = "to_entry_id", nullable = false)
  private UUID toEntryId;

  @Column(name = "entry_count", nullable = false)
  private int entryCount;

  @Column(name = "segment_hash", nullable = false)
  private String segmentHash;

  @Column(name = "prev_segment_hash", nullable = false)
  private String prevSegmentHash;

  @Column(name = "sealed_at", nullable = false)
  private OffsetDateTime sealedAt = OffsetDateTime.now();

  @Column(name = "last_verified_at")
  private OffsetDateTime lastVerifiedAt;

  protected AuditChainSegment() {}

  public AuditChainSegment(
      UUID id,
      UUID workspaceId,
      long segmentSeq,
      UUID fromEntryId,
      UUID toEntryId,
      int entryCount,
      String segmentHash,
      String prevSegmentHash) {
    this.id = id;
    this.workspaceId = workspaceId;
    this.segmentSeq = segmentSeq;
    this.fromEntryId = fromEntryId;
    this.toEntryId = toEntryId;
    this.entryCount = entryCount;
    this.segmentHash = segmentHash;
    this.prevSegmentHash = prevSegmentHash;
  }

  public void markVerified(OffsetDateTime at) {
    this.lastVerifiedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWorkspaceId() {
    return workspaceId;
  }

  public long getSegmentSeq() {
    return segmentSeq;
  }

  public UUID getFromEntryId() {
    return fromEntryId;
  }

  public UUID getToEntryId() {
    return toEntryId;
  }

  public int getEntryCount() {
    return entryCount;
  }

  public String getSegmentHash() {
    return segmentHash;
  }

  public String getPrevSegmentHash() {
    return prevSegmentHash;
  }

  public OffsetDateTime getSealedAt() {
    return sealedAt;
  }

  public OffsetDateTime getLastVerifiedAt() {
    return lastVerifiedAt;
  }
}
