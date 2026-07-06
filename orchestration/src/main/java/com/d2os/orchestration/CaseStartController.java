package com.d2os.orchestration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** POST /cases/{id}/start — kick off the Initiation pipeline (T028, contracts/api.yaml). */
@RestController
@RequestMapping("/api/v1/cases")
public class CaseStartController {

    private final CaseStartService caseStartService;

    public CaseStartController(CaseStartService caseStartService) {
        this.caseStartService = caseStartService;
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> start(@PathVariable UUID id) {
        caseStartService.start(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
