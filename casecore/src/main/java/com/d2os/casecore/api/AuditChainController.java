package com.d2os.casecore.api;

import com.d2os.casecore.audit.AuditChainVerifier;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /audit/chain/verify} (T042, contracts/api.yaml, FR-013). */
@RestController
@RequestMapping("/api/v1/audit/chain")
public class AuditChainController {

    private final AuditChainVerifier auditChainVerifier;

    public AuditChainController(AuditChainVerifier auditChainVerifier) {
        this.auditChainVerifier = auditChainVerifier;
    }

    @PostMapping("/verify")
    public AuditChainVerifier.ChainResult verify() {
        return auditChainVerifier.verifyWorkspace(WorkspaceContext.require());
    }
}
