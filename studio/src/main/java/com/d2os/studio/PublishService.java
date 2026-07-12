package com.d2os.studio;

import com.d2os.casecore.AuditWriter;
import com.d2os.catalog.ChecksumUtil;
import com.d2os.catalog.DefinitionAsset;
import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.catalog.DefinitionResolutionService;
import com.d2os.catalog.DefinitionView;
import com.d2os.catalog.DraftService;
import com.d2os.governance.DeltaReport;
import com.d2os.governance.DeltaReportService;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstance.GateSubjectType;
import com.d2os.governance.GateInstance.GateType;
import com.d2os.governance.GateInstanceRepository;
import com.d2os.governance.GateService;
import com.d2os.governance.GateStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publish-lifecycle orchestration for the studio (tasks.md T013/T014/T016/T017, US2, research R3).
 *
 * <h2>Module placement (the circular-dependency question the task brief raised)</h2>
 *
 * plan.md's file list names this class {@code catalog/.../PublishService.java}. It is NOT there:
 * this orchestration needs BOTH {@code catalog}'s {@link DraftService}/{@link DefinitionAsset}
 * mutators AND {@code governance}'s {@link GateService}/{@link DeltaReportService} in the same
 * method — but {@code governance}'s own {@code build.gradle} already declares {@code implementation
 * project(':catalog')} (gate subjects resolve against {@code DefinitionAssetRepository}, per that
 * module's own javadoc). A {@code catalog} dependency on {@code governance} would therefore close a
 * cycle: {@code catalog -> governance -> catalog}. Gradle's module graph does not allow this.
 *
 * <p>{@code studio} already depends on BOTH {@code catalog} and {@code governance} (its {@code
 * build.gradle} says so explicitly — "the Phase 5 approval-gate subprocess / GateInstance /
 * DeltaReportService that the studio's D4 publish gate ... reuse"), so it is the one module that
 * can legally see both sides without inventing a new dependency edge. This class lives here for
 * exactly that reason, mirroring how {@code DraftController} (T008) already sits in {@code studio}
 * rather than {@code catalog} for the CRUD surface. {@code catalog} itself gained no new
 * dependencies or business logic for this phase — only two small, self-contained additions used by
 * this class: {@link DefinitionAsset#pinContentHash}/{@link
 * DefinitionAsset#markPublishedFromReview} (entity-level guards, same style as the existing {@code
 * updateBody}/{@code markInReview}) and {@link ChecksumUtil} (a public SHA-256 helper, extracted
 * from {@code DefinitionPublishService}'s private one so this class does not have to duplicate it
 * inline). {@code DefinitionPublishService} itself — {@code CatalogSeedLoader}'s
 * Draft-&gt;Published, no-gate, no-semver-check primitive — is untouched, per Phase 1-2's design
 * decision; this class's {@link #publish} is a wholly separate, additive gate-integrated path.
 *
 * <h2>Content-hash pinning (T013) and the tamper guard (T016)</h2>
 *
 * {@link #submitForReview} pins {@code sha256(body)} onto the row via {@link
 * DefinitionAsset#pinContentHash} the moment it leaves Draft (the InReview freeze already makes the
 * body immutable through the ordinary edit path — {@link DefinitionAsset#updateBody} refuses
 * non-Draft rows — so this is defense-in-depth, not the primary guard). {@link #publish} recomputes
 * the hash from the CURRENT body and passes it to {@link DefinitionAsset#markPublishedFromReview},
 * which compares it against the pinned value itself and refuses to publish on a mismatch.
 *
 * <h2>Delta reports (T014) and MAJOR detection (T017)</h2>
 *
 * A delta report is generated iff a prior published version of the same {@code (type,key)} exists
 * (via {@link DefinitionResolutionService#latestPublishedView}) — both bodies are pretty-printed
 * (mirrors {@code StudioPageController}'s own convention) before being diffed via {@link
 * DeltaReportService#generateForDefinitions}, one code path serving both the "prompt diff" and
 * "canonical-JSON content diff" cases research R3 describes (there is no meaningful structural
 * difference between the two for a JSON body — a Prompt's {@code template} field changes are just
 * as legible in a pretty-printed whole-body diff as any other field's). When there is NO prior
 * version (first-ever publish of a new key), generation is skipped entirely and the gate's {@code
 * inputsRef} records why: {@code chk_delta_report_subject_shape} (V26) requires BOTH {@code
 * from_definition_id}/{@code to_definition_id} non-null for the definition-pair shape, so there is
 * no legal "diff against nothing" row to write.
 *
 * <p>MAJOR-version detection ({@link SemVer#isMajorBump}) against that same prior version opens a
 * SECOND {@code APPROVAL} gate bound to the {@code architecture-board} role ({@code
 * d2os.studio.roles.architecture-board}, T003) alongside the always-opened D4 ({@code
 * catalog-owner}) gate; {@link #publish} requires EVERY gate found for the draft's {@code
 * (DEFINITION_VERSION, draftId)} subject to be {@code APPROVED}, not a hardcoded count, so it
 * naturally covers both the one-gate and two-gate cases.
 *
 * <h2>Role enforcement — an honest scope note</h2>
 *
 * The configured role keys are read and recorded in each gate's {@code inputsRef} for display, but
 * — matching {@code GateController}'s own documented posture ("there is no role model in the
 * codebase yet") — this phase does NOT add actor-role authorization to {@code GateService.decide}.
 * Any actor able to call {@code POST /gates/{id}/decision} can decide either gate today, same as
 * every other gate in this codebase. Wiring real per-gate role authorization is a later hardening
 * pass, not invented here.
 */
@Service
public class PublishService {

  /** The D4 catalog-owner publish-review gate every submit-review opens (T013). */
  public static final String D4_GATE_KEY = "catalog-publish-review";

  /** The second, MAJOR-only architecture-board gate (T017). */
  public static final String BOARD_GATE_KEY = "catalog-architecture-board-review";

  private final DraftService draftService;
  private final DefinitionAssetRepository definitionAssetRepository;
  private final DefinitionResolutionService definitionResolutionService;
  private final GateService gateService;
  private final GateInstanceRepository gateInstanceRepository;
  private final DeltaReportService deltaReportService;
  private final AuditWriter auditWriter;
  private final ObjectMapper objectMapper;
  private final String catalogOwnerRole;
  private final String architectureBoardRole;

  public PublishService(
      DraftService draftService,
      DefinitionAssetRepository definitionAssetRepository,
      DefinitionResolutionService definitionResolutionService,
      GateService gateService,
      GateInstanceRepository gateInstanceRepository,
      DeltaReportService deltaReportService,
      AuditWriter auditWriter,
      ObjectMapper objectMapper,
      @Value("${d2os.studio.roles.catalog-owner}") String catalogOwnerRole,
      @Value("${d2os.studio.roles.architecture-board}") String architectureBoardRole) {
    this.draftService = draftService;
    this.definitionAssetRepository = definitionAssetRepository;
    this.definitionResolutionService = definitionResolutionService;
    this.gateService = gateService;
    this.gateInstanceRepository = gateInstanceRepository;
    this.deltaReportService = deltaReportService;
    this.auditWriter = auditWriter;
    this.objectMapper = objectMapper;
    this.catalogOwnerRole = catalogOwnerRole;
    this.architectureBoardRole = architectureBoardRole;
  }

  /**
   * {@code POST /catalog/drafts/{draftId}/submit-review} (T013, FR-004): Draft -&gt; InReview, pin
   * the content hash, produce the review delta report (T014), and open the D4 gate (+ a second
   * architecture-board gate iff this is a MAJOR bump, T017).
   */
  @Transactional
  public SubmitReviewResult submitForReview(UUID draftId, UUID workspaceId, String actor) {
    DefinitionAsset draft = draftService.load(draftId);

    String pinnedHash = ChecksumUtil.sha256Hex(draft.getBody());
    try {
      draft.markInReview();
    } catch (IllegalStateException e) {
      throw new PublishConflictException(e.getMessage(), e);
    }
    draft.pinContentHash(pinnedHash);
    definitionAssetRepository.save(draft);

    Optional<DefinitionView> priorOpt =
        definitionResolutionService.latestPublishedView(draft.getType(), draft.getKey());

    UUID deltaReportId = null;
    String deltaNote;
    if (priorOpt.isPresent()) {
      DefinitionView prior = priorOpt.get();
      DeltaReport report =
          deltaReportService.generateForDefinitions(
              workspaceId,
              prior.id(),
              draft.getId(),
              prettyJson(prior.body()),
              prettyJson(draft.getBody()));
      deltaReportId = report.getId();
      deltaNote =
          "diff against prior published "
              + draft.getType()
              + ":"
              + draft.getKey()
              + " v"
              + prior.version();
    } else {
      deltaNote =
          "no prior published version of "
              + draft.getType()
              + ":"
              + draft.getKey()
              + " -- first publish, delta report generation skipped (chk_delta_report_subject_shape "
              + "requires both from/to definition ids non-null; there is no legal row for a "
              + "'diff against nothing' first publish)";
    }

    boolean majorBump =
        priorOpt.map(p -> SemVer.isMajorBump(draft.getVersion(), p.version())).orElse(false);
    String inputsRef =
        inputsRefJson(
            draft,
            pinnedHash,
            deltaReportId,
            deltaNote,
            majorBump,
            priorOpt.map(DefinitionView::version).orElse(null));

    GateInstance d4Gate =
        gateService.open(
            workspaceId,
            null,
            GateType.APPROVAL,
            D4_GATE_KEY,
            1,
            GateSubjectType.DEFINITION_VERSION,
            draftId,
            inputsRef,
            catalogOwnerRole,
            1,
            null);
    attachDelta(d4Gate, deltaReportId);

    GateInstance boardGate = null;
    if (majorBump) {
      boardGate =
          gateService.open(
              workspaceId,
              null,
              GateType.APPROVAL,
              BOARD_GATE_KEY,
              1,
              GateSubjectType.DEFINITION_VERSION,
              draftId,
              inputsRef,
              architectureBoardRole,
              1,
              null);
      attachDelta(boardGate, deltaReportId);
    }

    return new SubmitReviewResult(
        draft.getId(),
        d4Gate.getId(),
        boardGate == null ? null : boardGate.getId(),
        deltaReportId,
        majorBump);
  }

  /**
   * {@code POST /catalog/drafts/{draftId}/publish} (T016/T017/T018, FR-006/007/008/017/018):
   * requires every gate opened against this draft's subject to be APPROVED, re-verifies the pinned
   * content hash, enforces semver ordering against the prior published version, and writes the
   * Published flip + AuditEntry in this one transaction.
   */
  @Transactional
  public PublishResult publish(UUID draftId, UUID workspaceId, String actor) {
    DefinitionAsset draft = draftService.load(draftId);

    if (!DefinitionAsset.Status.InReview.name().equals(draft.getStatus())) {
      throw new PublishConflictException(
          "Only InReview definitions can be published; "
              + draft.getKey()
              + " is "
              + draft.getStatus());
    }

    List<GateInstance> gates =
        gateInstanceRepository.findBySubjectTypeAndSubjectId(
            GateSubjectType.DEFINITION_VERSION.name(), draftId);
    if (gates.isEmpty()) {
      throw new PublishConflictException(
          "no review gate has been opened for " + draftId + " -- submit for review first");
    }
    long notApproved = gates.stream().filter(g -> g.status() != GateStatus.APPROVED).count();
    if (notApproved > 0) {
      throw new PublishConflictException(
          "publish requires every opened gate to be APPROVED ("
              + notApproved
              + " of "
              + gates.size()
              + " gate(s) not yet APPROVED)");
    }

    Optional<DefinitionView> priorOpt =
        definitionResolutionService.latestPublishedView(draft.getType(), draft.getKey());
    if (priorOpt.isPresent()) {
      String priorVersion = priorOpt.get().version();
      if (SemVer.compare(draft.getVersion(), priorVersion) <= 0) {
        throw new PublishConflictException(
            "version "
                + draft.getVersion()
                + " is not greater than the prior published version "
                + priorVersion
                + " of "
                + draft.getType()
                + ":"
                + draft.getKey());
      }
    }

    String recomputedHash = ChecksumUtil.sha256Hex(draft.getBody());
    try {
      draft.markPublishedFromReview(recomputedHash);
    } catch (IllegalStateException e) {
      throw new PublishConflictException(e.getMessage(), e);
    }

    try {
      definitionAssetRepository.save(draft);
    } catch (DataIntegrityViolationException e) {
      // Defense-in-depth: uq_definition_type_key_version is GLOBAL (not per-workspace, V3),
      // so a genuine duplicate (type,key,version) is already refused at draft CREATE time
      // (DraftController, T008) -- this row has held that exact tuple since it was created,
      // so this catch is not known to be reachable through the normal flow, but is here so a
      // constraint violation surfaces as a 409 conflict rather than a raw 500 either way.
      throw new PublishConflictException(
          "duplicate (type,key,version): "
              + draft.getType()
              + "/"
              + draft.getKey()
              + "/"
              + draft.getVersion(),
          e);
    }

    // 008 US5 (T051): publishing a catalog definition version is a trust-sensitive decision —
    // stamp the authenticated publisher + catalog-owner role (no-op/NULL in default mode).
    // "catalog-owner" mirrors d2os.studio.roles.catalog-owner and the endpoint's @PreAuthorize
    // gate.
    auditWriter.recordDecision(
        workspaceId,
        "definition_asset",
        draft.getId(),
        "DEFINITION_PUBLISHED",
        actor,
        "catalog-owner",
        Map.of(
            "type",
            draft.getType(),
            "key",
            draft.getKey(),
            "version",
            draft.getVersion(),
            "checksum",
            recomputedHash,
            "gateCount",
            gates.size()));

    return new PublishResult(
        draft.getId(),
        draft.getType(),
        draft.getKey(),
        draft.getVersion(),
        draft.getChecksum(),
        draft.getStatus());
  }

  private void attachDelta(GateInstance gate, UUID deltaReportId) {
    if (deltaReportId == null) {
      return;
    }
    gate.attachDeltaReport(deltaReportId);
    gateInstanceRepository.save(gate);
  }

  private String prettyJson(String rawJson) {
    try {
      Object tree = objectMapper.readTree(rawJson);
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    } catch (Exception e) {
      return rawJson;
    }
  }

  private String inputsRefJson(
      DefinitionAsset draft,
      String pinnedHash,
      UUID deltaReportId,
      String deltaNote,
      boolean majorBump,
      String priorVersion) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("definitionId", draft.getId().toString());
    ref.put("type", draft.getType());
    ref.put("key", draft.getKey());
    ref.put("version", draft.getVersion());
    ref.put("pinnedContentHash", pinnedHash);
    ref.put("deltaReportId", deltaReportId == null ? null : deltaReportId.toString());
    ref.put("deltaNote", deltaNote);
    ref.put("majorVersionBump", majorBump);
    ref.put("priorPublishedVersion", priorVersion);
    ref.put("catalogOwnerRole", catalogOwnerRole);
    if (majorBump) {
      ref.put("architectureBoardRole", architectureBoardRole);
    }
    try {
      return objectMapper.writeValueAsString(ref);
    } catch (Exception e) {
      return "{}";
    }
  }

  public record SubmitReviewResult(
      UUID draftId,
      UUID d4GateId,
      UUID architectureBoardGateId,
      UUID deltaReportId,
      boolean majorVersionBump) {}

  public record PublishResult(
      UUID draftId, String type, String key, String version, String checksum, String status) {}
}
