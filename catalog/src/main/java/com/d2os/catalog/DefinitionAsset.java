package com.d2os.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The DefinitionAsset supertype (Principle I). Read-focused JPA mapping; publish/immutability is
 * enforced by the DB trigger (V3). Authoring/publish writes go through {@link DefinitionPublishService}.
 */
@Entity
@Table(name = "definition_asset")
public class DefinitionAsset {

    /**
     * V25 widens the DB CHECK to add {@code InReview} (Draft -&gt; InReview -&gt; Published -&gt;
     * Deprecated, research R2, tasks.md T004/T007): a draft submitted for review is frozen —
     * {@link #updateBody} refuses edits once status leaves {@code Draft}.
     */
    public enum Status { Draft, InReview, Published, Deprecated }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String locale;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String body;

    @Column
    private String checksum;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected DefinitionAsset() {}

    public DefinitionAsset(UUID id, UUID workspaceId, String key, String version, String type,
                           String locale, String body, String createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.key = key;
        this.version = version;
        this.type = type;
        this.status = Status.Draft.name();
        this.locale = locale;
        this.body = body;
        this.createdBy = createdBy;
    }

    /** Publish this definition, computing its checksum (T012, T4-a). Only legal from Draft. */
    public void markPublished(String checksum) {
        this.checksum = checksum;
        this.status = Status.Published.name();
        this.publishedAt = OffsetDateTime.now();
    }

    /**
     * Studio draft editing (tasks.md T007, research R2, FR-001). Content edits are refused unless
     * the row is still {@code Draft} — this is what makes the {@code InReview} freeze real (once
     * {@link #markInReview} flips status, this guard starts rejecting edits) and, together with
     * the V3 immutability trigger on Published rows, means a definition's body can only ever
     * change while it is an editable Draft. Guard/exception style mirrors {@link
     * DefinitionPublishService#publish}'s "Only Draft/... can X; key is status" pattern.
     */
    public void updateBody(String newBody) {
        if (!Status.Draft.name().equals(status)) {
            throw new IllegalStateException(
                    "Only Draft definitions can be edited; " + key + " is " + status);
        }
        this.body = newBody;
    }

    /**
     * Submit-for-review transition (Draft -&gt; InReview, research R2). Added now (T007) so it is
     * cheap for T013 to wire the actual submit-for-review endpoint/gate-open later; not called
     * from anywhere yet in this phase. Only legal from Draft, same guard style as {@link
     * #updateBody}/{@link #markPublished}.
     */
    public void markInReview() {
        if (!Status.Draft.name().equals(status)) {
            throw new IllegalStateException(
                    "Only Draft definitions can move to InReview; " + key + " is " + status);
        }
        this.status = Status.InReview.name();
    }

    /**
     * Pin the content hash at submit-for-review (tasks.md T013, research R2's "content-hash
     * pinned at submission" tamper guard). Deliberately reuses the {@code checksum} column rather
     * than adding a new one: {@code checksum} is otherwise only ever written once, at publish, to
     * exactly {@code sha256(body)} — pinning that same value the moment the body is frozen
     * (immediately after {@link #markInReview()}) costs nothing (no reader branches on
     * {@code checksum != null} to infer Published status; grep-verified) and gives {@link
     * #markPublishedFromReview} a stored baseline to defensively re-check against later, exactly
     * mirroring how a Published row's checksum is simply "the hash that was true when it was
     * frozen." Only legal once the row has actually left Draft.
     */
    public void pinContentHash(String hash) {
        if (!Status.InReview.name().equals(status)) {
            throw new IllegalStateException(
                    "Content hash can only be pinned once InReview; " + key + " is " + status);
        }
        this.checksum = hash;
    }

    /**
     * Gate-integrated publish (tasks.md T016, US2): the InReview -&gt; Published transition once
     * every opened {@code DEFINITION_VERSION} gate has reached {@code APPROVED} — the studio's
     * {@code PublishService} (module placement note: lives in {@code studio}, not {@code catalog},
     * because it also needs {@code governance}'s {@code GateInstanceRepository}, and {@code
     * governance} already depends on {@code catalog} — a {@code catalog -&gt; governance} edge
     * would be circular) is the only caller. {@code recomputedHash} is {@code sha256(body)}
     * computed fresh by the caller at publish time; compared against whatever {@link
     * #pinContentHash} recorded at submission as a tamper guard (research R2: "any tamper is
     * detected at publish") — a mismatch can only happen if the row was mutated outside the normal
     * {@link #updateBody} guard (e.g. a direct DB write), since the InReview freeze already blocks
     * every ordinary edit path. This is a SEPARATE method from {@link #markPublished} (the
     * Draft-only, no-gate {@code DefinitionPublishService} primitive {@code CatalogSeedLoader}
     * uses) precisely so that direct-publish path is never touched by this phase (Phase 1-2's
     * design decision) — two distinct publish primitives, two distinct guards, sharing only the
     * {@code Status.Published} terminal assignment.
     */
    public void markPublishedFromReview(String recomputedHash) {
        if (!Status.InReview.name().equals(status)) {
            throw new IllegalStateException(
                    "Only InReview definitions can be published via gate review; " + key + " is " + status);
        }
        if (checksum != null && !checksum.equals(recomputedHash)) {
            throw new IllegalStateException(
                    "pinned content hash mismatch for " + key + " v" + version
                            + " — body changed after submit-for-review (tamper guard)");
        }
        this.checksum = recomputedHash;
        this.status = Status.Published.name();
        this.publishedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getKey() { return key; }
    public String getVersion() { return version; }
    public String getType() { return type; }
    public String getStatus() { return status; }
    public String getLocale() { return locale; }
    public String getBody() { return body; }
    public String getChecksum() { return checksum; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
