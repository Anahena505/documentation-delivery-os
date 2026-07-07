package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface CaseInstanceRepository extends JpaRepository<CaseInstance, UUID> {

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
