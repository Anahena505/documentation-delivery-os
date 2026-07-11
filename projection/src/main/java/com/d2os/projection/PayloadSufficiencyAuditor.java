package com.d2os.projection;

import com.d2os.tenancy.WorkspaceContext;
import com.d2os.tenancy.security.WorkspaceRlsBinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T011 — validates outbox events against the projector's declared per-event-type required-field
 * sets (research R6, FR-011). Only the {@code gate_instance} events are given a real field
 * contract in Phase 3 — those are the ONLY ones whose payload the {@link Projector}/{@link
 * NodeEdgeMapper} actually reads to shape a node/edge (the Phase 5 {@code GateEventPayload}
 * contract this projector consumes). Case-lifecycle events ({@code Planned}/{@code Running}/...)
 * are mapped by re-reading {@code case_instance} directly (see {@link Projector}'s javadoc) rather
 * than by parsing the outbox payload, so there is no payload field for them to be "thin" on —
 * declaring an (empty) contract for them here would be theater, not a real check. Every OTHER
 * {@code event_type} enumerated by grepping {@code auditWriter.record(} call sites across the repo
 * ({@code CASE_TYPE_CONFIRMED}/{@code CASE_TYPE_OVERRIDDEN}, {@code ARTIFACT_WRITE_REFUSED},
 * {@code DEFINITION_PUBLISHED}, {@code GATE_APPROVE}/{@code GATE_REJECT}/{@code
 * GATE_REQUEST_CHANGES}, the knowledge-module events) is simply not a type the Phase 3 projector
 * maps at all — {@link #auditGateEvent} returns {@link AuditResult#notDeclared()} for anything
 * outside {@code gate_instance}'s three real event types, and the {@link Projector} never calls
 * this auditor for those either. This IS a scoped-down decision, documented honestly rather than
 * inventing hypothetical requirements for event types nothing downstream projects yet.
 *
 * <h2>Two tiers of "required" (R6's "skip only the unprojectable parts")</h2>
 * Each gate event type's contract splits into {@code structural} fields (without which a GATE node
 * cannot even be identified/labelled — {@code gateId}/{@code gateType}/{@code gateDefinitionKey}/
 * {@code gateDefinitionVersion}) and {@code content} fields (present in the {@code
 * GateEventPayload} contract but not needed to place the node — e.g. {@code GATE_DECIDED}'s {@code
 * decisionVerb}/{@code deciderId}). A structurally-thin event is NOT projectable at all (the whole
 * event is skipped, a gap recorded); a content-thin event is STILL projected (the missing
 * attribute just comes through null, exactly as {@link NodeEdgeMapper#mapGateEvent} already
 * tolerates), with a gap recorded for the missing content so it surfaces via {@code GET
 * /graph/admin/gaps} rather than being silently absorbed.
 */
@Component
public class PayloadSufficiencyAuditor {

    private static final Logger log = LoggerFactory.getLogger(PayloadSufficiencyAuditor.class);

    private static final String CONSUMER = "graph-projector";

    /** One event type's declared field contract (research R6, the {@code GateEventPayload} shape). */
    private record FieldContract(List<String> structural, List<String> content) {}

    private static final Map<String, FieldContract> GATE_EVENT_CONTRACTS = Map.of(
            "GATE_OPENED", new FieldContract(
                    List.of("gateId", "gateType", "gateDefinitionKey", "gateDefinitionVersion"),
                    List.of()),
            "GATE_DECIDED", new FieldContract(
                    List.of("gateId", "gateType", "gateDefinitionKey", "gateDefinitionVersion"),
                    List.of("decisionVerb", "deciderId")),
            "GATE_REGENERATION_TRIGGERED", new FieldContract(
                    List.of("gateId", "gateType", "gateDefinitionKey", "gateDefinitionVersion"),
                    List.of("producedArtifactRevisionId")));

    /**
     * Result of auditing one event against its declared contract.
     *
     * @param declared      false when this event type carries no contract in Phase 3 (not audited
     *                      at all — see class javadoc)
     * @param projectable   false only when a STRUCTURAL field is missing — the event cannot be
     *                      mapped at all and the {@link Projector} must skip it entirely
     * @param missingFields every missing field (structural + content), for the {@link
     *                      ProjectionGap} row's {@code missing_fields} column
     */
    public record AuditResult(boolean declared, boolean projectable, List<String> missingFields) {
        static AuditResult notDeclared() {
            return new AuditResult(false, true, List.of());
        }
    }

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final WorkspaceRlsBinder workspaceRlsBinder;
    private final PlatformTransactionManager transactionManager;
    private final GraphWriteRepository graphWriteRepository;
    private final ProjectorRlsBinder projectorRlsBinder;
    private final PlatformTransactionManager projectorTransactionManager;
    private final int gapAlertThreshold;

    public PayloadSufficiencyAuditor(ObjectMapper objectMapper,
                                     JdbcTemplate jdbcTemplate,
                                     WorkspaceRlsBinder workspaceRlsBinder,
                                     PlatformTransactionManager transactionManager,
                                     GraphWriteRepository graphWriteRepository,
                                     ProjectorRlsBinder projectorRlsBinder,
                                     @Qualifier("projectorTransactionManager") PlatformTransactionManager projectorTransactionManager,
                                     @Value("${d2os.projection.gap-alert-threshold:10}") int gapAlertThreshold) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.workspaceRlsBinder = workspaceRlsBinder;
        this.transactionManager = transactionManager;
        this.graphWriteRepository = graphWriteRepository;
        this.projectorRlsBinder = projectorRlsBinder;
        this.projectorTransactionManager = projectorTransactionManager;
        this.gapAlertThreshold = gapAlertThreshold;
    }

    /**
     * Pure audit (no DB access) — {@code aggregateType}/{@code eventType} straight off the {@code
     * event_outbox} row, {@code payloadJson} its raw JSONB text. Returns {@link
     * AuditResult#notDeclared()} for anything not one of the 3 real gate event types (see class
     * javadoc).
     */
    public AuditResult auditGateEvent(String aggregateType, String eventType, String payloadJson) {
        if (!"gate_instance".equals(aggregateType)) {
            return AuditResult.notDeclared();
        }
        FieldContract contract = GATE_EVENT_CONTRACTS.get(eventType);
        if (contract == null) {
            // e.g. GATE_APPROVE/GATE_REJECT/GATE_REQUEST_CHANGES — GateService's own thin
            // decision-verb audit row, superseded by the GATE_DECIDED event GateEventPublisher
            // emits in the same transaction. Not a type the projector maps (see class javadoc).
            return AuditResult.notDeclared();
        }
        Map<String, Object> payload = parse(payloadJson);
        List<String> missingStructural = missing(payload, contract.structural());
        List<String> missingContent = missing(payload, contract.content());
        List<String> allMissing = new ArrayList<>(missingStructural);
        allMissing.addAll(missingContent);
        return new AuditResult(true, missingStructural.isEmpty(), List.copyOf(allMissing));
    }

    /**
     * Records a {@link ProjectionGap} for one insufficient event and — past {@code
     * d2os.projection.gap-alert-threshold} OPEN gaps for this workspace — logs a WARN-level alert.
     * Runs in its own transaction against the {@code d2os_projector} datasource (the caller's own
     * read-side transaction against the app datasource is a separate connection entirely, per
     * T007's dual-datasource design); the caller is responsible for having already decided this
     * event needs a gap row (i.e. {@link AuditResult#missingFields()} is non-empty).
     */
    public void recordGap(UUID workspaceId, UUID eventId, String eventType, List<String> missingFields) {
        TransactionTemplate tx = requiresNew(projectorTransactionManager);
        tx.executeWithoutResult(status -> {
            projectorRlsBinder.bindCurrentTransaction(workspaceId);
            graphWriteRepository.insertGap(new ProjectionGap(UUID.randomUUID(), workspaceId, eventId, eventType,
                    missingFields.toArray(new String[0]), OffsetDateTime.now(), ProjectionGap.Status.OPEN));
        });
        alertIfPastThreshold(workspaceId);
    }

    /**
     * Logs a WARN-level alert if this workspace has at least {@code d2os.projection.gap-alert-
     * threshold} OPEN {@link ProjectionGap} rows. Public/standalone so both {@link #recordGap} and
     * {@link Projector} (which writes its own gap rows in the same transaction as the graph writes
     * they accompany, rather than through {@link #recordGap} — see {@link Projector}'s javadoc) can
     * trigger the same threshold check after a gap-producing write commits.
     */
    public void alertIfPastThreshold(UUID workspaceId) {
        long openCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM projection_gap WHERE workspace_id = ? AND status = 'OPEN'",
                Long.class, workspaceId);
        if (openCount >= gapAlertThreshold) {
            log.warn("projection sufficiency: workspace {} has {} OPEN projection_gap rows (threshold {}) — "
                            + "likely a systemic emitter-side problem, not isolated thin events",
                    workspaceId, openCount, gapAlertThreshold);
        }
    }

    /**
     * "Dry sweep" mode (research R6, the E7.1 payload audit): re-audits every {@code gate_instance}
     * event ever recorded for this workspace — regardless of {@code projection_checkpoint}'s
     * watermark — WITHOUT re-projecting anything, purely for retroactive gap analysis. Existing
     * OPEN gaps for an event are not duplicated (checked by {@code event_id} before inserting).
     */
    public DrySweepSummary drySweep(UUID workspaceId) {
        WorkspaceContext.set(workspaceId);
        List<Map<String, Object>> events;
        try {
            TransactionTemplate readTx = requiresNew(transactionManager);
            events = readTx.execute(status -> {
                workspaceRlsBinder.bindCurrentTransaction(workspaceId);
                return jdbcTemplate.queryForList(
                        "SELECT id, event_type, payload FROM event_outbox "
                                + "WHERE workspace_id = ? AND aggregate_type = 'gate_instance' ORDER BY seq",
                        workspaceId);
            });
        } finally {
            WorkspaceContext.clear();
        }

        int audited = 0;
        int thin = 0;
        List<UUID> alreadyGapped = existingGapEventIds(workspaceId);
        for (Map<String, Object> row : events) {
            audited++;
            UUID eventId = (UUID) row.get("id");
            String eventType = (String) row.get("event_type");
            String payloadJson = String.valueOf(row.get("payload"));
            AuditResult result = auditGateEvent("gate_instance", eventType, payloadJson);
            if (result.declared() && !result.missingFields().isEmpty()) {
                thin++;
                if (!alreadyGapped.contains(eventId)) {
                    recordGap(workspaceId, eventId, eventType, result.missingFields());
                }
            }
        }
        return new DrySweepSummary(audited, thin);
    }

    /** Count of previously-recorded gap event ids, so a re-run of the dry sweep does not duplicate rows. */
    private List<UUID> existingGapEventIds(UUID workspaceId) {
        TransactionTemplate readTx = requiresNew(transactionManager);
        return readTx.execute(status -> {
            workspaceRlsBinder.bindCurrentTransaction(workspaceId);
            return jdbcTemplate.queryForList(
                    "SELECT event_id FROM projection_gap WHERE workspace_id = ?", UUID.class, workspaceId);
        });
    }

    public record DrySweepSummary(int auditedEventCount, int thinEventCount) {}

    private List<String> missing(Map<String, Object> payload, List<String> fields) {
        List<String> result = new ArrayList<>();
        for (String field : fields) {
            Object value = payload.get(field);
            if (value == null || (value instanceof String s && s.isBlank())) {
                result.add(field);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String payloadJson) {
        try {
            return payloadJson == null ? Map.of() : objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            // Malformed JSON is the ultimate "thin" event — every declared field is missing.
            return Map.of();
        }
    }

    private TransactionTemplate requiresNew(PlatformTransactionManager manager) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(manager, def);
    }
}
