package com.d2os.intake.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentSummaryRepository extends JpaRepository<AttachmentSummary, UUID> {

    Optional<AttachmentSummary> findByAttachmentId(UUID attachmentId);

    List<AttachmentSummary> findByWorkspaceId(UUID workspaceId);
}
