package com.d2os.persona;

import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.catalog.DefinitionLookupService;
import com.d2os.catalog.DefinitionView;
import com.d2os.persona.gateway.WorkspaceScopeGuard;
import com.d2os.persona.spi.AttachmentSummaryPort;
import com.d2os.persona.spi.KnowledgeProvider;
import com.d2os.persona.spi.SubmissionDataPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Builds the stateless execution envelope for one persona step (T029, AD-8), resolving every
 * definition it needs from the Case's <em>pinned snapshot only</em> — never from the live catalog —
 * so a running Case is immune to concurrent catalog changes (AD-4). The snapshot stores only
 * (type,key,version); each entry's real id is re-resolved here via {@link DefinitionLookupService}
 * so downstream OperationExecution rows reference genuine DefinitionAsset ids.
 */
@Component
public class ExecutionEnvelopeBuilder {

    private final CaseInstanceRepository caseRepository;
    private final CaseDefinitionSnapshotRepository snapshotRepository;
    private final DefinitionLookupService definitionLookup;
    private final SubmissionDataPort submissionDataPort;
    private final ObjectMapper objectMapper;
    // ObjectProvider so persona-only slice tests (no knowledge module on the path) still wire this
    // builder: getIfAvailable() yields null when no KnowledgeProvider bean exists → no injection (T013).
    private final ObjectProvider<KnowledgeProvider> knowledgeProvider;
    // ObjectProvider so persona-only slice tests (no intake on the path) still wire this builder:
    // getIfAvailable() yields null when no AttachmentSummaryPort bean exists → no summaries (T044).
    private final ObjectProvider<AttachmentSummaryPort> attachmentSummaryPort;
    private final WorkspaceScopeGuard workspaceScopeGuard;
    private final int maxItemsPerOperation;

    public ExecutionEnvelopeBuilder(CaseInstanceRepository caseRepository,
                                    CaseDefinitionSnapshotRepository snapshotRepository,
                                    DefinitionLookupService definitionLookup,
                                    SubmissionDataPort submissionDataPort,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<KnowledgeProvider> knowledgeProvider,
                                    ObjectProvider<AttachmentSummaryPort> attachmentSummaryPort,
                                    WorkspaceScopeGuard workspaceScopeGuard,
                                    @Value("${d2os.knowledge.max-items-per-operation:5}") int maxItemsPerOperation) {
        this.caseRepository = caseRepository;
        this.snapshotRepository = snapshotRepository;
        this.definitionLookup = definitionLookup;
        this.submissionDataPort = submissionDataPort;
        this.objectMapper = objectMapper;
        this.knowledgeProvider = knowledgeProvider;
        this.attachmentSummaryPort = attachmentSummaryPort;
        this.workspaceScopeGuard = workspaceScopeGuard;
        this.maxItemsPerOperation = maxItemsPerOperation;
    }

    public PersonaEnvelope build(UUID caseId, String personaKey) {
        CaseInstance kase = caseRepository.findById(caseId)
                .orElseThrow(() -> new NoSuchElementException("case " + caseId));
        CaseDefinitionSnapshot snapshot = snapshotRepository.findByCaseInstanceId(caseId)
                .orElseThrow(() -> new IllegalStateException("case " + caseId + " has no pinned snapshot"));

        JsonNode entries = readEntries(snapshot.getEntries());
        VersionRef personaRef = find(entries, "persona", personaKey);
        VersionRef promptRef = find(entries, "prompt", personaKey + "-prompt");
        VersionRef rubricRef = find(entries, "rubric", personaKey + "-rubric");

        DefinitionView personaDef = resolve("persona", personaRef);
        DefinitionView promptDef = resolve("prompt", promptRef);
        DefinitionView rubricDef = resolve("rubric", rubricRef);

        String formDataJson = submissionDataPort.findFormDataJson(kase.getSubmissionId())
                .orElseThrow(() -> new IllegalStateException("submission " + kase.getSubmissionId() + " not found"));

        // US5 (T044, FR-015): sanitized attachment summaries only — never raw bytes. Empty when no
        // AttachmentSummaryPort bean is on the path (persona-only slice tests) or the submission has none.
        List<String> attachmentSummaries = resolveAttachmentSummaries(kase.getSubmissionId());

        // Phase 3 (T013): resolve the persona's knowledge profile from its definition body, retrieve the
        // entitled items, and assert workspace scope (T015) before they enter the envelope.
        List<String> knowledgeProfile = extractKnowledgeProfile(personaDef.body());
        List<KnowledgeProvider.InjectedItem> injectedKnowledge = retrieveKnowledge(kase, knowledgeProfile);
        workspaceScopeGuard.assertSameWorkspace(kase.getWorkspaceId(), injectedKnowledge);
        int estimatedInjectedTokens = estimateTokens(injectedKnowledge);

        return new PersonaEnvelope(
                caseId, personaKey,
                personaDef.id(), personaDef.version(),
                promptDef.id(), promptDef.version(), extractTemplate(promptDef.body()),
                rubricDef.id(), rubricDef.version(), rubricDef.body(),
                formDataJson,
                injectedKnowledge, estimatedInjectedTokens,
                attachmentSummaries);
    }

