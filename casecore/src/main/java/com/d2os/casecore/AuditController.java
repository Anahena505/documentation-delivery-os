package com.d2os.casecore;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Tamper-evident audit trail read API for a Case (T060, FR-007, contracts/api.yaml). */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/audit")
public class AuditController {

    private final AuditEntryRepository auditEntryRepository;

    public AuditController(AuditEntryRepository auditEntryRepository) {
        this.auditEntryRepository = auditEntryRepository;
    }

    @GetMapping
    public ResponseEntity<List<AuditEntryRecord>> auditTrail(@PathVariable UUID caseId) {
        return ResponseEntity.ok(
                auditEntryRepository.findBySubjectTypeAndSubjectIdOrderByTxTimeAsc("case_instance", caseId));
    }
}
