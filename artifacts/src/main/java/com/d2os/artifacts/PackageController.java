package com.d2os.artifacts;

import com.d2os.artifacts.dto.PackageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Package API — read + verify (T038, contracts/api.yaml — packages tag). */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/package")
public class PackageController {

  private final ExecutionPackageRepository packageRepository;
  private final HandoverRecordRepository handoverRepository;
  private final PackageAssemblyService packageAssemblyService;
  private final ObjectMapper objectMapper;

  public PackageController(
      ExecutionPackageRepository packageRepository,
      HandoverRecordRepository handoverRepository,
      PackageAssemblyService packageAssemblyService,
      ObjectMapper objectMapper) {
    this.packageRepository = packageRepository;
    this.handoverRepository = handoverRepository;
    this.packageAssemblyService = packageAssemblyService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public ResponseEntity<PackageResponse> get(@PathVariable UUID caseId) {
    ExecutionPackage pkg =
        packageRepository
            .findByCaseInstanceId(caseId)
            .orElseThrow(
                () -> new PackageNotDeliveredException("no package delivered for case " + caseId));
    HandoverRecord handover =
        handoverRepository
            .findByExecutionPackageId(pkg.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "package " + pkg.getId() + " missing its handover record"));

    return ResponseEntity.ok(
        PackageResponse.from(
            pkg,
            handover,
            parse(pkg.getManifest()),
            parse(handover.getContentsIndex()),
            parse(handover.getArtifactHashes())));
  }

  @PostMapping("/verify")
  public ResponseEntity<VerifyResult> verify(@PathVariable UUID caseId) {
    ExecutionPackage pkg =
        packageRepository
            .findByCaseInstanceId(caseId)
            .orElseThrow(
                () -> new PackageNotDeliveredException("no package delivered for case " + caseId));
    boolean valid = packageAssemblyService.verify(pkg);
    return ResponseEntity.ok(new VerifyResult(valid));
  }

  private Object parse(String json) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (Exception e) {
      return json;
    }
  }

  public record VerifyResult(boolean manifestHashValid) {}
}
