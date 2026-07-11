package com.d2os.intake;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemSubmissionRepository extends JpaRepository<ProblemSubmission, UUID> {}
