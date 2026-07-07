package com.d2os.intake.attachment;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.tenancy.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Owns the attachment lifecycle (US5, T040/T042/T043): validate the upload against the default-deny
 * policy, store the raw bytes in the object store, then run the sandboxed extract → summarize pipeline
 * so a persona only ever sees the sanitized summary. Boundary violations (size/type) are rejected
 * before any record exists; a post-acceptance extraction failure lands the attachment in {@code
 * REJECTED} with a reason and leaves the submission (and any Case) untouched.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;
    private final ObjectStoreClient objectStoreClient;
    private final SandboxedExtractor sandboxedExtractor;
    private final AttachmentSummarizer summarizer;
    private final AttachmentProperties properties;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             ObjectStoreClient objectStoreClient,
                             SandboxedExtractor sandboxedExtractor,
                             AttachmentSummarizer summarizer,
                             AttachmentProperties properties) {
        this.attachmentRepository = attachmentRepository;
        this.objectStoreClient = objectStoreClient;
        this.sandboxedExtractor = sandboxedExtractor;
        this.summarizer = summarizer;
        this.properties = properties;
    }

    /**
     * Accept and process one upload for {@code submissionId}. Returns the persisted attachment, whose
     * {@code status} reflects the outcome ({@code SUMMARIZED} on success, {@code REJECTED} if extraction
     * failed). Throws {@link AttachmentPolicyException} for boundary violations (no record created).
     */
    @Transactional
    public Attachment accept(UUID submissionId, String filename, String contentType, byte[] bytes) {
        UUID workspaceId = WorkspaceContext.require();

        if (bytes.length > properties.getMaxSizeBytes()) {
            throw new AttachmentPolicyException(AttachmentPolicyException.Reason.OVERSIZE,
                    "attachment %d bytes exceeds cap %d".formatted(bytes.length, properties.getMaxSizeBytes()));
        }
        if (!properties.isAllowed(contentType)) {
            throw new AttachmentPolicyException(AttachmentPolicyException.Reason.DISALLOWED_TYPE,
                    "content type not in allowlist: " + contentType);
        }

        UUID attachmentId = UUID.randomUUID();
        // Workspace-scoped, random key — the display filename is never used to build a storage path.
        String objectKey = "attachments/%s/%s".formatted(workspaceId, attachmentId);
        String contentHash = HashUtil.sha256Hex(bytes);
        objectStoreClient.put(objectKey, bytes, contentType);

        Attachment attachment = new Attachment(attachmentId, workspaceId, submissionId,
                filename, contentType, bytes.length, objectKey, contentHash);
        attachmentRepository.save(attachment);

        // Sandboxed extraction — a hostile file can only crash its fork, never the app (T042).
        attachment.markExtracting();
        String extractedText;
        try {
            extractedText = sandboxedExtractor.extract(bytes, contentType);
        } catch (SandboxedExtractor.ExtractionException e) {
            log.warn("attachment {} rejected: {}", attachmentId, e.getMessage());
            attachment.reject(e.getMessage());
            return attachmentRepository.save(attachment);
        }

        // Summarize the sanitized text; only this summary may ever reach a persona (T043/FR-015).
        summarizer.summarize(attachment, extractedText);
        attachment.markSummarized();
        return attachmentRepository.save(attachment);
    }

    @Transactional(readOnly = true)
    public List<Attachment> list(UUID submissionId) {
        return attachmentRepository.findBySubmissionIdOrderByCreatedAtAsc(submissionId);
    }
}
