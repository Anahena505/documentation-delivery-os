package com.d2os.studio;

import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Publish-lifecycle endpoints (tasks.md T013/T018, US2, FR-004/006): submit-for-review and
 * publish. Kept in a SEPARATE controller from the pure-CRUD {@link DraftController} per plan.md's
 * file list ({@code studio/PublishController.java}) — same base path
 * ({@code /api/v1/catalog/drafts}), disjoint sub-routes ({@code /submit-review}, {@code
 * /publish}), so there is no mapping conflict between the two {@code @RestController} beans.
 * All orchestration lives in {@link PublishService} (see that class's javadoc for the
 * catalog-vs-governance circular-dependency reasoning behind this module placement) — this
 * controller only translates {@link PublishConflictException} (409, via {@link
 * DraftExceptionHandler}) and {@link java.util.NoSuchElementException} (404, same handler) into
 * HTTP responses, exactly like {@link DraftController} does for the CRUD surface.
 */
@RestController
@RequestMapping("/api/v1/catalog/drafts")
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    /** {@code POST /catalog/drafts/{draftId}/submit-review} (T013, FR-004). */
    @PostMapping("/{draftId}/submit-review")
    public ResponseEntity<PublishService.SubmitReviewResult> submitReview(
            @PathVariable UUID draftId,
            @RequestHeader(value = "X-Actor", defaultValue = "author") String actor) {
        PublishService.SubmitReviewResult result =
                publishService.submitForReview(draftId, WorkspaceContext.require(), actor);
        return ResponseEntity.ok(result);
    }

    /** {@code POST /catalog/drafts/{draftId}/publish} (T018, FR-006/007/008/017/018). */
    @PostMapping("/{draftId}/publish")
    public ResponseEntity<PublishService.PublishResult> publish(
            @PathVariable UUID draftId,
            @RequestHeader(value = "X-Actor", defaultValue = "author") String actor) {
        PublishService.PublishResult result =
                publishService.publish(draftId, WorkspaceContext.require(), actor);
        return ResponseEntity.ok(result);
    }
}
