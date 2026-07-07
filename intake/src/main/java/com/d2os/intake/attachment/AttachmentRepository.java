package com.d2os.intake.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findBySubmissionIdOrderByCreatedAtAsc(UUID submissionId);
}
