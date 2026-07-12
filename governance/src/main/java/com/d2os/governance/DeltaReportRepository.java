package com.d2os.governance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads/writes {@link DeltaReport} rows (RLS-scoped to the caller's workspace). */
public interface DeltaReportRepository extends JpaRepository<DeltaReport, UUID> {}
