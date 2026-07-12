package com.d2os.studio.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Server-side half of the dmn-js editor bridge (tasks.md T010, plan.md {@code editor/} subpackage,
 * FR-002). dmn-js (T002's vendored JS island — see {@code
 * studio/src/main/resources/static/studio/vendor/README.md}, not actually vendored in this sandbox:
 * no outbound internet access to fetch it) is a rich CLIENT-side decision-table editor; this class
 * is deliberately NOT a DMN model of its own. It is a thin passthrough that lets a Rule draft's
 * body carry raw DMN XML text so a browser-side dmn-js instance can GET the XML, edit it visually,
 * and PUT the edited XML straight back — no server-side parsing, validation, or understanding of
 * DMN semantics happens here.
 *
 * <p>{@code definition_asset.body} is JSONB (cannot hold raw XML directly), so the XML is wrapped
 * as a single JSON string field: {@code {"dmnXml": "<...>"}}. This is a DIFFERENT body shape from
 * the {@code {"decisionKey":...,"engine":"flowable-dmn"}} POINTER shape {@code CatalogSeedLoader}
 * seeds for already-published rules (e.g. {@code submission-classification}, {@code
 * case-type-classification}) — those point at a classpath {@code *.dmn} resource under {@code
 * orchestration}/{@code governance}'s {@code src/main/resources} rather than embedding XML in the
 * catalog row, which is why {@link #fromBodyJson} returns {@code null} for that legacy shape: there
 * genuinely is no XML in the catalog row to hand back, only a decisionKey pointer. Draft rows
 * authored through THIS bridge (new Rule drafts created via the studio DMN editor) use the {@code
 * dmnXml} shape end to end, via {@code DraftController}'s {@code
 * /api/v1/catalog/drafts/{draftId}/dmn-xml} endpoints.
 */
@Component
public class DmnEditorBridge {

  private final ObjectMapper objectMapper;

  public DmnEditorBridge(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Wrap raw DMN XML (as dmn-js would POST it) into the JSON string the Draft body column stores.
   */
  public String toBodyJson(String dmnXml) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("dmnXml", dmnXml == null ? "" : dmnXml);
    return root.toString();
  }

  /**
   * Unwrap a Draft's body JSON back to raw DMN XML for dmn-js to GET and render. Returns {@code
   * null} for the legacy {@code {"decisionKey":...}} pointer shape (nothing to visually edit
   * through this bridge — see class javadoc) or for any body that fails to parse as JSON.
   */
  public String fromBodyJson(String bodyJson) {
    try {
      JsonNode root = objectMapper.readTree(bodyJson);
      if (root.has("dmnXml")) {
        return root.path("dmnXml").asText();
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