    /** Sanitized attachment summaries for the submission, or empty when none/no port is wired (T044). */
    private List<String> resolveAttachmentSummaries(UUID submissionId) {
        AttachmentSummaryPort port = attachmentSummaryPort.getIfAvailable();
        if (port == null || submissionId == null) {
            return List.of();
        }
        return port.findSummaryTexts(submissionId);
    }

    /**
     * Retrieve governed knowledge for this operation. When no {@link KnowledgeProvider} bean is on the
     * path (persona-only slice tests) or the profile is empty, returns an empty list — identical to
     * pre-Phase-3 behavior. projectId is null in US1 (the seed set is WORKSPACE-scoped).
     */
    private List<KnowledgeProvider.InjectedItem> retrieveKnowledge(CaseInstance kase, List<String> profile) {
        KnowledgeProvider provider = knowledgeProvider.getIfAvailable();
        if (provider == null || profile.isEmpty()) {
            return List.of();
        }
        KnowledgeProvider.KnowledgeQuery query = new KnowledgeProvider.KnowledgeQuery(
                kase.getWorkspaceId(), null, profile, profile, maxItemsPerOperation);
        return provider.retrieve(query);
    }

    /** Parse the persona definition body's {@code knowledgeProfile} string array (empty if absent). */
    private List<String> extractKnowledgeProfile(String personaBody) {
        List<String> profile = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(personaBody).path("knowledgeProfile");
            if (node.isArray()) {
                for (JsonNode tag : node) {
                    profile.add(tag.asText());
                }
            }
        } catch (Exception e) {
            // A malformed/absent body means no profile → no injection (fail open to empty, not to error).
            return List.of();
        }
        return profile;
    }

    /** Rough token estimate (~4 chars/token) for the injected content, charged against the case budget. */
    private int estimateTokens(List<KnowledgeProvider.InjectedItem> items) {
        int chars = 0;
        for (KnowledgeProvider.InjectedItem item : items) {
            if (item.content() != null) chars += item.content().length();
        }
        return chars / 4;
    }

    private DefinitionView resolve(String type, VersionRef ref) {
        return definitionLookup.byTypeKeyVersion(type, ref.key(), ref.version())
                .orElseThrow(() -> new IllegalStateException(
                        "pinned " + type + " definition missing from catalog: " + ref.key() + " v" + ref.version()));
    }

    private JsonNode readEntries(String entriesJson) {
        try {
            return objectMapper.readTree(entriesJson);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed snapshot entries", e);
        }
    }

    private VersionRef find(JsonNode entries, String type, String key) {
        for (JsonNode entry : entries) {
            if (type.equals(entry.path("type").asText()) && key.equals(entry.path("key").asText())) {
                return new VersionRef(key, entry.path("version").asText());
            }
        }
        throw new NoSuchElementException("no pinned " + type + " definition for key " + key);
    }

    private String extractTemplate(String promptBody) {
        try {
            return objectMapper.readTree(promptBody).path("template").asText();
        } catch (Exception e) {
            return promptBody;
        }
    }

    private record VersionRef(String key, String version) {}
}
