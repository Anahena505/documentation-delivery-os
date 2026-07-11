package com.d2os.casecore.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditChainSegmentRepository extends JpaRepository<AuditChainSegment, UUID> {

    Optional<AuditChainSegment> findFirstByWorkspaceIdOrderBySegmentSeqDesc(UUID workspaceId);

    List<AuditChainSegment> findByWorkspaceIdOrderBySegmentSeqAsc(UUID workspaceId);
}
