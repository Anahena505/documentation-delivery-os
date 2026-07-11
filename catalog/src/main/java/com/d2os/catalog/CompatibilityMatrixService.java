package com.d2os.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Computed (never stored) compatibility matrix — evaluates {@code compatibleWith} range
 * declarations a definition body may carry against the CURRENT latest published version of each
 * declared dependency, flagging any that fall out of range (Phase 6 US3, T022, research R5,
 * FR-011). {@code compatibleWith} shape: {@code [{"type":"template","key":"...","range":">=1.0.0
 * <2.0.0"}]} — a range is a space-separated list of {@code op}+semver conditions ({@code >=, >, <=,
 * <, =}), all of which must hold.
 *
 * <p><b>Disclosed gap</b>: no seeded catalog content in this codebase currently declares {@code
 * compatibleWith} (the convention this service introduces is new) — every {@link #evaluate} call
 * against today's real seed data returns an empty matrix. The mechanism itself is real and
 * genuinely evaluates any definition that DOES declare the field; it's simply that none does yet.
 */
@Service
public class CompatibilityMatrixService {

  private static final Pattern CONDITION = Pattern.compile("(>=|<=|>|<|=)\\s*(\\d+\\.\\d+\\.\\d+)");

  public record CompatibilityEntry(
      String definitionType,
      String definitionKey,
      String definitionVersion,
      String dependencyType,
      String dependencyKey,
      String declaredRange,
      String resolvedVersion,
      boolean inRange) {}

  private final DefinitionAssetRepository repository;
  private final DefinitionResolutionService definitionResolution;
  private final ObjectMapper objectMapper;

  public CompatibilityMatrixService(
      DefinitionAssetRepository repository,
      DefinitionResolutionService definitionResolution,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.definitionResolution = definitionResolution;
    this.objectMapper = objectMapper;
  }

  public List<CompatibilityEntry> evaluate() {
    List<CompatibilityEntry> matrix = new ArrayList<>();
    for (DefinitionAsset def : repository.findByStatus(DefinitionAsset.Status.Published.name())) {
      JsonNode declarations;
      try {
        declarations = objectMapper.readTree(def.getBody()).path("compatibleWith");
      } catch (Exception e) {
        continue; // malformed body JSON — nothing to evaluate for this row
      }
      if (!declarations.isArray()) {
        continue;
      }
      for (JsonNode decl : declarations) {
        String depType = decl.path("type").asText(null);
        String depKey = decl.path("key").asText(null);
        String range = decl.path("range").asText(null);
        if (depType == null || depKey == null || range == null) {
          continue;
        }
        String resolvedVersion =
            definitionResolution
                .latestPublished(depType, depKey)
                .map(DefinitionRef::version)
                .orElse(null);
        boolean inRange = resolvedVersion != null && inRange(range, resolvedVersion);
        matrix.add(
            new CompatibilityEntry(
                def.getType(),
                def.getKey(),
                def.getVersion(),
                depType,
                depKey,
                range,
                resolvedVersion,
                inRange));
      }
    }
    return matrix;
  }

  private boolean inRange(String range, String version) {
    CatalogSemVer actual = CatalogSemVer.parse(version);
    Matcher m = CONDITION.matcher(range);
    boolean matchedAny = false;
    while (m.find()) {
      matchedAny = true;
      String op = m.group(1);
      CatalogSemVer bound = CatalogSemVer.parse(m.group(2));
      int cmp = actual.compareTo(bound);
      boolean holds =
          switch (op) {
            case ">=" -> cmp >= 0;
            case ">" -> cmp > 0;
            case "<=" -> cmp <= 0;
            case "<" -> cmp < 0;
            case "=" -> cmp == 0;
            default -> false;
          };
      if (!holds) return false;
    }
    return matchedAny;
  }
}
