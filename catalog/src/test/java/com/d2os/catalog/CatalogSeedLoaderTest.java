package com.d2os.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Fast, plain-JUnit calibration test for {@link CatalogSeedLoader} (T065). {@code
 * DefinitionAsset.body} is a Postgres {@code JSON}-typed column
 * ({@code @JdbcTypeCode(SqlTypes.JSON)}) — a malformed body would fail loudly on any real IT's
 * insert, but that is a slow (Testcontainers) way to catch a JSON syntax mistake in hand-authored
 * template content. This mocks the repository/publish-service so every body {@link
 * CatalogSeedLoader#run} would seed can be parsed and asserted directly, no database.
 */
class CatalogSeedLoaderTest {

  @Test
  void everySeededBodyIsValidJson() throws Exception {
    DefinitionAssetRepository repository = mock(DefinitionAssetRepository.class);
    DefinitionPublishService publishService = mock(DefinitionPublishService.class);
    when(repository.findByTypeAndKeyAndVersion(anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    // Capture every DefinitionAsset the repository is asked to save — that is every seed() call,
    // template/playbook/persona/etc alike.
    List<DefinitionAsset> seeded = new ArrayList<>();
    when(repository.save(any()))
        .thenAnswer(
            inv -> {
              DefinitionAsset asset = inv.getArgument(0);
              seeded.add(asset);
              return asset;
            });

    CatalogSeedLoader loader = new CatalogSeedLoader(repository, publishService);
    loader.run(new DefaultApplicationArguments());

    assertFalse(seeded.isEmpty(), "expected CatalogSeedLoader to seed at least one definition");

    ObjectMapper mapper = new ObjectMapper();
    List<String> malformed = new ArrayList<>();
    for (DefinitionAsset asset : seeded) {
      try {
        mapper.readTree(asset.getBody());
      } catch (Exception e) {
        malformed.add(
            asset.getType()
                + ":"
                + asset.getKey()
                + ":"
                + asset.getVersion()
                + " -> "
                + e.getMessage());
      }
    }
    assertTrue(
        malformed.isEmpty(), () -> "malformed JSON bodies:\n" + String.join("\n", malformed));
  }

  @Test
  void nineTemplatesAndThreePlaybooksAreSeededWithExpectedFields() throws Exception {
    DefinitionAssetRepository repository = mock(DefinitionAssetRepository.class);
    DefinitionPublishService publishService = mock(DefinitionPublishService.class);
    when(repository.findByTypeAndKeyAndVersion(anyString(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    List<DefinitionAsset> seeded = new ArrayList<>();
    when(repository.save(any()))
        .thenAnswer(
            inv -> {
              DefinitionAsset asset = inv.getArgument(0);
              seeded.add(asset);
              return asset;
            });

    CatalogSeedLoader loader = new CatalogSeedLoader(repository, publishService);
    loader.run(new DefaultApplicationArguments());

    List<DefinitionAsset> templates =
        seeded.stream().filter(a -> "template".equals(a.getType())).toList();
    List<DefinitionAsset> playbooks =
        seeded.stream().filter(a -> "playbook".equals(a.getType())).toList();

    assertEquals(9, templates.size(), "T065: 7 revised + 2 greenfield = 9 templates");
    // Phase 1 playbooks (T065) + the pre-existing Phase 3 knowledge-curation playbook.
    assertEquals(4, playbooks.size(), "3 Phase-1 playbooks + 1 pre-existing Phase-3 playbook");
    assertTrue(
        templates.stream().anyMatch(a -> "task-breakdown".equals(a.getKey())),
        "greenfield Task Breakdown template must be present");
    assertTrue(
        templates.stream().anyMatch(a -> "handover-record".equals(a.getKey())),
        "greenfield Handover Record template must be present");

    ObjectMapper mapper = new ObjectMapper();
    for (DefinitionAsset t : templates) {
      JsonNode node = mapper.readTree(t.getBody());
      assertTrue(
          node.has("title")
              && node.has("personaKey")
              && node.has("defines")
              && node.has("references")
              && node.has("content"),
          () -> t.getKey() + " is missing an expected template field: " + node);
      assertFalse(
          node.get("content").asText().isBlank(), () -> t.getKey() + " content must not be blank");
    }
  }
}
