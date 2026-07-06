package com.d2os.intake;

import com.d2os.intake.dto.ConfirmClassificationRequest;
import com.d2os.intake.dto.CreateSubmissionRequest;
import com.d2os.intake.dto.SubmissionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Intake API (contracts/api.yaml — intake tag). T023 + T025. */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /** POST /submissions — create + classify (FR-001). */
    @PostMapping
    public ResponseEntity<SubmissionResponse> create(@Valid @RequestBody CreateSubmissionRequest request) {
        ProblemSubmission saved = submissionService.createAndClassify(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubmissionResponse.from(saved));
    }

    /** POST /submissions/{id}/confirm-classification — human confirm (FR-002). */
    @PostMapping("/{id}/confirm-classification")
    public ResponseEntity<SubmissionResponse> confirm(@PathVariable UUID id,
                                                      @Valid @RequestBody ConfirmClassificationRequest request) {
        ProblemSubmission updated = submissionService.confirmClassification(id, request);
        return ResponseEntity.ok(SubmissionResponse.from(updated));
    }
}
