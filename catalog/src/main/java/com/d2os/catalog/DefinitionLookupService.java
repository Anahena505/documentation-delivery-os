package com.d2os.catalog;

import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Fetches a pinned (key,version) definition's full body — used by pipelines executing against a
 * frozen snapshot (AD-4).
 */
@Service
public class DefinitionLookupService {

  private final DefinitionAssetRepository repository;

  public DefinitionLookupService(DefinitionAssetRepository repository) {
    this.repository = repository;
  }

  public Optional<DefinitionView> byTypeKeyVersion(String type, String key, String version) {
    return repository
        .findByTypeAndKeyAndVersion(type, key, version)
        .map(
            d ->
                new DefinitionView(
                    d.getId(), d.getType(), d.getKey(), d.getVersion(), d.getBody()));
  }
}
