package com.d2os.persona.spi;

import java.util.Optional;
import java.util.UUID;

/** Port letting persona read raw submission form data without depending on intake (dependency inversion). */
public interface SubmissionDataPort {
    Optional<String> findFormDataJson(UUID submissionId);
}
