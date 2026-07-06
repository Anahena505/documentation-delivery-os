package com.d2os.intake;

import com.d2os.intake.dto.ConfirmClassificationRequest;
import com.d2os.intake.dto.CreateSubmissionRequest;
import com.d2os.tenancy.WorkspaceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Intake orchestration: create + classify (T023/T024), and human confirm (T025). */
@Service
public class SubmissionService {

    private final ProblemSubmissionRepository repository;
    private final ClassificationService classificationService;
    private final ObjectMapper objectMapper;

    public SubmissionService(ProblemSubmissionRepository repository,
                             ClassificationService classificationService,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.classificationService = classificationService;
        this.objectMapper = objectMapper;
    }

    /** FR-001 + FR-002: persist the submission (form as opaque data) and classify it. */
    @Transactional
    public ProblemSubmission createAndClassify(CreateSubmissionRequest request) {
        UUID workspaceId = WorkspaceContext.require();
        String formJson = toJson(request.formData());
        String tagsJson = request.sensitivityTags() == null ? "[]" : toJson(request.sensitivityTags());

        ProblemSubmission submission = new ProblemSubmission(
                UUID.randomUUID(), workspaceId, formJson, tagsJson, "api");

        // The form is passed to classification as structured data only — never interpreted as
        // instructions (AD-12). Prompt-level delimiting (T1-a) is enforced in PromptDefinition bodies.
        ClassificationResult result = classificationService.classify(request.formData());
        submission.applyClassification(result);

        return repository.save(submission);
    }

    /** FR-002: human confirms (or overrides) the classification before a Case may open. */
    @Transactional
    public ProblemSubmission confirmClassification(UUID submissionId, ConfirmClassificationRequest request) {
        ProblemSubmission submission = repository.findById(submissionId)
                .orElseThrow(() -> new NoSuchElementException("submission " + submissionId));
        // TODO(T017): also write a formal D1 Decision + AuditEntry via AuditWriter once available.
        submission.confirm("api", request.confirmedCaseType());
        return repository.save(submission);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserializable submission payload", e);
        }
    }
}
