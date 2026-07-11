package com.d2os.intake.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /submissions/{id}/case-type/confirm} (contracts/api.yaml, T011, US1).
 * {@code caseType} must be one of INITIATION / ASSESSMENT / ENHANCEMENT (validated in the service —
 * UNDETERMINED is a valid proposal but never a valid confirmation). {@code rationale} is expected
 * (not enforced here) when overriding the proposal.
 */
public record ConfirmCaseTypeRequest(
        @NotBlank String caseType,
        String rationale
) {
}
