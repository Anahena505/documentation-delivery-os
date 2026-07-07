package com.d2os.intake.attachment;

import java.time.OffsetDateTime;
import java.util.UUID;

/** API view of an attachment (contracts/api.yaml — intake tag, T041). Raw bytes are never exposed here. */
public record AttachmentResponse(
        UUID id,
        UUID submissionId,
        String filename,
        String contentType,
        long sizeBytes,
        String contentHash,
        String status,
        String rejectionReason,
        OffsetDateTime createdAt) {

    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(a.getId(), a.getSubmissionId(), a.getFilename(), a.getContentType(),
                a.getSizeBytes(), a.getContentHash(), a.getStatus(), a.getRejectionReason(), a.getCreatedAt());
    }
}
