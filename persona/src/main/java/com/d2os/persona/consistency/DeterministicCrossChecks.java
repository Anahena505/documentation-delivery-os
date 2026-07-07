package com.d2os.persona.consistency;

import com.d2os.artifacts.storage.ObjectStoreClient;
import com.d2os.persona.OperationExecution;
import com.d2os.persona.OperationExecutionRepository;
import com.d2os.persona.PersonaInvocation;
import com.d2os.persona.PersonaInvocationRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tier-1 consistency checks (US3, FR-006/007/019): pure, deterministic, no AI. Parses each validated
 * persona output for a machine-readable index block and flags contradictions across the WHOLE output
 * set produced so far — the parallel specialists and the upstream sequential personas (FR-019).
 *
 * <p>Index-block grammar (rendered by the persona templates, T014): lines of the form
 * {@code defines: ns:id, ns:id}, {@code references: ns:id, ns:id}, and {@code attr: name=value}.
 * Two findings are produced:
 * <ul>
 *   <li>{@code DANGLING_REFERENCE} — an id referenced by one output that no output defines;</li>
 *   <li>{@code ATTRIBUTE_CONTRADICTION} — the same attribute name asserted with different values by
 *       two outputs.</li>
 * </ul>
 * Both are DETERMINISTIC (blocking). Findings anchor to the {@code operation_execution} ids of the
 * outputs involved, since artifacts do not exist yet at check time.
 */
@Component
public class DeterministicCrossChecks {

    private final PersonaInvocationRepository invocationRepository;
    private final OperationExecutionRepository operationExecutionRepository;
    private final ObjectStoreClient objectStoreClient;

    public DeterministicCrossChecks(PersonaInvocationRepository invocationRepository,
                                    OperationExecutionRepository operationExecutionRepository,
                                    ObjectStoreClient objectStoreClient) {
        this.invocationRepository = invocationRepository;
        this.operationExecutionRepository = operationExecutionRepository;
        this.objectStoreClient = objectStoreClient;
    }

    /** Returns unsaved findings; the caller persists them and writes CONFLICTS_WITH edges. */
    public List<ConsistencyFinding> check(UUID caseId, UUID workspaceId) {
        // Final output per validated persona (skip the consistency reviewer itself if present).
        Map<UUID, String> opText = new HashMap<>();                 // operationExecutionId -> output text
        for (PersonaInvocation inv : invocationRepository.findByCaseInstanceIdOrderBySequenceNoAsc(caseId)) {
            if (!PersonaInvocation.Status.validated.name().equals(inv.getStatus())) continue;
            if ("consistency-reviewer".equals(inv.getPersonaKey())) continue;
            List<OperationExecution> attempts =
                    operationExecutionRepository.findByPersonaInvocationIdOrderByAttemptNoAsc(inv.getId());
            if (attempts.isEmpty()) continue;
            OperationExecution finalAttempt = attempts.get(attempts.size() - 1);
            if (finalAttempt.getOutputRef() == null) continue;
            opText.put(finalAttempt.getId(), fetch(finalAttempt.getOutputRef()));
        }

        Set<String> definedIds = new HashSet<>();
        Map<UUID, List<String>> referencesByOp = new HashMap<>();
        Map<String, Map<String, UUID>> attrValueToOp = new HashMap<>();   // name -> (value -> firstOpId)
        List<ConsistencyFinding> findings = new ArrayList<>();

        // First pass: collect definitions and per-op references/attributes.
        for (Map.Entry<UUID, String> e : opText.entrySet()) {
            UUID opId = e.getKey();
            for (String line : e.getValue().split("\\r?\\n")) {
                String l = line.strip();
                String lower = l.toLowerCase();
                if (lower.startsWith("defines:")) {
                    definedIds.addAll(items(l.substring("defines:".length())));
                } else if (lower.startsWith("references:")) {
                    referencesByOp.computeIfAbsent(opId, k -> new ArrayList<>())
                            .addAll(items(l.substring("references:".length())));
                } else if (lower.startsWith("attr:")) {
                    String[] kv = l.substring("attr:".length()).split("=", 2);
                    if (kv.length == 2) {
                        String name = kv[0].strip();
                        String value = kv[1].strip();
                        Map<String, UUID> byValue = attrValueToOp.computeIfAbsent(name, k -> new HashMap<>());
                        byValue.putIfAbsent(value, opId);
                    }
                }
            }
        }

        // Dangling references: referenced id defined by no output.
        referencesByOp.forEach((opId, refs) -> {
            for (String ref : refs) {
                if (!ref.isBlank() && !definedIds.contains(ref)) {
                    findings.add(new ConsistencyFinding(
                            UUID.randomUUID(), workspaceId, caseId,
                            ConsistencyFinding.Tier.DETERMINISTIC, ConsistencyFinding.Kind.DANGLING_REFERENCE,
                            ref, opId, null,
                            "{\"reference\":\"" + esc(ref) + "\"}"));
                }
            }
        });

        // Attribute contradictions: same name asserted with two different values.
        attrValueToOp.forEach((name, byValue) -> {
            if (byValue.size() >= 2) {
                var it = byValue.entrySet().iterator();
                var first = it.next();
                var second = it.next();
                findings.add(new ConsistencyFinding(
                        UUID.randomUUID(), workspaceId, caseId,
                        ConsistencyFinding.Tier.DETERMINISTIC, ConsistencyFinding.Kind.ATTRIBUTE_CONTRADICTION,
                        name, first.getValue(), second.getValue(),
                        "{\"attribute\":\"" + esc(name) + "\",\"values\":[\"" + esc(first.getKey())
                                + "\",\"" + esc(second.getKey()) + "\"]}"));
            }
        });

        return findings;
    }

    private List<String> items(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private String fetch(String ref) {
        try {
            return new String(objectStoreClient.get(ref), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
