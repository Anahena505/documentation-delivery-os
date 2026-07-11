package com.d2os.casecore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A running Case (data-model.md §4). Status transitions are guarded by {@link CaseStatus}. */
@Entity
@Table(name = "case_instance")
public class CaseInstance {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "feature_id", nullable = false)
    private UUID featureId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "case_type_key", nullable = false)
    private String caseTypeKey;

    @Column(name = "case_type_version", nullable = false)
    private String caseTypeVersion;

    @Column(nullable = false)
    private String mode = "mutating";

    @Column(nullable = false)
    private String status = CaseStatus.Submitted.name();

    @Column(name = "token_budget", nullable = false)
    private long tokenBudget;

    @Column(name = "tokens_spent", nullable = false)
    private long tokensSpent;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    protected CaseInstance() {}

    public CaseInstance(UUID id, UUID workspaceId, UUID featureId, UUID submissionId,
                        String caseTypeKey, String caseTypeVersion, long tokenBudget, String createdBy) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.featureId = featureId;
        this.submissionId = submissionId;
        this.caseTypeKey = caseTypeKey;
        this.caseTypeVersion = caseTypeVersion;
        this.tokenBudget = tokenBudget;
        this.createdBy = createdBy;
    }

    public CaseStatus status() {
        return CaseStatus.valueOf(status);
    }

    /** Transition to {@code target}, rejecting any move the state machine forbids. */
    public void transitionTo(CaseStatus target) {
        CaseStatus current = status();
        if (!current.canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(current, target);
        }
        this.status = target.name();
    }

    /** True if spending {@code estimatedTokens} more would exceed the Case's budget (NFR-7). */
    public boolean wouldExceedBudget(long estimatedTokens) {
        return tokensSpent + estimatedTokens > tokenBudget;
    }

    public void recordTokensSpent(long tokens) {
        this.tokensSpent += tokens;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public UUID getFeatureId() { return featureId; }
    public UUID getSubmissionId() { return submissionId; }
    public String getCaseTypeKey() { return caseTypeKey; }
    public String getCaseTypeVersion() { return caseTypeVersion; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public long getTokenBudget() { return tokenBudget; }
    public long getTokensSpent() { return tokensSpent; }
}
