package com.d2os.intake;

import com.d2os.persona.spi.SubmissionDataPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Adapter implementing persona's {@link SubmissionDataPort} from the intake read model. */
@Component
public class IntakeSubmissionDataPort implements SubmissionDataPort {

    private final ProblemSubmissionRepository repository;

    public IntakeSubmissionDataPort(ProblemSubmissionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<String> findFormDataJson(UUID submissionId) {
        return repository.findById(submissionId).map(ProblemSubmission::getFormData);
    }
}
