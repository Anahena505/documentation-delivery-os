package com.d2os.observability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KpiSampleRepository extends JpaRepository<KpiSample, UUID> {
    List<KpiSample> findByMetricOrderByAtAsc(String metric);
}
