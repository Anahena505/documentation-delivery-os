package com.d2os.casecore.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request body for POST /cases (contracts/api.yaml — cases tag). */
public record CreateCaseRequest(@NotNull UUID submissionId, @NotNull UUID featureId) {}
