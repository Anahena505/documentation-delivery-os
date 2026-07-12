package com.d2os.knowledge.api;

import com.d2os.knowledge.AlreadyDeprecatedException;
import com.d2os.knowledge.DeprecationService;
import com.d2os.knowledge.KnowledgeAffectedExecution;
import com.d2os.knowledge.KnowledgeAffectedExecutionRepository;
import com.d2os.knowledge.KnowledgeItem;
import com.d2os.knowledge.KnowledgeItemRepository;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The governed-knowledge inspection + deprecation surface (US3, T030, contracts/api.yaml). Reads
 * run under RLS so every listing is already workspace-scoped. Deprecation delegates to {@link
 * DeprecationService} (retire + flag in one transaction); the deprecating actor is read from the
 * {@code X-Actor} header as a pragmatic input pending a full auth-principal model (matching {@link
 * CandidateController}). Deprecation never rewrites history — it only flags the executions that
 * referenced a retired item (FR-015/016).
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

  private final KnowledgeItemRepository itemRepository;
  private final KnowledgeAffectedExecutionRepository affectedRepository;
  private final DeprecationService deprecationService;

  public KnowledgeController(
      KnowledgeItemRepository itemRepository,
      KnowledgeAffectedExecutionRepository affectedRepository,
      DeprecationService deprecationService) {
    this.itemRepository = itemRepository;
    this.affectedRepository = affectedRepository;
    this.deprecationService = deprecationService;
  }

  // ---- item listing / inspection ---------------------------------------------------------------

  @GetMapping("/items")
  public List<ItemSummary> listItems(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scopeLevel,
      @RequestParam(required = false) String tag,
      @RequestParam(required = false) String key) {
    return itemRepository.findAll().stream()
        .filter(i -> status == null || status.equalsIgnoreCase(i.getStatus()))
        .filter(i -> scopeLevel == null || scopeLevel.equalsIgnoreCase(i.getScopeLevel().name()))
        .filter(i -> key == null || key.equals(i.getKey()))
        .filter(
            i -> tag == null || (i.getTags() != null && Arrays.asList(i.getTags()).contains(tag)))
        .map(ItemSummary::of)
        .toList();
  }

  @GetMapping("/items/{id}")
  public ResponseEntity<ItemDetail> getItem(@PathVariable UUID id) {
    Optional<KnowledgeItem> found = itemRepository.findById(id);
    return found
        .map(item -> ResponseEntity.ok(ItemDetail.of(item)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  // ---- deprecation -----------------------------------------------------------------------------

  @PostMapping("/items/{id}/deprecate")
  public ResponseEntity<DeprecateResponse> deprecate(
      @PathVariable UUID id,
      @RequestHeader(value = "X-Actor", defaultValue = "curator") String actor,
      @RequestBody(required = false) DeprecateRequest request) {
    String reason = request == null ? null : request.reason();
    try {
      DeprecationService.DeprecationResult result =
          deprecationService.deprecateItem(id, reason, actor);
      // Contract shape (api.yaml): {itemId, affectedExecutions}. deprecateItem retires exactly the
      // one path-id version, so the item id is the path id and the flagged count is the flag total.
      return ResponseEntity.ok(new DeprecateResponse(id, result.flaggedExecutionCount()));
    } catch (AlreadyDeprecatedException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    }
  }

  // ---- affected-execution review queue ---------------------------------------------------------

  @GetMapping("/affected-executions")
  public List<AffectedExecutionView> affectedExecutions(
      @RequestParam(required = false) String reviewStatus,
      @RequestParam(required = false) String knowledgeKey) {
    List<KnowledgeAffectedExecution> rows =
        (reviewStatus == null || reviewStatus.isBlank())
            ? affectedRepository.findAllByOrderByFlaggedAtDesc()
            : affectedRepository.findByReviewStatusOrderByFlaggedAtDesc(reviewStatus.toUpperCase());
    return rows.stream()
        .filter(a -> knowledgeKey == null || knowledgeKey.equals(a.getKnowledgeItemKey()))
        .map(AffectedExecutionView::of)
        .toList();
  }

  @PostMapping("/affected-executions/{flagId}/review")
  public ResponseEntity<AffectedExecutionView> review(
      @PathVariable UUID flagId,
      @RequestHeader(value = "X-Actor", defaultValue = "curator") String actor) {
    try {
      // Flip + audit in one transaction (service-owned) — the flag-only OPEN→REVIEWED
      // acknowledgement never touches the flagged execution/snapshot/output (FR-016).
      KnowledgeAffectedExecution flag = deprecationService.reviewAffectedExecution(flagId, actor);
      return ResponseEntity.ok(AffectedExecutionView.of(flag));
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    }
  }

  // ---- DTOs ------------------------------------------------------------------------------------

  public record DeprecateRequest(String reason) {}

  public record DeprecateResponse(UUID itemId, int affectedExecutions) {}

  public record ItemSummary(
      UUID id,
      String key,
      int version,
      String scopeLevel,
      List<String> tags,
      String title,
      String status) {
    static ItemSummary of(KnowledgeItem i) {
      return new ItemSummary(
          i.getId(),
          i.getKey(),
          i.getVersion(),
          i.getScopeLevel().name(),
          List.of(i.getTags()),
          i.getTitle(),
          i.getStatus());
    }
  }

  public record ItemDetail(
      UUID id,
      String key,
      int version,
      String scopeLevel,
      UUID scopeRef,
      List<String> tags,
      String locale,
      String title,
      String content,
      String contentHash,
      String status,
      UUID sourceCandidateId,
      Integer supersedesVersion,
      String deprecationReason,
      OffsetDateTime deprecatedAt,
      OffsetDateTime createdAt) {
    static ItemDetail of(KnowledgeItem i) {
      return new ItemDetail(
          i.getId(),
          i.getKey(),
          i.getVersion(),
          i.getScopeLevel().name(),
          i.getScopeRef(),
          List.of(i.getTags()),
          i.getLocale(),
          i.getTitle(),
          i.getContent(),
          i.getContentHash(),
          i.getStatus(),
          i.getSourceCandidateId(),
          i.getSupersedesVersion(),
          i.getDeprecationReason(),
          i.getDeprecatedAt(),
          i.getCreatedAt());
    }
  }

  public record AffectedExecutionView(
      UUID id,
      String knowledgeItemKey,
      int knowledgeItemVersion,
      UUID operationExecutionId,
      UUID caseInstanceId,
      String reviewStatus,
      OffsetDateTime flaggedAt) {
    static AffectedExecutionView of(KnowledgeAffectedExecution a) {
      return new AffectedExecutionView(
          a.getId(),
          a.getKnowledgeItemKey(),
          a.getKnowledgeItemVersion(),
          a.getOperationExecutionId(),
          a.getCaseInstanceId(),
          a.getReviewStatus(),
          a.getFlaggedAt());
    }
  }
}
