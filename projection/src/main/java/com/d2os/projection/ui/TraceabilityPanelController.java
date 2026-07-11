package com.d2os.projection.ui;

import com.d2os.projection.query.TraceabilityQueryService;
import com.d2os.projection.query.TraceabilityQueryService.Direction;
import com.d2os.tenancy.WorkspaceContext;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * T018 &mdash; the self-contained traceability panel (research R7, FR-006). A plain
 * {@code @Controller} resolving a server-rendered Thymeleaf view over the exact same {@link
 * TraceabilityQueryService} the JSON API ({@link com.d2os.projection.api.TraceabilityController})
 * uses &mdash; no second query implementation. Search box (form) -&gt; lineage tree -&gt; node
 * links, all rendered from a single template, matching {@code StudioPageController}'s PATTERN
 * (route -&gt; model -&gt; Thymeleaf view) without any dependency on the {@code studio} module
 * itself (plan.md's explicit "NOT studio-dependent" requirement &mdash; verified by grepping this
 * module's own {@code build.gradle}, which names {@code tenancy}/{@code casecore}/{@code
 * artifacts}/{@code knowledge}/{@code governance} only).
 *
 * <p>Genuinely minimal per the honesty standard {@code studio}'s own pages set: a plain HTML form
 * (GET, so results are bookmarkable/shareable via query string) and a server-rendered result list
 * &mdash; no htmx/JS island vendored here (unlike {@code studio}, which references a script tag for
 * an asset it explicitly documents as not-yet-vendored; this panel simply doesn't reference one, so
 * there is no dead reference either way).
 */
@Controller
@RequestMapping("/projection/traceability")
public class TraceabilityPanelController {

  private final TraceabilityQueryService traceabilityQueryService;

  public TraceabilityPanelController(TraceabilityQueryService traceabilityQueryService) {
    this.traceabilityQueryService = traceabilityQueryService;
  }

  /**
   * {@code GET /projection/traceability} &mdash; renders the search form, and results once a query
   * is submitted.
   */
  @GetMapping
  public String search(
      @RequestParam(required = false) String nodeType,
      @RequestParam(required = false) String naturalKey,
      @RequestParam(required = false, defaultValue = "TRACES_TO") String relation,
      @RequestParam(required = false, defaultValue = "DOWNSTREAM") String direction,
      @RequestParam(required = false, defaultValue = "10") int maxDepth,
      @RequestParam(required = false) String pageToken,
      Model model) {
    model.addAttribute("nodeType", nodeType);
    model.addAttribute("naturalKey", naturalKey);
    model.addAttribute("relation", relation);
    model.addAttribute("direction", direction);
    model.addAttribute("maxDepth", maxDepth);

    boolean searched =
        nodeType != null && !nodeType.isBlank() && naturalKey != null && !naturalKey.isBlank();
    model.addAttribute("searched", searched);
    if (searched) {
      UUID workspaceId = WorkspaceContext.require();
      Direction dir;
      try {
        dir = Direction.valueOf(direction);
      } catch (IllegalArgumentException e) {
        dir = Direction.DOWNSTREAM;
      }
      var result =
          "DEPENDS_ON".equals(relation)
              ? traceabilityQueryService.dependsOn(
                  workspaceId, nodeType, naturalKey, dir, maxDepth, pageToken)
              : traceabilityQueryService.tracesTo(
                  workspaceId, nodeType, naturalKey, dir, maxDepth, pageToken);
      model.addAttribute("found", result.isPresent());
      result.ifPresent(r -> model.addAttribute("result", r));
    }
    return "projection/traceability";
  }
}
