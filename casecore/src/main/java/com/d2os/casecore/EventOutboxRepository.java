package com.d2os.casecore;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventOutboxRepository extends JpaRepository<EventOutboxRecord, UUID> {}
