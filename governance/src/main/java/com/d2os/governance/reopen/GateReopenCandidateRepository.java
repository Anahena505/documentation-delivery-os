package com.d2os.governance.reopen;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateReopenCandidateRepository extends JpaRepository<GateReopenCandidate, UUID> {

  List<GateReopenCandidate> findByUpstreamArtifactRevisionId(UUID upstreamArtifactRevisionId);

  /** {@code GET /reopen-candidates?disposition=} (T028). */
  List<GateReopenCandidate> findByDisposition(String disposition);

  List<GateReopenCandidate> findByGateInstanceId(UUID gateInstanceId);
}
