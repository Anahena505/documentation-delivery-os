package com.d2os.casecore.dto;

import com.d2os.casecore.CaseInstance;

import java.util.UUID;

/** API view of a Case (contracts/api.yaml #/CaseInstance). */
public record CaseResponse(
        UUID id,
        UUID featureId,
        String status,
        String mode,
        String caseTypeKey,
        String caseTypeVersion,
        long tokenBudget,
        long tokensSpent,
        Object definitionSnapshot
) {
    public static CaseResponse from(CaseInstance k, Object snapshotEntries) {
        return new CaseResponse(
                k.getId(), k.getFeatureId(), k.getStatus(), k.getMode(),
                k.getCaseTypeKey(), k.getCaseTypeVersion(),
                k.getTokenBudget(), k.getTokensSpent(), snapshotEntries);
    }
}
