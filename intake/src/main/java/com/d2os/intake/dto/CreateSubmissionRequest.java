package com.d2os.intake.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Request body for POST /submissions (FR-001). formData is opaque — treated as data (AD-12). */
public record CreateSubmissionRequest(
    @NotNull Map<String, Object> formData, List<String> sensitivityTags) {}
