package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CaseInstanceRepository extends JpaRepository<CaseInstance, UUID> {

    /**
     * Phase 5 (T023/T024, US3, research R4): the Feature's most recent Case in a given status — used
     * both by the Enhancement confirm-time 422 gate (T024, "has this Feature ever delivered a
     * baseline?") and by {@code BaselineResolutionDelegate} (T023, "resolve the specific Delivered Case
     * whose pinned ArtifactRevisions become this Enhancement case's baseline set"). {@code status} is a
     * plain string match against {@link CaseInstance}'s stored status column (e.g. {@code
     * CaseStatus.Delivered.name()}) rather than a typed enum parameter, matching how status is already
     * persisted/compared elsewhere in this entity.
     */
    Optional<CaseInstance> findFirstByFeatureIdAndStatusOrderByCreatedAtDesc(UUID featureId, String status);

    /**
     * Atomically increment only the token counter (T036 fix). The four parallel specialists each record
     * usage concurrently; saving the whole entity would let one specialist's stale in-memory status
     * (e.g. Running, loaded before a sibling escalated the Case) overwrite a committed status change.
     * A targeted UPDATE touches only tokens_spent, so a concurrent escalation/suspension is preserved.
     */
    @Modifying(clearAutomatically = true)
    @Query("update CaseInstance c set c.tokensSpent = c.tokensSpent + :tokens where c.id = :id")
    void addTokensSpent(@Param("id") UUID id, @Param("tokens") long tokens);
}
