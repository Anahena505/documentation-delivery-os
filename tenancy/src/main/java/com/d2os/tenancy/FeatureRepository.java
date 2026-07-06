package com.d2os.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FeatureRepository extends JpaRepository<Feature, UUID> {
}
