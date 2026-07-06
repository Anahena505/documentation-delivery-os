package com.d2os.persona;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PersonaInvocationRepository extends JpaRepository<PersonaInvocation, UUID> {
    List<PersonaInvocation> findByCaseInstanceIdOrderBySequenceNoAsc(UUID caseInstanceId);
}
