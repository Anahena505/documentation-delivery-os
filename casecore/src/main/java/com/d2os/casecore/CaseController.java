package com.d2os.casecore;

import com.d2os.casecore.dto.BaselineResponse;
import com.d2os.casecore.dto.CaseResponse;
import com.d2os.casecore.dto.CreateCaseRequest;
import com.d2os.casecore.spi.SubmissionLookup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Case API — open + read (contracts/api.yaml — cases tag). T026 + GET /cases/{id} (US1). */
@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {

    private final CaseService caseService;
    private final CaseInstanceRepository caseRepository;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final SubmissionLookup submissionLookup;
    private final AuditEntryRepository auditEntryRepository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public CaseController(CaseService caseService,
                          CaseInstanceRepository caseRepository,
                          CaseDefinitionSnapshotRepository snapshotRepository,
                          SubmissionLookup submissionLookup,
                          AuditEntryRepository auditEntryRepository,
                          ObjectMapper objectMapper,
                          JdbcTemplate jdbcTemplate) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.snapshotRepository = snapshotRepository;
        this.submissionLookup = submissionLookup;
        this.auditEntryRepository = auditEntryRepository;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** POST /cases — open a Case from a confirmed submission; 409 on FR-016 conflict. */
    @PostMapping
    public ResponseEntity<CaseResponse> create(@Valid @RequestBody CreateCaseRequest request) {
        SubmissionLookup.SubmissionInfo submission = submissionLookup.find(request.submissionId())
                .orElseThrow(() -> new CaseCreationException("submission " + request.submissionId() + " not found"));

        CaseInstance kase = caseService.openCase(submission, request.featureId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CaseResponse.from(kase, snapshotEntries(kase.getId())));
    }

    /** GET /cases/{id} — status + pinned snapshot. */
    @GetMapping("/{id}")
    public ResponseEntity<CaseResponse> get(@PathVariable UUID id) {
        CaseInstance kase = caseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("case " + id));
        return ResponseEntity.ok(CaseResponse.from(kase, snapshotEntries(id)));
    }

    /**
     * GET /cases/{id}/baseline — an Enhancement case's pinned baseline ArtifactRevisions, with {@code
     * superseded}/{@code deprecated} status surfaced (T025, US3, research R4). 404 when the case isn't
     * an Enhancement case, or its {@code BaselineResolutionDelegate} step hasn't run yet (no {@code
     * BASELINE_RESOLVED} audit entry) — both map through the same {@link NoSuchElementException} →
     * 404 path {@link CaseExceptionHandler} already provides.
     */
    @GetMapping("/{id}/baseline")
    public ResponseEntity<BaselineResponse> baseline(@PathVariable UUID id) {
        CaseInstance kase = caseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("case " + id));
        if (!"enhancement".equalsIgnoreCase(kase.getCaseTypeKey())) {
            throw new NoSuchElementException("case " + id + " is not an Enhancement case");
        }
        AuditEntryRecord entry = auditEntryRepository
                .findFirstBySubjectTypeAndSubjectIdAndActionOrderByTxTimeDesc(
                        "case_instance", id, "BASELINE_RESOLVED")
                .orElseThrow(() -> new NoSuchElementException("case " + id + " has no resolved baseline"));
        return ResponseEntity.ok(BaselineResponse.from(kase, entry, objectMapper, jdbcTemplate));
    }

    /**
     * GET /cases/{id}/required-artifacts — the pinned expected-artifact set: BASE (from the case
     * type's template dependencies) plus CONDITIONAL (from the conditional-artifacts DMN, T032), each
     * with {@code fulfilled} computed against the Case's actually-produced artifacts (T034, US5).
     */
    @GetMapping("/{id}/required-artifacts")
    public ResponseEntity<List<Map<String, Object>>> requiredArtifacts(@PathVariable UUID id) {
        caseRepository.findById(id).orElseThrow(() -> new NoSuchElementException("case " + id));
        CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(id)
                .orElseThrow(() -> new NoSuchElementException("case " + id + " has no pinned snapshot"));

        Set<String> producedKinds = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT artifact_type FROM artifact WHERE case_instance_id = ?", String.class, id));

        List<Map<String, Object>> result = caseService.requiredArtifacts(snapshot).stream()
                .map(entry -> {
                    Map<String, Object> withFulfilled = new java.util.LinkedHashMap<>(entry);
                    withFulfilled.put("fulfilled", producedKinds.contains(entry.get("artifactKind")));
                    return withFulfilled;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    private Object snapshotEntries(UUID caseId) {
        return snapshotRepository.findByCaseInstanceId(caseId)
                .map(s -> parse(s.getEntries()))
                .orElse(null);
    }

    private Object parse(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
