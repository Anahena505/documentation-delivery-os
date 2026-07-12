package com.d2os.governance;

import com.d2os.artifacts.ArtifactRevision;
import com.d2os.artifacts.storage.ObjectStoreClient;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic unified diff between two revisions of one Artifact (T020, research R2, FR-005) — no
 * AI summary anywhere in this path (Q4's "no endpoint accepts artifact content from a human for an
 * AI-drafted artifact" contract is symmetric: nothing here re-writes content either, it only
 * describes the difference two already-immutable revisions represent). {@code java-diff-utils}
 * (Myers algorithm) is fully deterministic for a fixed pair of inputs, so replaying a regeneration
 * reproduces the identical {@code diffContent}/{@code diffHash} every time.
 *
 * <p>Called by {@code RegenerationDelegate} (orchestration) right after {@code
 * ArtifactService.createRevision} produces the new revision (T019/T020 wiring) — the resulting
 * {@code DeltaReport.id} is threaded back as a process variable and attached to the newly
 * (re-)opened {@code GateInstance.deltaReportId} by {@code GateTaskBridge}, so {@code GET
 * /gates/{id}/delta-report} (T021) resolves it for the re-presented reviewer.
 */
@Service
public class DeltaReportService {

  private final DeltaReportRepository deltaReportRepository;
  private final ObjectStoreClient objectStoreClient;

  public DeltaReportService(
      DeltaReportRepository deltaReportRepository, ObjectStoreClient objectStoreClient) {
    this.deltaReportRepository = deltaReportRepository;
    this.objectStoreClient = objectStoreClient;
  }

  /**
   * Diff {@code from}'s content against {@code to}'s content (both revisions of the same Artifact —
   * callers are responsible for that invariant) and persist the result. {@code diffHash} is SHA-256
   * over {@code diffContent} exactly (T020) — a tamper check / reproducibility fingerprint on the
   * report itself, distinct from either revision's own {@code contentHash}.
   */
  @Transactional
  public DeltaReport generate(
      UUID workspaceId, UUID artifactId, ArtifactRevision from, ArtifactRevision to) {
    List<String> fromLines = readLines(from.getStorageRef());
    List<String> toLines = readLines(to.getStorageRef());

    Patch<String> patch = DiffUtils.diff(fromLines, toLines);
    List<String> unifiedDiffLines =
        UnifiedDiffUtils.generateUnifiedDiff(
            "revision-" + from.getId(), "revision-" + to.getId(), fromLines, patch, 3);
    String diffContent = String.join("\n", unifiedDiffLines);
    String diffHash = HashUtil.sha256Hex(diffContent);

    DeltaReport report =
        new DeltaReport(
            UUID.randomUUID(),
            workspaceId,
            artifactId,
            from.getId(),
            to.getId(),
            diffContent,
            diffHash);
    return deltaReportRepository.save(report);
  }

  private List<String> readLines(String storageRef) {
    byte[] bytes = objectStoreClient.get(storageRef);
    String text = new String(bytes, StandardCharsets.UTF_8);
    return Arrays.asList(text.split("\n", -1));
  }

  /**
   * Diff two {@code DefinitionAsset} bodies directly (tasks.md T014, research R3) — the studio's D4
   * publish-review content (prompt/persona diffs, or a canonical-JSON content diff for any other
   * type; this method makes no type distinction, since both are "diff this JSON text against that
   * JSON text" — see {@code studio.PublishService} for why one code path serves both). Unlike
   * {@link #generate}, there is no {@link ObjectStoreClient}/{@code ArtifactRevision} involved:
   * content already lives in-memory as {@code definition_asset.body}, and the caller is expected to
   * have already pretty-printed both bodies for a readable diff (mirrors {@code
   * StudioPageController}'s own pretty-print convention) before calling this. Persists using the
   * V26 definition-pair {@code delta_report} shape ({@code from_definition_id}/{@code
   * to_definition_id}; the artifact-triple columns stay null — {@code
   * chk_delta_report_subject_shape} enforces exactly one shape per row).
   *
   * <p>{@code fromDefinitionId}/{@code toDefinitionId} must both be non-null (the CHECK constraint
   * requires it) — there is no "diff against nothing" row for a first-ever publish of a new key;
   * callers must skip calling this entirely in that case.
   */
  @Transactional
  public DeltaReport generateForDefinitions(
      UUID workspaceId,
      UUID fromDefinitionId,
      UUID toDefinitionId,
      String fromBodyPretty,
      String toBodyPretty) {
    List<String> fromLines =
        Arrays.asList((fromBodyPretty == null ? "" : fromBodyPretty).split("\n", -1));
    List<String> toLines =
        Arrays.asList((toBodyPretty == null ? "" : toBodyPretty).split("\n", -1));

    Patch<String> patch = DiffUtils.diff(fromLines, toLines);
    List<String> unifiedDiffLines =
        UnifiedDiffUtils.generateUnifiedDiff(
            "definition-" + fromDefinitionId, "definition-" + toDefinitionId, fromLines, patch, 3);
    String diffContent = String.join("\n", unifiedDiffLines);
    String diffHash = HashUtil.sha256Hex(diffContent);

    DeltaReport report =
        new DeltaReport(
            UUID.randomUUID(),
            workspaceId,
            fromDefinitionId,
            toDefinitionId,
            diffContent,
            diffHash);
    return deltaReportRepository.save(report);
  }
}
