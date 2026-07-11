package com.d2os.catalog;

import java.util.UUID;

/**
 * A resolved published definition including its id and body (for readers that need the content, not
 * just the ref).
 */
public record DefinitionView(UUID id, String type, String key, String version, String body) {
  public DefinitionRef toRef() {
    return new DefinitionRef(type, key, version);
  }
}
