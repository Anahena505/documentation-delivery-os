package com.d2os.catalog;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draft lifecycle for the studio (Phase 6, tasks.md T007, research R2, FR-001). Creates and edits
 * {@code Draft}-status {@link DefinitionAsset} rows for any of the eight definition types — the
 * studio's {@code DraftController} (T008, later) is the only caller in this phase; the
 * InReview-freeze and content-hash pinning described by data-model.md fall naturally out of {@link
 * DefinitionAsset#updateBody}'s Draft-only guard once a later task ({@code PublishController},
 * T013) actually calls {@link DefinitionAsset#markInReview()}.
 *
 * <p>Deliberately does NOT call {@link DefinitionPublishService#publish}: drafts stay unpublished
 * and are not resolvable by any running case (resolution filters on {@code status = 'Published'})
 * until they pass the Phase 5 approval-gate flow (US2, out of this task's scope). This is the
 * opposite of {@link CatalogSeedLoader#seed}, which goes straight Draft -&gt; Published for
 * system-seeded content — that seeding path is untouched by this service.
 */
@Service
public class DraftService {

  private final DefinitionAssetRepository repository;

  public DraftService(DefinitionAssetRepository repository) {
    this.repository = repository;
  }

  /**
   * Create a new Draft row for {@code (type, key, version)}. Relies on the existing {@code
   * uq_definition_type_key_version} unique constraint (V3) to surface a duplicate as a persistence
   * conflict — {@code DraftController} (T008) is what turns that into the spec's 409, not this
   * method.
   */
  @Transactional
  public DefinitionAsset create(
      String type, String key, String version, String body, UUID workspaceId, String actor) {
    DefinitionAsset draft =
        new DefinitionAsset(UUID.randomUUID(), workspaceId, key, version, type, "en", body, actor);
    return repository.save(draft);
  }

  /** Load a draft (or any definition_asset row) by id. */
  @Transactional(readOnly = true)
  public DefinitionAsset load(UUID draftId) {
    return repository
        .findById(draftId)
        .orElseThrow(() -> new NoSuchElementException("definition " + draftId));
  }

  /**
   * Replace a draft's body. Guarded to {@code Draft} status by {@link DefinitionAsset#updateBody}
   * itself (throws {@link IllegalStateException} otherwise) — this single guard is what refuses
   * edits both for an ordinary Published row and for a row currently {@code InReview} (the
   * "InReview freeze" data-model.md describes), since {@code InReview} is a distinct, non-Draft
   * status.
   */
  @Transactional
  public DefinitionAsset update(UUID draftId, String newBody) {
    DefinitionAsset draft = load(draftId);
    draft.updateBody(newBody);
    return repository.save(draft);
  }
}
