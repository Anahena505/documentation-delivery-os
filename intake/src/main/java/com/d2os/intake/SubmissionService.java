package com.d2os.intake;

import com.d2os.casecore.AuditWriter;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.casecore.CaseStatus;
import com.d2os.casecore.DecisionRecord;
import com.d2os.casecore.DecisionRepository;
import com.d2os.intake.dto.ConfirmCaseTypeRequest;
import com.d2os.intake.dto.ConfirmClassificationRequest;
import com.d2os.intake.dto.CreateSubmissionRequest;
import com.d2os.tenancy.WorkspaceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Intake orchestration: create + classify (T023/T024), and human confirm (T025). */
@Service
public class SubmissionService {

    /** contracts/api.yaml CaseTypeClassification#confirmedCaseType enum (UNDETERMINED excluded). */
    private static final Set<String> CONFIRMABLE_CASE_TYPES = Set.of("INITIATION", "ASSESSMENT", "ENHANCEMENT");

    private final ProblemSubmissionRepository repository;
    private final ClassificationService classificationService;
    private final CaseTypeClassificationService caseTypeClassificationService;
    private final AuditWriter auditWriter;
    private final DecisionRepository decisionRepository;
    private final CaseInstanceRepository caseInstanceRepository;
    private final ObjectMapper objectMapper;

    public SubmissionService(ProblemSubmissionRepository repository,
                             ClassificationService classificationService,
                             CaseTypeClassificationService caseTypeClassificationService,
                             AuditWriter auditWriter,
                             DecisionRepository decisionRepository,
                             CaseInstanceRepository caseInstanceRepository,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.classificationService = classificationService;
        this.caseTypeClassificationService = caseTypeClassificationService;
        this.auditWriter = auditWriter;
        this.decisionRepository = decisionRepository;
        this.caseInstanceRepository = caseInstanceRepository;
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

        // Phase 4 (T010, US1, research R5): run the case-type-classification DMN alongside the Phase
        // 1-3 classify step (not a duplicate pipeline — same classify moment, second advisory output)
        // and persist its proposal (INITIATION/ASSESSMENT/ENHANCEMENT/UNDETERMINED).
        String proposedCaseType = caseTypeClassificationService.classify(request.formData());
        submission.applyCaseTypeProposal(proposedCaseType);

        return repository.save(submission);
    }

    /** FR-002: human confirms (or overrides) the classification before a Case may open. */
    @Transactional
    public ProblemSubmission confirmClassification(UUID submissionId, ConfirmClassificationRequest request) {
        ProblemSubmission submission = repository.findById(submissionId)
                .orElseThrow(() -> new NoSuchElementException("submission " + submissionId));
        submission.confirm("api", request.confirmedCaseType());
        return repository.save(submission);
    }

    /**
     * Phase 4 (T011, US1): human confirm/override of the case-type-classification proposal — the
     * authority of record on case type (research R5). Confirming with a type different from the
     * proposal records an override; the original proposal is preserved unchanged. Writes a D4
     * Decision + AuditEntry in the SAME transaction (data-model.md invariant). 409 (mapped by
     * {@link SubmissionController}) if already CONFIRMED.
     */
    @Transactional
    public ProblemSubmission confirmCaseType(UUID submissionId, ConfirmCaseTypeRequest request) {
        if (!CONFIRMABLE_CASE_TYPES.contains(request.caseType())) {
            throw new IllegalArgumentException(
                    "caseType must be one of " + CONFIRMABLE_CASE_TYPES + ", got " + request.caseType());
        }
        ProblemSubmission submission = repository.findById(submissionId)
                .orElseThrow(() -> new NoSuchElementException("submission " + submissionId));

        if ("CONFIRMED".equals(submission.getClassificationStatus())) {
            throw new AlreadyConfirmedException(
                    "submission " + submissionId + " case type is already confirmed");
        }

        if ("ENHANCEMENT".equals(request.caseType())) {
            requireDeliveredBaseline(submission);
        }

        String proposedBefore = submission.getProposedCaseType();
        submission.confirmCaseType(request.caseType());
        submission = repository.save(submission);

        boolean overridden = submission.isClassificationOverridden();

        // D4 — human decision, the authority of record on case type (AD-5 §9). caseInstanceId is
        // null: no Case exists yet at confirm time (Case creation is gated on this confirmation,
        // T012) — see DecisionRecord's V19 note. inputsRef ties the decision back to the submission.
        DecisionRecord decision = new DecisionRecord(
                UUID.randomUUID(), submission.getWorkspaceId(), null, "D4", "api",
                request.rationale(), submissionId.toString());
        decision = decisionRepository.save(decision);

        auditWriter.record(submission.getWorkspaceId(), "problem_submission", submissionId,
                overridden ? "CASE_TYPE_OVERRIDDEN" : "CASE_TYPE_CONFIRMED", "api",
                Map.of("proposedCaseType", String.valueOf(proposedBefore),
                        "confirmedCaseType", submission.getConfirmedCaseType(),
                        "overridden", overridden,
                        "decisionId", decision.getId().toString()));

        return submission;
    }

    /**
     * Phase 5 (T024, US3, research R4, design decision): ENHANCEMENT has no dedicated {@code
     * featureId} field anywhere in the intake data model — {@link com.d2os.intake.dto.ConfirmCaseTypeRequest}
     * only carries {@code caseType}/{@code rationale}, and neither {@link ProblemSubmission} nor {@link
     * com.d2os.intake.dto.CreateSubmissionRequest} carry a {@code featureId} anywhere. The ONLY
     * structured place a Feature can be named is inside the submission's opaque {@code formData} map —
     * the SAME pattern {@link CaseTypeClassificationService#classify} already uses to read {@code
     * subjectExists}/{@code hasDeliveredBaseline}/{@code requestIntent} out of formData. This method
     * reads a {@code featureId} key out of formData at confirm time; if it is absent or not a
     * parseable UUID, that is treated identically to "no baseline" (422) — an Enhancement with no
     * identifiable Feature has no baseline by construction. This is a deliberate interpretation of
     * FR-010/contracts {@code /case-type/confirm} 422, not a requirement documented elsewhere.
     */
    private void requireDeliveredBaseline(ProblemSubmission submission) {
        UUID featureId = extractFeatureId(submission.getFormData());
        if (featureId == null) {
            throw new NoBaselineException(
                    "ENHANCEMENT submission " + submission.getId()
                            + " formData has no parseable featureId — cannot resolve a baseline");
        }
        boolean hasDeliveredBaseline = caseInstanceRepository
                .findFirstByFeatureIdAndStatusOrderByCreatedAtDesc(featureId, CaseStatus.Delivered.name())
                .isPresent();
        if (!hasDeliveredBaseline) {
            throw new NoBaselineException(
                    "feature " + featureId + " has no Delivered Case — an Enhancement requires a "
                            + "delivered baseline (FR-010)");
        }
    }

    private UUID extractFeatureId(String formDataJson) {
        try {
            JsonNode node = objectMapper.readTree(formDataJson).path("featureId");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return UUID.fromString(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserializable submission payload", e);
        }
    }
}
