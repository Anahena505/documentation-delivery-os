package com.d2os.intake.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /submissions/{id}/confirm-classification (FR-002). */
public record ConfirmClassificationRequest(
        @NotBlank String confirmedCaseType,
        String rationale
) {
}
