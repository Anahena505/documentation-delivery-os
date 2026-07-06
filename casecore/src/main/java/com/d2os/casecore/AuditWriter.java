package com.d2os.casecore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Same-transaction audit + outbox write helper (T017, Principle V). {@code MANDATORY} propagation
 * means this can only be called from within an already-open transaction — it never opens its own,
 * so the audit/event rows are guaranteed to commit or roll back atomically with the state change
 * they describe.
 */
@Component
public class AuditWriter {

    private final AuditEntryRepository auditRepository;
    private final EventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AuditWriter(AuditEntryRepository auditRepository,
                       EventOutboxRepository outboxRepository,
                       ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID workspaceId, String subjectType, UUID subjectId, String action,
                       String actor, Map<String, Object> details) {
        String detailsJson = toJson(details);
        auditRepository.save(new AuditEntryRecord(
                UUID.randomUUID(), workspaceId, subjectType, subjectId, action, actor, detailsJson));
        outboxRepository.save(new EventOutboxRecord(
                UUID.randomUUID(), workspaceId, subjectType, subjectId, action, detailsJson));
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unserializable audit details", e);
        }
    }
}
