package com.d2os.casecore;

import com.d2os.tenancy.WorkspaceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace budget rollup API (T037, FR-017, contracts/api.yaml). Reports the current period cap,
 * consumption, and rate limit for the caller's workspace (bound by {@code WorkspaceContextFilter}).
 */
@RestController
public class WorkspaceBudgetController {

    private final WorkspaceBudgetService workspaceBudgetService;

    public WorkspaceBudgetController(WorkspaceBudgetService workspaceBudgetService) {
        this.workspaceBudgetService = workspaceBudgetService;
    }

    @GetMapping("/api/v1/workspace/budget")
    public ResponseEntity<WorkspaceBudgetService.BudgetView> budget() {
        return ResponseEntity.ok(workspaceBudgetService.get(WorkspaceContext.require()));
    }
}
