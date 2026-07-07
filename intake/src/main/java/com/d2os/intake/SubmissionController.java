package com.d2os.intake;

import com.d2os.intake.attachment.Attachment;
import com.d2os.intake.attachment.AttachmentPolicyException;
import com.d2os.intake.attachment.AttachmentResponse;
import com.d2os.intake.attachment.AttachmentService;
import com.d2os.intake.dto.ConfirmClassificationRequest;
import com.d2os.intake.dto.CreateSubmissionRequest;
import com.d2os.intake.dto.SubmissionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

/** Intake API (contracts/api.yaml — intake tag). T023 + T025 + attachment upload surface (T041). */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final AttachmentService attachmentService;

    public SubmissionController(SubmissionService submissionService, AttachmentService attachmentService) {
        this.submissionService = submissionService;
        this.attachmentService = attachmentService;
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

    /**
     * POST /submissions/{id}/attachments — upload one file (multipart, US5/FR-015, T041). The file is
     * stored, sandbox-extracted, and summarized before any persona could ever read it. Oversized → 413,
     * disallowed type → 422 (no record created); an extraction failure returns 201 with status REJECTED.
     */
    @PostMapping("/{id}/attachments")
    public ResponseEntity<AttachmentResponse> upload(@PathVariable UUID id,
                                                     @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read uploaded file", e);
        }
        Attachment saved = attachmentService.accept(
                id, file.getOriginalFilename(), file.getContentType(), bytes);
        return ResponseEntity.status(HttpStatus.CREATED).body(AttachmentResponse.from(saved));
    }

    /** GET /submissions/{id}/attachments — list attachments (metadata only; raw bytes never exposed). */
    @GetMapping("/{id}/attachments")
    public ResponseEntity<List<AttachmentResponse>> listAttachments(@PathVariable UUID id) {
        List<AttachmentResponse> body = attachmentService.list(id).stream()
                .map(AttachmentResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** Map default-deny boundary rejections to the API's documented status codes (T041). */
    @ExceptionHandler(AttachmentPolicyException.class)
    public ResponseEntity<String> onPolicyViolation(AttachmentPolicyException e) {
        HttpStatus status = e.getReason() == AttachmentPolicyException.Reason.OVERSIZE
                ? HttpStatus.PAYLOAD_TOO_LARGE          // 413
                : HttpStatus.UNPROCESSABLE_ENTITY;      // 422
        return ResponseEntity.status(status).body(e.getMessage());
    }
}
