package com.d2os.persona;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OperationExecutionRepository extends JpaRepository<OperationExecution, UUID> {

    List<OperationExecution> findByPersonaInvocationIdOrderByAttemptNoAsc(UUID personaInvocationId);

    @Query("""
            select oe from OperationExecution oe
            where oe.personaInvocationId in (
              select pi.id from PersonaInvocation pi where pi.caseInstanceId = :caseInstanceId
            )
            order by oe.createdAt asc
            """)
    List<OperationExecution> findByCaseInstanceId(@Param("caseInstanceId") UUID caseInstanceId);
}
