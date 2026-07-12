package com.d2os.artifacts.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageAccessGrantRepository extends JpaRepository<PackageAccessGrant, UUID> {

  List<PackageAccessGrant> findByPackageId(UUID packageId);

  Optional<PackageAccessGrant> findByPackageIdAndRole(UUID packageId, String role);
}
