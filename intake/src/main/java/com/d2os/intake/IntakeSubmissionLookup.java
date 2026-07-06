package com.d2os.intake;

import com.d2os.casecore.spi.SubmissionLookup;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing casecore's {@link SubmissionLookup} port from the intake read model.
 * This is the seam that lets casecore open a Case without depending on the intake module.
 */
@Component
public class IntakeSubmissionLookup implements SubmissionLookup {

    private final ProblemSubmissionRepository repository;

    public IntakeSubmissionLookup(ProblemSubmissionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<SubmissionInfo> find(UUID submissionId) {
        return repository.findById(submissionId).map(s -> new SubmissionInfo(
                s.getId(),
                s.getWorkspaceId(),
                s.getClassificationCaseType(),
                ProblemSubmission.Status.confirmed.name().equals(s.getStatus())
        ));
    }
}
