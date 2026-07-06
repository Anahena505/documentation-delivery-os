package com.d2os.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProblemSubmissionRepository extends JpaRepository<ProblemSubmission, UUID> {
}
