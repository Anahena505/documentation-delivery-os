package com.d2os.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Reads/writes {@link DeltaReport} rows (RLS-scoped to the caller's workspace). */
public interface DeltaReportRepository extends JpaRepository<DeltaReport, UUID> {
}
