package com.d2os.casecore.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditChainSegmentRepository extends JpaRepository<AuditChainSegment, UUID> {

  Optional<AuditChainSegment> findFirstByWorkspaceIdOrderBySegmentSeqDesc(UUID workspaceId);

  List<AuditChainSegment> findByWorkspaceIdOrderBySegmentSeqAsc(UUID workspaceId);
}
