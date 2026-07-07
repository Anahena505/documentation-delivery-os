package com.d2os.intake.attachment;

import com.d2os.persona.spi.AttachmentSummaryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adapter implementing persona's {@link AttachmentSummaryPort} from the intake read model. Returns the
 * sanitized summary text for each {@code SUMMARIZED} attachment of a submission, in upload order —
 * never touching the raw object-store bytes, which keeps the FR-015 boundary intact.
 */
@Component
public class IntakeAttachmentSummaryPort implements AttachmentSummaryPort {

    private final AttachmentRepository attachmentRepository;
    private final AttachmentSummaryRepository summaryRepository;

    public IntakeAttachmentSummaryPort(AttachmentRepository attachmentRepository,
                                       AttachmentSummaryRepository summaryRepository) {
        this.attachmentRepository = attachmentRepository;
        this.summaryRepository = summaryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findSummaryTexts(UUID submissionId) {
        List<String> summaries = new ArrayList<>();
        for (Attachment attachment : attachmentRepository.findBySubmissionIdOrderByCreatedAtAsc(submissionId)) {
            if (Attachment.Status.SUMMARIZED.name().equals(attachment.getStatus())) {
                summaryRepository.findByAttachmentId(attachment.getId())
                        .ifPresent(s -> summaries.add(s.getSummaryText()));
            }
        }
        return summaries;
    }
}
