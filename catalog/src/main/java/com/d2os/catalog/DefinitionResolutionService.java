package com.d2os.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves ({@code key, version}) references to published definitions (T013, Principle I).
 *
 * <p>v1 selects the highest published version lexically; semver-aware ordering is a refinement
 * tracked against T013. Callers pin the resolved refs into a CaseDefinitionSnapshot so a running
 * Case never re-resolves against a moving catalog (AD-4).
 */
@Service
public class DefinitionResolutionService {

  private final DefinitionAssetRepository repository;

  public DefinitionResolutionService(DefinitionAssetRepository repository) {
    this.repository = repository;
  }

  /** Latest published version of a (type, key), if any. */
  public Optional<DefinitionRef> latestPublished(String type, String key) {
    return latestPublishedView(type, key).map(DefinitionView::toRef);
  }

  /** Same as {@link #latestPublished}, but including the body — for readers that need content. */
  public Optional<DefinitionView> latestPublishedView(String type, String key) {
    return repository
        .findFirstByTypeAndKeyAndStatusOrderByVersionDesc(type, key, "Published")
        .map(
            d ->
                new DefinitionView(
                    d.getId(), d.getType(), d.getKey(), d.getVersion(), d.getBody()));
  }

  /** All published definitions sharing a key (e.g. the assets that make up a case type). */
  public List<DefinitionRef> publishedByKey(String key) {
    return repository.findByKeyAndStatus(key, "Published").stream()
        .map(d -> new DefinitionRef(d.getType(), d.getKey(), d.getVersion()))
        .toList();
  }
}
