package com.d2os.replay;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Replay-audit API (T042, contracts/api.yaml — replay tag). */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/replay")
public class ReplayController {

  private final ReplayHarness replayHarness;

  public ReplayController(ReplayHarness replayHarness) {
    this.replayHarness = replayHarness;
  }

  @PostMapping
  public ResponseEntity<ReplayReport> replay(@PathVariable UUID caseId) {
    return ResponseEntity.ok(replayHarness.replay(caseId));
  }
}
