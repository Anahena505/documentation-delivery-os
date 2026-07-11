package com.d2os.studio;

import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.catalog.DraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf page-serving controller (tasks.md T011, research R1). A plain {@code @Controller} (not
 * {@code @RestController}) resolving server-rendered views over the same {@link
 * DraftService}/{@link DefinitionAssetRepository} the JSON API ({@link DraftController}, T008)
 * uses.
 *
 * <p>This phase proves the presentation-layer plumbing works — Spring MVC route -&gt; model -&gt;
 * Thymeleaf template, referencing the T002 vendored asset paths — not a polished, fully interactive
 * UI. The dmn-js decision-table island and any live diff2html rendering are left genuinely inert
 * until a later, network-enabled step actually vendors those files (see {@code
 * static/studio/vendor/README.md}); the templates reference the paths they will load from so
 * nothing needs to change once the files land.
 */
@Controller
@RequestMapping("/studio/drafts")
public class StudioPageController {

  private static final List<String> DRAFT_LIST_STATUSES = List.of("Draft", "InReview");

  private final DefinitionAssetRepository repository;
  private final DraftService draftService;
  private final ObjectMapper objectMapper;

  public StudioPageController(
      DefinitionAssetRepository repository, DraftService draftService, ObjectMapper objectMapper) {
    this.repository = repository;
    this.draftService = draftService;
    this.objectMapper = objectMapper;
  }

  /** {@code GET /studio/drafts} — the eight-type draft list page (research R1). */
  @GetMapping
  public String list(@RequestParam(required = false) String type, Model model) {
    List<DefinitionAsset> drafts =
        repository.findAll().stream()
            .filter(d -> DRAFT_LIST_STATUSES.contains(d.getStatus()))
            .filter(d -> type == null || type.equals(d.getType()))
            .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
            .toList();
    model.addAttribute("drafts", drafts);
    model.addAttribute("typeFilter", type);
    return "studio/drafts";
  }

  /**
   * {@code GET /studio/drafts/{draftId}} — the per-type editor page (DMN table for rules, plain
   * form otherwise).
   */
  @GetMapping("/{draftId}")
  public String edit(@PathVariable UUID draftId, Model model) {
    DefinitionAsset draft = draftService.load(draftId);
    model.addAttribute("draft", draft);
    model.addAttribute("bodyJson", prettyBody(draft.getBody()));
    model.addAttribute("editable", "Draft".equals(draft.getStatus()));
    return "studio/draft-edit";
  }

  /**
   * {@code POST /studio/drafts/{draftId}} — the plain-HTML-form save action backing the editor page
   * above (browsers cannot submit HTML forms with method PUT; the JSON API's {@code PUT
   * /api/v1/catalog/drafts/{draftId}} is the real contract endpoint, T008 — this is a thin form
   * adapter over the same {@link DraftService#update}, not a second implementation of the guard).
   */
  @PostMapping("/{draftId}")
  public String update(@PathVariable UUID draftId, @RequestParam("body") String body) {
    draftService.update(draftId, body);
    return "redirect:/studio/drafts/" + draftId;
  }

  private String prettyBody(String rawJson) {
    try {
      Object tree = objectMapper.readTree(rawJson);
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    } catch (Exception e) {
      return rawJson;
    }
  }
}
