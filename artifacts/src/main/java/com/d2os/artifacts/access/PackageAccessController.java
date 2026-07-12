package com.d2os.artifacts.access;

import com.d2os.artifacts.ExecutionPackage;
import com.d2os.artifacts.ExecutionPackageRepository;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-scoped package access (T040/T042, contracts/api.yaml, FR-015). {@code X-Roles}
 * (comma-separated) identifies the caller's roles — same pragmatic-input posture as {@code
 * NotificationController}. {@code GET /packages/{packageId}} is a minimal addition beyond the
 * contract's explicit grants-CRUD endpoints: it is the concrete enforcement point demonstrating
 * "reading a delivered package requires a grant" (FR-015) — no package-content-read endpoint
 * existed anywhere in this codebase before this phase to retrofit the check onto.
 */
@RestController
@RequestMapping("/api/v1/packages/{packageId}")
public class PackageAccessController {

  private final PackageAccessService packageAccessService;
  private final ExecutionPackageRepository packageRepository;

  public PackageAccessController(
      PackageAccessService packageAccessService, ExecutionPackageRepository packageRepository) {
    this.packageAccessService = packageAccessService;
    this.packageRepository = packageRepository;
  }

  @GetMapping
  public ResponseEntity<PackageView> read(
      @PathVariable UUID packageId,
      @RequestHeader(value = "X-Roles", defaultValue = "reviewer") String roles) {
    ExecutionPackage pkg =
        packageRepository
            .findById(packageId)
            .orElseThrow(() -> new NoSuchElementException("package " + packageId));
    if (!packageAccessService.checkAccess(packageId, Arrays.asList(roles.split(",")))) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return ResponseEntity.ok(
        new PackageView(pkg.getId(), pkg.getCaseInstanceId(), pkg.getManifestHash()));
  }

  @GetMapping("/grants")
  public List<PackageGrantView> listGrants(@PathVariable UUID packageId) {
    return packageAccessService.listGrants(packageId).stream().map(PackageGrantView::of).toList();
  }

  @PostMapping("/grants")
  public ResponseEntity<PackageGrantView> grant(
      @PathVariable UUID packageId,
      @RequestHeader(value = "X-Actor", defaultValue = "reviewer") String actor,
      @RequestBody GrantRequest request) {
    ExecutionPackage pkg =
        packageRepository
            .findById(packageId)
            .orElseThrow(() -> new NoSuchElementException("package " + packageId));
    PackageAccessGrant grant =
        packageAccessService.grant(pkg.getWorkspaceId(), packageId, request.role(), actor);
    return ResponseEntity.status(HttpStatus.CREATED).body(PackageGrantView.of(grant));
  }

  public record GrantRequest(String role) {}

  public record PackageView(UUID id, UUID caseInstanceId, String manifestHash) {}

  public record PackageGrantView(
      UUID id,
      UUID packageId,
      String role,
      String grantedBy,
      OffsetDateTime createdAt,
      OffsetDateTime revokedAt) {
    static PackageGrantView of(PackageAccessGrant g) {
      return new PackageGrantView(
          g.getId(),
          g.getPackageId(),
          g.getRole(),
          g.getGrantedBy(),
          g.getCreatedAt(),
          g.getRevokedAt());
    }
  }
}
