package com.d2os.intake;

import com.d2os.casecore.spi.SubmissionLookup;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing casecore's {@link SubmissionLookup} port from the intake read model.
 * This is the seam that lets casecore open a Case without depending on the intake module.
 *
 * <p>Phase 4 (T012, US1): {@code confirmed} and {@code caseTypeKey} now read the classification
 * columns (V18) rather than the Phase 1-3 {@code status}/{@code classificationCaseType} fields —
 * {@code confirmed} is {@code classification_status = CONFIRMED}, and the case-type key is the
 * lower-cased {@code confirmed_case_type} (the catalog's case_type definitions are keyed lower-case,
 * e.g. {@code "initiation"}, while the classification enum is upper-case, e.g. {@code "INITIATION"}).
 * {@link ProblemSubmission#confirm} keeps these columns in sync for callers still using the legacy
 * {@code /confirm-classification} endpoint, so both confirm paths are honored uniformly here.
 */
@Component
public class IntakeSubmissionLookup implements SubmissionLookup {

    private final ProblemSubmissionRepository repository;

    public IntakeSubmissionLookup(ProblemSubmissionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SubmissionInfo> find(UUID submissionId) {
        return repository.findById(submissionId).map(s -> {
            boolean confirmed = "CONFIRMED".equals(s.getClassificationStatus());
            String caseTypeKey = confirmed && s.getConfirmedCaseType() != null
                    ? s.getConfirmedCaseType().toLowerCase(Locale.ROOT)
                    : s.getClassificationCaseType();
            return new SubmissionInfo(s.getId(), s.getWorkspaceId(), caseTypeKey, confirmed, s.getFormData());
        });
    }
}
