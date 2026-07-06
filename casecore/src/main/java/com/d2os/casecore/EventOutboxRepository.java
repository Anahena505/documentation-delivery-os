package com.d2os.casecore;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventOutboxRepository extends JpaRepository<EventOutboxRecord, UUID> {
}
