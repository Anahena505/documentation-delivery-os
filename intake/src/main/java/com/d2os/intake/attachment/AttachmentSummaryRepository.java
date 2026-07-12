package com.d2os.intake.attachment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentSummaryRepository extends JpaRepository<AttachmentSummary, UUID> {

  Optional<AttachmentSummary> findByAttachmentId(UUID attachmentId);

  List<AttachmentSummary> findByWorkspaceId(UUID workspaceId);
}
