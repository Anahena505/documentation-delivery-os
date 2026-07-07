package com.d2os.persona;

import com.d2os.artifacts.spi.PersonaOutputPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Adapter implementing artifacts' {@link PersonaOutputPort} from persona execution state. */
@Component
public class PersonaOutputAdapter implements PersonaOutputPort {

    private final PersonaInvocationRepository personaInvocationRepository;
    private final OperationExecutionRepository operationExecutionRepository;

    public PersonaOutputAdapter(PersonaInvocationRepository personaInvocationRepository,
                                OperationExecutionRepository operationExecutionRepository) {
        this.personaInvocationRepository = personaInvocationRepository;
        this.operationExecutionRepository = operationExecutionRepository;
    }

    @Override
    public List<ValidatedOutput> validatedOutputsForCase(UUID caseId) {
        List<ValidatedOutput> outputs = new ArrayList<>();
        for (PersonaInvocation invocation : personaInvocationRepository.findByCaseInstanceIdOrderBySequenceNoAsc(caseId)) {
            if (!PersonaInvocation.Status.validated.name().equals(invocation.getStatus())) continue;

            List<OperationExecution> attempts =
                    operationExecutionRepository.findByPersonaInvocationIdOrderByAttemptNoAsc(invocation.getId());
            if (attempts.isEmpty()) continue;

            OperationExecution finalAttempt = attempts.get(attempts.size() - 1);
            // Prefer the real persona key (T018); fall back to the Phase 1 positional label only for
            // legacy rows written before persona_key existed, so old cases still assemble.
            String personaKey = invocation.getPersonaKey() != null
                    ? invocation.getPersonaKey()
                    : "persona-" + invocation.getSequenceNo();
            outputs.add(new ValidatedOutput(
                    personaKey, finalAttempt.getId(), finalAttempt.getOutputRef(), finalAttempt.getOutputHash()));
        }
        return outputs;
    }
}
