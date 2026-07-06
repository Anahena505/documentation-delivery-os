package com.d2os.casecore;

import com.d2os.casecore.dto.CaseResponse;
import com.d2os.casecore.dto.CreateCaseRequest;
import com.d2os.casecore.spi.SubmissionLookup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/** Case API — open + read (contracts/api.yaml — cases tag). T026 + GET /cases/{id} (US1). */
@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {

    private final CaseService caseService;
    private final CaseInstanceRepository caseRepository;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final SubmissionLookup submissionLookup;
    private final ObjectMapper objectMapper;

    public CaseController(CaseService caseService,
                          CaseInstanceRepository caseRepository,
                          CaseDefinitionSnapshotRepository snapshotRepository,
                          SubmissionLookup submissionLookup,
                          ObjectMapper objectMapper) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.snapshotRepository = snapshotRepository;
        this.submissionLookup = submissionLookup;
        this.objectMapper = objectMapper;
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
