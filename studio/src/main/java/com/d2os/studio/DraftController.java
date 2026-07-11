package com.d2os.studio;

import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.catalog.DraftService;
import com.d2os.studio.editor.DmnEditorBridge;
import com.d2os.studio.editor.PromptEditorModel;
import com.d2os.studio.editor.RubricEditorModel;
import com.d2os.tenancy.WorkspaceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD-as-draft over the eight definition types (tasks.md T008, US1, FR-001, contracts/api.yaml
 * "drafts" tag). Mutations (create/update) delegate to catalog's {@link DraftService}, which owns
 * the Draft-only edit guard (Principle I, via {@link DefinitionAsset#updateBody}) — this controller
 * adds no domain rule of its own beyond per-type typed-slot validation (FR-003, T009), only
 * request/response mapping and HTTP status translation, matching plan.md's Structure Decision
 * ("studio only renders" catalog semantics).
 *
 * <p>The read/list endpoints go straight at {@link DefinitionAssetRepository} — the same pattern
 * {@code CatalogController} uses in-module for read-only listing. RLS on {@code definition_asset}
 * (V3's {@code ws_isolation_definition} policy) already scopes {@code repository.findAll()} to the
 * caller's workspace plus the Global system workspace, so no extra workspace filter is needed for
 * correctness — only the {@code type}/{@code status} query parameters are applied here.
 *
 * <p><b>Response shape decision (T008)</b>: plain JSON, matching contracts/api.yaml's {@code
 * DefinitionVersion}-shaped schemas (extended with a {@code body} field the contract's schema omits
 * but the "GET returns full draft content" description requires). T011's server-rendered Thymeleaf
 * pages consume this JSON contract for their own page-controller logic where needed (or read the
 * entity directly, since {@code StudioPageController} lives in the same module) rather than this
 * controller returning HTML fragments — kept intentionally simple and independently testable
 * (StudioAuthoringIT, T012) rather than coupling the two phases' delivery order. If a later phase
 * wires real htmx partial-swap interactivity into the editor pages, these same endpoints can grow
 * an {@code Accept: text/html} branch that renders a Thymeleaf fragment instead of JSON without
 * changing the underlying service calls — noted as a known follow-up, not done here.
 */
@RestController
@RequestMapping("/api/v1/catalog/drafts")
public class DraftController {

  /**
   * Contracts/api.yaml's {@code listDrafts} status enum — Published/Deprecated are never "drafts".
   */
  private static final List<String> DRAFT_LIST_STATUSES = List.of("Draft", "InReview");

  private final DraftService draftService;
  private final DefinitionAssetRepository repository;
  private final ObjectMapper objectMapper;
  private final DmnEditorBridge dmnEditorBridge;

  public DraftController(
      DraftService draftService,
      DefinitionAssetRepository repository,
      ObjectMapper objectMapper,
      DmnEditorBridge dmnEditorBridge) {
    this.draftService = draftService;
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.dmnEditorBridge = dmnEditorBridge;
  }

  /** {@code GET /catalog/drafts} (US1) — filterable by {@code type}/{@code status}. */
  @GetMapping
  public List<DraftView> list(
      @RequestParam(required = false) String type, @RequestParam(required = false) String status) {
    return repository.findAll().stream()
        .filter(
            d ->
                status == null
                    ? DRAFT_LIST_STATUSES.contains(d.getStatus())
                    : status.equals(d.getStatus()))
        .filter(d -> type == null || type.equals(d.getType()))
        .map(this::toView)
        .toList();
  }

  /**
   * {@code POST /catalog/drafts} (FR-001, US1) — create a Draft candidate of any of the eight
   * types. Rubric/Prompt bodies are validated through their typed-slot editor model (T009, FR-003)
   * before the row is ever persisted; a duplicate {@code (type,key,version)} surfaces as 409 via
   * the {@code uq_definition_type_key_version} constraint (V3).
   */
  @PostMapping
  public ResponseEntity<DraftView> create(
      @RequestBody CreateDraftRequest request,
      @RequestHeader(value = "X-Actor", defaultValue = "author") String actor) {
    String bodyJson = bodyJsonOf(request.body());
    validateTypedSlots(request.type(), bodyJson);
    try {
      DefinitionAsset draft =
          draftService.create(
              request.type(),
              request.key(),
              request.version(),
              bodyJson,
              WorkspaceContext.require(),
              actor);
      return ResponseEntity.status(HttpStatus.CREATED).body(toView(draft));
    } catch (DataIntegrityViolationException e) {
      throw new DraftConflictException(
          "(type,key,version) already exists: "
              + request.type()
              + "/"
              + request.key()
              + "/"
              + request.version(),
          e);
    }
  }

  /** {@code GET /catalog/drafts/{draftId}} (US1) — full content for continued editing. */
  @GetMapping("/{draftId}")
  public DraftView get(@PathVariable UUID draftId) {
    return toView(draftService.load(draftId));
  }

  /**
   * {@code PUT /catalog/drafts/{draftId}} (US1) — update draft content, Draft status only. {@link
   * DraftService#update} throws {@link IllegalStateException} (mapped to 409 here) once the row has
   * left {@code Draft} (research R2's InReview freeze).
   */
  @PutMapping("/{draftId}")
  public DraftView update(@PathVariable UUID draftId, @RequestBody UpdateDraftRequest request) {
    DefinitionAsset existing = draftService.load(draftId);
    String bodyJson = bodyJsonOf(request.body());
    validateTypedSlots(existing.getType(), bodyJson);
    try {
      return toView(draftService.update(draftId, bodyJson));
    } catch (IllegalStateException e) {
      throw new DraftConflictException(e.getMessage(), e);
    }
  }

  // ---- DMN editor bridge (T010, FR-002) -----------------------------------------------------
  //
  // Rule drafts carry raw DMN XML wrapped as {"dmnXml": "..."} (DmnEditorBridge) rather than the
  // free-form generic JSON body path above — a browser-side dmn-js instance is meant to GET/PUT
  // these two endpoints directly, never the generic body as JSON.

  /** {@code GET /catalog/drafts/{draftId}/dmn-xml} — raw DMN XML for a Rule draft (T010). */
  @GetMapping(value = "/{draftId}/dmn-xml", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> getDmnXml(@PathVariable UUID draftId) {
    DefinitionAsset draft = draftService.load(draftId);
    String xml = dmnEditorBridge.fromBodyJson(draft.getBody());
    if (xml == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(xml);
  }

  /** {@code PUT /catalog/drafts/{draftId}/dmn-xml} — replace a Rule draft's DMN XML (T010). */
  @PutMapping(value = "/{draftId}/dmn-xml", consumes = MediaType.APPLICATION_XML_VALUE)
  public DraftView putDmnXml(@PathVariable UUID draftId, @RequestBody String dmnXml) {
    try {
      return toView(draftService.update(draftId, dmnEditorBridge.toBodyJson(dmnXml)));
    } catch (IllegalStateException e) {
      throw new DraftConflictException(e.getMessage(), e);
    }
  }

  // ---- helpers --------------------------------------------------------------------------------

  private String bodyJsonOf(JsonNode body) {
    return body == null ? "{}" : body.toString();
  }

  /**
   * Typed-slot validation (T009, FR-003): rubric/prompt drafts are checked against their editor
   * model before save; every other type has no typed-slot editor in this phase and is accepted as
   * opaque JSON (case_type/workflow/persona/playbook/template) or the DMN-XML wrapper shape (rule,
   * validated by {@link DmnEditorBridge} only when authored through the dmn-xml endpoints above —
   * the generic body path intentionally does not force the dmnXml shape, since a rule's
   * placeholder/pointer body is also legal JSON here).
   */
  private void validateTypedSlots(String type, String bodyJson) {
    try {
      switch (type) {
        case "rubric" -> RubricEditorModel.fromBodyJson(bodyJson, objectMapper).validate();
        case "prompt" -> PromptEditorModel.fromBodyJson(bodyJson, objectMapper).validate();
        default -> {
          /* no typed-slot editor for this type in this phase */
        }
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("malformed " + type + " body: " + e.getMessage(), e);
    }
  }

  private DraftView toView(DefinitionAsset d) {
    Object body;
    try {
      body = objectMapper.readTree(d.getBody());
    } catch (Exception e) {
      body = d.getBody();
    }
    return new DraftView(
        d.getId(),
        d.getType(),
        d.getKey(),
        d.getVersion(),
        d.getStatus(),
        d.getLocale(),
        body,
        d.getChecksum(),
        d.getCreatedAt(),
        d.getCreatedBy());
  }

  // ---- DTOs ------------------------------------------------------------------------------------

  public record CreateDraftRequest(String type, String key, String version, JsonNode body) {}

  public record UpdateDraftRequest(JsonNode body) {}

  public record DraftView(
      UUID id,
      String type,
      String key,
      String version,
      String status,
      String locale,
      Object body,
      String checksum,
      OffsetDateTime createdAt,
      String createdBy) {}
}
