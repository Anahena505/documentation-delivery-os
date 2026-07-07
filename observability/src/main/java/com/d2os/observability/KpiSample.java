package com.d2os.observability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One KPI measurement (E1.9). Phase 3 (US4): {@code dimensions} carries per-item attribution
 *  ({@code {"key":..., "version":...}}) for the {@code knowledge_influence} metric; {@code {}} otherwise. */
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String dimensions = "{}";

    @Column(name = "at", nullable = false)
    private OffsetDateTime at = OffsetDateTime.now();

    protected KpiSample() {}

    public KpiSample(UUID id, UUID workspaceId, String metric, UUID caseInstanceId, double value) {
        this(id, workspaceId, metric, caseInstanceId, value, "{}");
    }

    public KpiSample(UUID id, UUID workspaceId, String metric, UUID caseInstanceId, double value,
                     String dimensions) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.metric = metric;
        this.caseInstanceId = caseInstanceId;
        this.value = value;
        this.dimensions = dimensions == null ? "{}" : dimensions;
    }

    public String getMetric() { return metric; }
    public UUID getCaseInstanceId() { return caseInstanceId; }
    public double getValue() { return value; }
    public String getDimensions() { return dimensions; }
    public OffsetDateTime getAt() { return at; }
}
