package com.d2os.persona;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonaInvocationRepository extends JpaRepository<PersonaInvocation, UUID> {
  List<PersonaInvocation> findByCaseInstanceIdOrderBySequenceNoAsc(UUID caseInstanceId);
}
