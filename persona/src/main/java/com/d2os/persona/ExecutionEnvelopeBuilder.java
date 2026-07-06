package com.d2os.persona;

import com.d2os.casecore.CaseDefinitionSnapshot;
import com.d2os.casecore.CaseDefinitionSnapshotRepository;
import com.d2os.casecore.CaseInstance;
import com.d2os.casecore.CaseInstanceRepository;
import com.d2os.catalog.DefinitionLookupService;
import com.d2os.catalog.DefinitionView;
import com.d2os.persona.spi.SubmissionDataPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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

    public ExecutionEnvelopeBuilder(CaseInstanceRepository caseRepository,
                                    CaseDefinitionSnapshotRepository snapshotRepository,
                                    DefinitionLookupService definitionLookup,
                                    SubmissionDataPort submissionDataPort,
                                    ObjectMapper objectMapper) {
        this.caseRepository = caseRepository;
        this.snapshotRepository = snapshotRepository;
        this.definitionLookup = definitionLookup;
        this.submissionDataPort = submissionDataPort;
        this.objectMapper = objectMapper;
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

        return new PersonaEnvelope(
                caseId, personaKey,
                personaDef.id(), personaDef.version(),
                promptDef.id(), promptDef.version(), extractTemplate(promptDef.body()),
                rubricDef.id(), rubricDef.version(), rubricDef.body(),
                formDataJson);
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
