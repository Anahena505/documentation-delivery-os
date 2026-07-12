package com.d2os.observability;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KpiSampleRepository extends JpaRepository<KpiSample, UUID> {
  List<KpiSample> findByMetricOrderByAtAsc(String metric);
}
