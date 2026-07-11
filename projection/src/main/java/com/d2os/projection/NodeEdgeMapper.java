package com.d2os.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * T006 — pure event/edge-row -> typed graph node/edge mapping (data-model.md's Node/Edge Mapping
 * table, research R3). No DB access: every method here is a deterministic function of its input
 * {@code *Fact} record, so it is directly unit-testable and safe to call twice for the same source
 * fact (idempotency, FR-012) without any Projector/rebuild machinery around it. Assembling a
 * complete {@code *Fact} from an outbox event payload or a source-table row (including any join a
 * fact needs, e.g. resolving an {@code Artifact.artifactType} for its revisions) is the {@code
 * Projector}'s job (T008, a later phase) — this class only transforms already-assembled facts.
 *
 * <h2>Deterministic ids (research R3)</h2>
 *
 * {@link GraphNode#getId()}/{@link GraphEdge#getId()} are surrogate UUIDs, but they are derived
 * with {@link UUID#nameUUIDFromBytes} from exactly the columns the V28 UNIQUE constraints cover —
 * {@code (workspaceId, generation, nodeType, naturalKey)} for nodes, {@code (workspaceId,
 * generation, edgeType, fromNode, toNode, sourceRef)} for edges. That makes id derivation itself
 * pure and reproducible: an edge that references a node computes the SAME id for that node the
 * node-creating call would, without a DB round-trip to look it up. This is a deliberate design
 * choice beyond the literal task text (which only asked for natural-key determinism), needed to
 * keep this class DB-free while still letting edges resolve their endpoints.
 *
 * <h2>The CONFLICTS_WITH mapping decision</h2>
 *
 * {@code trace_link.link_type} is free text; the only value ANY code in this repo currently writes
 * is {@code 'CONFLICTS_WITH'} ({@code ConsistencyService#writeConflictEdge}), which is NOT one of
 * data-model.md's closed {@code graph_edge.edge_type} values ({@code TRACES_TO|DEPENDS_ON|
 * DERIVES_FROM|SATISFIES|INJECTED_INTO|PRODUCED|GATED_BY|BELONGS_TO}). {@link
 * #mapTraceLink(TraceLinkFact, int)} maps any {@code link_type} outside the three explicitly-named
 * kinds ({@code DERIVES_FROM}, {@code SATISFIES}, {@code TRACES_TO} itself) to edge_type {@code
 * TRACES_TO} with the real {@code link_type} preserved in {@code attributes.linkType} — this keeps
 * the edge_type enum closed per data-model.md (an application-layer invariant; V28 does not add a
 * DB CHECK constraint on edge_type, matching how node_type is left open for the REQUIREMENT subtype
 * below) while never silently dropping a real trace_link row, CONFLICTS_WITH included.
 *
 * <h2>Requirement-type artifact revisions</h2>
 *
 * {@code artifact_revision} carries no type of its own — {@code artifact.artifact_type} does (e.g.
 * {@code "requirement:brd"}, the {@code CatalogSeedLoader} "produces" tag convention). {@link
 * #mapArtifactRevision(ArtifactRevisionFact, int)} treats any {@code artifactType} starting with
 * {@code "requirement:"} as requirement-type (FR-005) and emits an ADDITIONAL {@code REQUIREMENT}
 * node sharing the SAME {@code natural_key}/provenance as the {@code ARTIFACT_REVISION} node (two
 * node_type rows, same source fact) — exactly data-model.md's mapping-table wording.
 */
@Component
public class NodeEdgeMapper {

  // --- node_type values (data-model.md; REQUIREMENT is the artifact subtype called out in the
  // mapping table but not in the node_type prose list — included here since the mapping table is
  // the authoritative per-source-type contract) ---
  public static final String NODE_CASE = "CASE";
  public static final String NODE_SUBMISSION = "SUBMISSION";
  public static final String NODE_ARTIFACT_REVISION = "ARTIFACT_REVISION";
  public static final String NODE_PACKAGE = "PACKAGE";
  public static final String NODE_DEFINITION_VERSION = "DEFINITION_VERSION";
  public static final String NODE_KNOWLEDGE_ITEM_VERSION = "KNOWLEDGE_ITEM_VERSION";
  public static final String NODE_OPERATION_EXECUTION = "OPERATION_EXECUTION";
  public static final String NODE_GATE = "GATE";
  public static final String NODE_FEATURE = "FEATURE";
  public static final String NODE_PROJECT = "PROJECT";
  public static final String NODE_REQUIREMENT = "REQUIREMENT";
  // US6 (spec 008, T058): the TemplateDefinition an artifact revision was rendered from, projected
  // (like REQUIREMENT) as an ADDITIONAL node sharing the artifact-provenance natural key. Only
  // emitted when an artifact_revision carries source_template_id + template_version.
  public static final String NODE_TEMPLATE = "TEMPLATE";

  // --- edge_type values (data-model.md's closed set; US6 adds PRODUCED_FROM for artifact
  // provenance) ---
  public static final String EDGE_TRACES_TO = "TRACES_TO";
  public static final String EDGE_DEPENDS_ON = "DEPENDS_ON";
  public static final String EDGE_DERIVES_FROM = "DERIVES_FROM";
  public static final String EDGE_SATISFIES = "SATISFIES";
  public static final String EDGE_INJECTED_INTO = "INJECTED_INTO";
  public static final String EDGE_PRODUCED = "PRODUCED";
  public static final String EDGE_GATED_BY = "GATED_BY";
  public static final String EDGE_BELONGS_TO = "BELONGS_TO";
  // US6 (spec 008, T058): ARTIFACT_REVISION -> TEMPLATE, "this revision's content was produced from
  // that pinned template version" (data-model.md §3). Only emitted when provenance is present.
  public static final String EDGE_PRODUCED_FROM = "PRODUCED_FROM";

  // --- source_kind values (data-model.md GraphNode.source_kind, research R2) ---
  public static final String SOURCE_OUTBOX_EVENT = "OUTBOX_EVENT";
  public static final String SOURCE_TRACE_LINK = "TRACE_LINK";
  public static final String SOURCE_DEPENDENCY = "DEPENDENCY";
  public static final String SOURCE_INJECTION_SNAPSHOT = "INJECTION_SNAPSHOT";

  private final ObjectMapper objectMapper;

  public NodeEdgeMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  // =================================================================================================
  // Case lifecycle outbox events -> CASE node (+ BELONGS_TO -> FEATURE)
  // =================================================================================================

  public record CaseLifecycleFact(
      UUID workspaceId,
      UUID caseId,
      UUID featureId,
      String status,
      UUID sourceEventId,
      OffsetDateTime occurredAt) {}

  public MappingResult mapCaseLifecycle(CaseLifecycleFact fact, int generation) {
    List<GraphNode> nodes = new ArrayList<>();
    List<GraphEdge> edges = new ArrayList<>();
    String sourceRef = fact.sourceEventId().toString();

    Map<String, Object> caseAttrs = new LinkedHashMap<>();
    caseAttrs.put("status", fact.status());
    GraphNode caseNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_CASE,
            fact.caseId().toString(),
            fact.status(),
            caseAttrs,
            SOURCE_OUTBOX_EVENT,
            sourceRef,
            fact.occurredAt());
    nodes.add(caseNode);

    if (fact.featureId() != null) {
      GraphNode featureNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_FEATURE,
              fact.featureId().toString(),
              "Feature",
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt());
      nodes.add(featureNode);
      edges.add(
          edge(
              fact.workspaceId(),
              generation,
              EDGE_BELONGS_TO,
              caseNode.getId(),
              featureNode.getId(),
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt()));
    }
    return new MappingResult(nodes, edges);
  }

  // =================================================================================================
  // Artifact/package events -> ARTIFACT_REVISION / PACKAGE nodes; PRODUCED edges from executions;
  // requirement-type revisions ALSO get a REQUIREMENT node (FR-005).
  // =================================================================================================

  public record ArtifactRevisionFact(
      UUID workspaceId,
      UUID artifactId,
      UUID artifactRevisionId,
      int revisionNo,
      String artifactType,
      UUID producedByOperationExecutionId,
      UUID sourceTemplateId,
      String templateVersion,
      UUID sourceEventId,
      OffsetDateTime createdAt) {}

  public MappingResult mapArtifactRevision(ArtifactRevisionFact fact, int generation) {
    List<GraphNode> nodes = new ArrayList<>();
    List<GraphEdge> edges = new ArrayList<>();
    String sourceRef = fact.sourceEventId().toString();
    String naturalKey = fact.artifactRevisionId().toString();

    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("artifactId", fact.artifactId().toString());
    attrs.put("revisionNo", fact.revisionNo());
    attrs.put("artifactType", fact.artifactType());
    GraphNode revisionNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_ARTIFACT_REVISION,
            naturalKey,
            fact.artifactType() + " r" + fact.revisionNo(),
            attrs,
            SOURCE_OUTBOX_EVENT,
            sourceRef,
            fact.createdAt());
    nodes.add(revisionNode);

    // Requirement-type artifact revisions ALSO project as a REQUIREMENT node — same natural
    // key/provenance as the revision (data-model.md mapping table, FR-005). Convention: an
    // artifactType tagged "requirement:<...>" (CatalogSeedLoader's produces-tag convention,
    // e.g. "requirement:brd") marks a requirement-type template.
    if (isRequirementType(fact.artifactType())) {
      GraphNode requirementNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_REQUIREMENT,
              naturalKey,
              fact.artifactType() + " r" + fact.revisionNo(),
              attrs,
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt());
      nodes.add(requirementNode);
    }

    if (fact.producedByOperationExecutionId() != null) {
      GraphNode operationNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_OPERATION_EXECUTION,
              fact.producedByOperationExecutionId().toString(),
              "Operation Execution",
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt());
      nodes.add(operationNode);
      edges.add(
          edge(
              fact.workspaceId(),
              generation,
              EDGE_PRODUCED,
              operationNode.getId(),
              revisionNode.getId(),
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt()));
    }

    // US6 (spec 008, T058, FR-014): a revision rendered from a pinned TemplateDefinition carries
    // source_template_id + template_version. Project that provenance as a TEMPLATE node (plus a
    // DEFINITION_VERSION node sharing its natural key — the same "one source fact, two node_type
    // rows, same natural key" pattern as the REQUIREMENT subtype above) and a PRODUCED_FROM edge
    // ARTIFACT_REVISION -> TEMPLATE. Natural key is {@code sourceTemplateId:templateVersion} —
    // self-contained (no join), so the incremental Projector, RebuildJob, and EquivalenceVerifier
    // compute an identical key. Absent for every pre-US6 (placeholder) revision, so this is purely
    // additive to the existing graph.
    if (fact.sourceTemplateId() != null && fact.templateVersion() != null) {
      String templateKey = fact.sourceTemplateId() + ":" + fact.templateVersion();
      Map<String, Object> templateAttrs = new LinkedHashMap<>();
      templateAttrs.put("sourceTemplateId", fact.sourceTemplateId().toString());
      templateAttrs.put("templateVersion", fact.templateVersion());
      GraphNode templateNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_TEMPLATE,
              templateKey,
              templateKey,
              templateAttrs,
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt());
      nodes.add(templateNode);
      GraphNode definitionNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_DEFINITION_VERSION,
              templateKey,
              templateKey,
              templateAttrs,
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt());
      nodes.add(definitionNode);
      edges.add(
          edge(
              fact.workspaceId(),
              generation,
              EDGE_PRODUCED_FROM,
              revisionNode.getId(),
              templateNode.getId(),
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.createdAt()));
    }
    return new MappingResult(nodes, edges);
  }

  /** {@code artifactType} starting with {@code "requirement:"} — see class javadoc. */
  public static boolean isRequirementType(String artifactType) {
    return artifactType != null && artifactType.startsWith("requirement:");
  }

  public record PackageFact(
      UUID workspaceId,
      UUID packageId,
      UUID caseInstanceId,
      UUID sourceEventId,
      OffsetDateTime createdAt) {}

  public MappingResult mapPackage(PackageFact fact, int generation) {
    String sourceRef = fact.sourceEventId().toString();
    GraphNode packageNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_PACKAGE,
            fact.packageId().toString(),
            "Package",
            Map.of(),
            SOURCE_OUTBOX_EVENT,
            sourceRef,
            fact.createdAt());
    // No edge_type in data-model.md's mapping table covers package membership (that is a
    // manifest-content relationship, not a graph edge in v1) — node-only, matching the mapping
    // table's literal "Artifact/package events -> ARTIFACT_REVISION, PACKAGE nodes" wording.
    return new MappingResult(List.of(packageNode), List.of());
  }

  // =================================================================================================
  // trace_link rows -> TRACES_TO / DERIVES_FROM / SATISFIES edges (kind-preserving)
  // =================================================================================================

  public record TraceLinkFact(
      UUID workspaceId,
      UUID linkId,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkType,
      OffsetDateTime createdAt) {}

  public MappingResult mapTraceLink(TraceLinkFact fact, int generation) {
    String sourceRef = fact.linkId().toString();
    GraphNode fromNode =
        node(
            fact.workspaceId(),
            generation,
            nodeTypeForSourceTable(fact.fromType()),
            fact.fromId().toString(),
            fact.fromType(),
            Map.of(),
            SOURCE_TRACE_LINK,
            sourceRef,
            fact.createdAt());
    GraphNode toNode =
        node(
            fact.workspaceId(),
            generation,
            nodeTypeForSourceTable(fact.toType()),
            fact.toId().toString(),
            fact.toType(),
            Map.of(),
            SOURCE_TRACE_LINK,
            sourceRef,
            fact.createdAt());

    String edgeType;
    Map<String, Object> attrs = new LinkedHashMap<>();
    String linkTypeUpper = fact.linkType() == null ? "" : fact.linkType().toUpperCase();
    switch (linkTypeUpper) {
      case "DERIVES_FROM" -> edgeType = EDGE_DERIVES_FROM;
      case "SATISFIES" -> edgeType = EDGE_SATISFIES;
      case "TRACES_TO" -> edgeType = EDGE_TRACES_TO;
      default -> {
        // CONFLICTS_WITH (the only kind any code in this repo currently writes) and any
        // other future/unknown link_type fall back to TRACES_TO with the real kind
        // preserved — see class javadoc's "CONFLICTS_WITH mapping decision".
        edgeType = EDGE_TRACES_TO;
        attrs.put("linkType", fact.linkType());
      }
    }

    GraphEdge traceEdge =
        edge(
            fact.workspaceId(),
            generation,
            edgeType,
            fromNode.getId(),
            toNode.getId(),
            attrs,
            SOURCE_TRACE_LINK,
            sourceRef,
            fact.createdAt());
    return new MappingResult(List.of(fromNode, toNode), List.of(traceEdge));
  }

  // =================================================================================================
  // dependency rows -> DEPENDS_ON edges. No writer exists anywhere in this repo today (verified) —
  // dead code until something starts writing `dependency` rows, implemented anyway per T006's
  // instruction so the mapping is ready the moment a writer lands.
  // =================================================================================================

  public record DependencyFact(
      UUID workspaceId,
      UUID dependencyId,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String depType,
      OffsetDateTime createdAt) {}

  public MappingResult mapDependency(DependencyFact fact, int generation) {
    String sourceRef = fact.dependencyId().toString();
    GraphNode fromNode =
        node(
            fact.workspaceId(),
            generation,
            nodeTypeForSourceTable(fact.fromType()),
            fact.fromId().toString(),
            fact.fromType(),
            Map.of(),
            SOURCE_DEPENDENCY,
            sourceRef,
            fact.createdAt());
    GraphNode toNode =
        node(
            fact.workspaceId(),
            generation,
            nodeTypeForSourceTable(fact.toType()),
            fact.toId().toString(),
            fact.toType(),
            Map.of(),
            SOURCE_DEPENDENCY,
            sourceRef,
            fact.createdAt());

    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("depType", fact.depType());
    GraphEdge dependsOnEdge =
        edge(
            fact.workspaceId(),
            generation,
            EDGE_DEPENDS_ON,
            fromNode.getId(),
            toNode.getId(),
            attrs,
            SOURCE_DEPENDENCY,
            sourceRef,
            fact.createdAt());
    return new MappingResult(List.of(fromNode, toNode), List.of(dependsOnEdge));
  }

  // =================================================================================================
  // knowledge_injection_snapshot rows -> KNOWLEDGE_ITEM_VERSION node + INJECTED_INTO edge
  // =================================================================================================

  public record InjectionSnapshotFact(
      UUID workspaceId,
      UUID snapshotId,
      UUID operationExecutionId,
      UUID knowledgeItemId,
      String knowledgeItemKey,
      int knowledgeItemVersion,
      int position,
      OffsetDateTime createdAt) {}

  public MappingResult mapInjectionSnapshot(InjectionSnapshotFact fact, int generation) {
    String sourceRef = fact.snapshotId().toString();
    String naturalKey = fact.knowledgeItemKey() + ":" + fact.knowledgeItemVersion();

    Map<String, Object> itemAttrs = new LinkedHashMap<>();
    itemAttrs.put("knowledgeItemId", fact.knowledgeItemId().toString());
    itemAttrs.put("key", fact.knowledgeItemKey());
    itemAttrs.put("version", fact.knowledgeItemVersion());
    GraphNode itemNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_KNOWLEDGE_ITEM_VERSION,
            naturalKey,
            naturalKey,
            itemAttrs,
            SOURCE_INJECTION_SNAPSHOT,
            sourceRef,
            fact.createdAt());

    GraphNode operationNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_OPERATION_EXECUTION,
            fact.operationExecutionId().toString(),
            "Operation Execution",
            Map.of(),
            SOURCE_INJECTION_SNAPSHOT,
            sourceRef,
            fact.createdAt());

    Map<String, Object> edgeAttrs = new LinkedHashMap<>();
    edgeAttrs.put("position", fact.position());
    GraphEdge injectedIntoEdge =
        edge(
            fact.workspaceId(),
            generation,
            EDGE_INJECTED_INTO,
            itemNode.getId(),
            operationNode.getId(),
            edgeAttrs,
            SOURCE_INJECTION_SNAPSHOT,
            sourceRef,
            fact.createdAt());

    return new MappingResult(List.of(itemNode, operationNode), List.of(injectedIntoEdge));
  }

  // =================================================================================================
  // Gate events (GateEventPayload, Phase 5) -> GATE node + GATED_BY edge. Only the three event
  // types GateEventPublisher currently emits (GATE_OPENED, GATE_DECIDED,
  // GATE_REGENERATION_TRIGGERED) are exercised by any real source today; the other four
  // GateEventPayload types are later governance phases' responsibility and map through the same
  // fact shape once they land.
  // =================================================================================================

  public record GateEventFact(
      UUID workspaceId,
      UUID gateId,
      String gateType,
      String gateDefinitionKey,
      int gateDefinitionVersion,
      UUID caseInstanceId,
      UUID subjectArtifactRevisionId,
      String eventType,
      String decisionVerb,
      String deciderId,
      UUID sourceEventId,
      OffsetDateTime occurredAt) {}

  public MappingResult mapGateEvent(GateEventFact fact, int generation) {
    List<GraphNode> nodes = new ArrayList<>();
    List<GraphEdge> edges = new ArrayList<>();
    String sourceRef = fact.sourceEventId().toString();

    Map<String, Object> gateAttrs = new LinkedHashMap<>();
    gateAttrs.put("gateType", fact.gateType());
    gateAttrs.put("gateDefinitionKey", fact.gateDefinitionKey());
    gateAttrs.put("gateDefinitionVersion", fact.gateDefinitionVersion());
    gateAttrs.put("eventType", fact.eventType());
    if (fact.decisionVerb() != null) gateAttrs.put("decisionVerb", fact.decisionVerb());
    if (fact.deciderId() != null) gateAttrs.put("deciderId", fact.deciderId());
    GraphNode gateNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_GATE,
            fact.gateId().toString(),
            fact.gateType() + ":" + fact.gateDefinitionKey(),
            gateAttrs,
            SOURCE_OUTBOX_EVENT,
            sourceRef,
            fact.occurredAt());
    nodes.add(gateNode);

    // Subject: a case (the common path) or a DEFINITION_VERSION-subject gate's artifact
    // revision (V27, studio publish review — caseInstanceId is null for those). GATED_BY runs
    // subject -> GATE ("this subject is gated by this gate"); a gate with neither (should not
    // happen per V20/V27's schema, but defensively handled) emits the GATE node alone.
    if (fact.subjectArtifactRevisionId() != null) {
      GraphNode subjectNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_ARTIFACT_REVISION,
              fact.subjectArtifactRevisionId().toString(),
              "Artifact Revision",
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt());
      nodes.add(subjectNode);
      edges.add(
          edge(
              fact.workspaceId(),
              generation,
              EDGE_GATED_BY,
              subjectNode.getId(),
              gateNode.getId(),
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt()));
    } else if (fact.caseInstanceId() != null) {
      GraphNode caseNode =
          node(
              fact.workspaceId(),
              generation,
              NODE_CASE,
              fact.caseInstanceId().toString(),
              "Case",
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt());
      nodes.add(caseNode);
      edges.add(
          edge(
              fact.workspaceId(),
              generation,
              EDGE_GATED_BY,
              caseNode.getId(),
              gateNode.getId(),
              Map.of(),
              SOURCE_OUTBOX_EVENT,
              sourceRef,
              fact.occurredAt()));
    }
    return new MappingResult(nodes, edges);
  }

  // =================================================================================================
  // Definition references (in snapshots/events) -> DEFINITION_VERSION nodes (exact key:version)
  // =================================================================================================

  public record DefinitionRefFact(
      UUID workspaceId,
      String definitionType,
      String definitionKey,
      String definitionVersion,
      String sourceKind,
      UUID sourceRefId,
      OffsetDateTime occurredAt) {}

  public MappingResult mapDefinitionRef(DefinitionRefFact fact, int generation) {
    String naturalKey =
        fact.definitionType() + ":" + fact.definitionKey() + ":" + fact.definitionVersion();
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("type", fact.definitionType());
    attrs.put("key", fact.definitionKey());
    attrs.put("version", fact.definitionVersion());
    GraphNode definitionNode =
        node(
            fact.workspaceId(),
            generation,
            NODE_DEFINITION_VERSION,
            naturalKey,
            naturalKey,
            attrs,
            fact.sourceKind(),
            fact.sourceRefId().toString(),
            fact.occurredAt());
    return new MappingResult(List.of(definitionNode), List.of());
  }

  // =================================================================================================
  // Shared helpers
  // =================================================================================================

  /**
   * The graph the mapper contributes for one source fact — zero or more nodes, zero or more edges.
   */
  public record MappingResult(List<GraphNode> nodes, List<GraphEdge> edges) {}

  /**
   * Known polymorphic-edge-endpoint source tables ({@code trace_link}/{@code dependency}'s {@code
   * from_type}/{@code to_type}, e.g. {@code "operation_execution"} — {@code
   * ConsistencyService#writeConflictEdge}) mapped to their {@code graph_node.node_type}.
   * Unknown/future source types fall back to the upper-cased table name rather than failing — a
   * forward-compatible default, not a silent drop (the node still projects, just without a curated
   * node_type).
   */
  private static String nodeTypeForSourceTable(String sourceTable) {
    if (sourceTable == null) return "UNKNOWN";
    return switch (sourceTable) {
      case "case_instance" -> NODE_CASE;
      case "problem_submission" -> NODE_SUBMISSION;
      case "artifact_revision" -> NODE_ARTIFACT_REVISION;
      case "execution_package" -> NODE_PACKAGE;
      case "definition_asset" -> NODE_DEFINITION_VERSION;
      case "knowledge_item" -> NODE_KNOWLEDGE_ITEM_VERSION;
      case "operation_execution" -> NODE_OPERATION_EXECUTION;
      case "gate_instance" -> NODE_GATE;
      case "feature" -> NODE_FEATURE;
      case "project" -> NODE_PROJECT;
      default -> sourceTable.toUpperCase();
    };
  }

  private GraphNode node(
      UUID workspaceId,
      int generation,
      String nodeType,
      String naturalKey,
      String label,
      Map<String, Object> attributes,
      String sourceKind,
      String sourceRef,
      OffsetDateTime projectedAt) {
    UUID id = nodeId(workspaceId, generation, nodeType, naturalKey);
    return new GraphNode(
        id,
        workspaceId,
        generation,
        nodeType,
        naturalKey,
        label,
        toJson(attributes),
        sourceKind,
        sourceRef,
        projectedAt);
  }

  private GraphEdge edge(
      UUID workspaceId,
      int generation,
      String edgeType,
      UUID fromNode,
      UUID toNode,
      Map<String, Object> attributes,
      String sourceKind,
      String sourceRef,
      OffsetDateTime projectedAt) {
    UUID id = edgeId(workspaceId, generation, edgeType, fromNode, toNode, sourceRef);
    return new GraphEdge(
        id,
        workspaceId,
        generation,
        edgeType,
        fromNode,
        toNode,
        toJson(attributes),
        sourceKind,
        sourceRef,
        projectedAt);
  }

  /**
   * Deterministic surrogate id from exactly the {@code uq_graph_node_natural_key} columns (see
   * class javadoc).
   */
  public static UUID nodeId(UUID workspaceId, int generation, String nodeType, String naturalKey) {
    String composite = workspaceId + "|" + generation + "|" + nodeType + "|" + naturalKey;
    return UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Deterministic surrogate id from exactly the {@code uq_graph_edge_identity} columns (see class
   * javadoc).
   */
  public static UUID edgeId(
      UUID workspaceId,
      int generation,
      String edgeType,
      UUID fromNode,
      UUID toNode,
      String sourceRef) {
    String composite =
        workspaceId
            + "|"
            + generation
            + "|"
            + edgeType
            + "|"
            + fromNode
            + "|"
            + toNode
            + "|"
            + sourceRef;
    return UUID.nameUUIDFromBytes(composite.getBytes(StandardCharsets.UTF_8));
  }

  private String toJson(Map<String, Object> attributes) {
    try {
      return objectMapper.writeValueAsString(attributes == null ? Map.of() : attributes);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unserializable graph element attributes", e);
    }
  }
}
