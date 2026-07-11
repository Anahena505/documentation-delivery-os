package com.d2os.studio;

import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.SubscriptionService;
import com.d2os.tenancy.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Global-library browse + copy-on-subscribe (T026, contracts/api.yaml, FR-013/014/015). */
@RestController
public class SubscriptionController {

  private final SubscriptionService subscriptionService;

  public SubscriptionController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @GetMapping("/api/v1/library/definitions")
  public List<Map<String, Object>> browse() {
    return subscriptionService.browseGlobal(WorkspaceContext.require());
  }

  @PostMapping("/api/v1/library/definitions/{definitionId}/subscribe")
  public ResponseEntity<SubscriptionResultView> subscribe(
      @PathVariable UUID definitionId,
      @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor) {
    SubscriptionService.SubscriptionResult result =
        subscriptionService.subscribe(definitionId, WorkspaceContext.require(), actor);
    DefinitionAsset copy = result.copy();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new SubscriptionResultView(
                copy.getId(),
                copy.getKey(),
                copy.getVersion(),
                copy.getCopiedFromId(),
                result.checksumVerified()));
  }

  public record SubscriptionResultView(
      UUID definitionId, String key, String version, UUID copiedFromId, boolean checksumVerified) {}
}
