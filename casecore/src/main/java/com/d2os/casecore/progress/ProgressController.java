package com.d2os.casecore.progress;

import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Progress stream API (T035, FR-011, contracts/api.yaml). Returns events after a cursor; with
 * {@code wait=true} it long-polls (holding the request up to 25 s) so a UI can follow a Case live —
 * including HEARTBEATs — without tight polling.
 */
@RestController
public class ProgressController {

    private static final long LONG_POLL_MILLIS = 25_000;
    private static final Limit PAGE = Limit.of(500);

    private final ProgressEventRepository repository;

    public ProgressController(ProgressEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/cases/{caseId}/progress")
    public ResponseEntity<List<ProgressView>> progress(@PathVariable UUID caseId,
                                                       @RequestParam(defaultValue = "0") long afterId,
                                                       @RequestParam(defaultValue = "false") boolean wait)
            throws InterruptedException {
        List<ProgressView> page = fetch(caseId, afterId);
        long deadline = System.currentTimeMillis() + LONG_POLL_MILLIS;
        while (wait && page.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            page = fetch(caseId, afterId);
        }
        return ResponseEntity.ok(page);
    }

    private List<ProgressView> fetch(UUID caseId, long afterId) {
        return repository.findByCaseIdAndIdGreaterThanOrderByIdAsc(caseId, afterId, PAGE).stream()
                .map(ProgressView::of)
                .toList();
    }

    public record ProgressView(long id, UUID caseId, String kind, String activityId, String detail,
                               OffsetDateTime createdAt) {
        static ProgressView of(ProgressEvent e) {
            return new ProgressView(e.getId(), e.getCaseId(), e.getKind(), e.getActivityId(),
                    e.getDetail(), e.getCreatedAt());
        }
    }
}
