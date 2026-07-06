package com.d2os.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DefinitionAssetRepository extends JpaRepository<DefinitionAsset, UUID> {

    Optional<DefinitionAsset> findFirstByTypeAndKeyAndStatusOrderByVersionDesc(
            String type, String key, String status);

    List<DefinitionAsset> findByKeyAndStatus(String key, String status);

    Optional<DefinitionAsset> findByTypeAndKeyAndVersion(String type, String key, String version);
}
