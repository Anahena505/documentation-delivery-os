package com.d2os.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One KPI measurement (E1.9). */
@Entity
@Table(name = "kpi_sample")
public class KpiSample {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String metric;

    @Column(name = "case_instance_id")
    private UUID caseInstanceId;

    @Column(nullable = false)
    private double value;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at = OffsetDateTime.now();

    protected KpiSample() {}

    public KpiSample(UUID id, UUID workspaceId, String metric, UUID caseInstanceId, double value) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.metric = metric;
        this.caseInstanceId = caseInstanceId;
        this.value = value;
    }

    public String getMetric() { return metric; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public double getValue() { return value; }
    public OffsetDateTime getAt() { return at; }
}
