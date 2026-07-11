package com.d2os.governance.reopen;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImpactAssessmentRepository extends JpaRepository<ImpactAssessment, UUID> {

  Optional<ImpactAssessment> findByGateInstanceIdAndUpstreamArtifactRevisionId(
      UUID gateInstanceId, UUID upstreamArtifactRevisionId);

  /**
   * {@code ReopenService.reopen} (T027): any impact assessment at all satisfies the FR-007 gate.
   */
  boolean existsByGateInstanceId(UUID gateInstanceId);
}
