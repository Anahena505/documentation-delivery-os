package com.d2os.artifacts.spi;

import java.util.List;
import java.util.UUID;

/** Port letting artifacts read validated persona outputs without depending on the persona module. */
public interface PersonaOutputPort {

    List<ValidatedOutput> validatedOutputsForCase(UUID caseId);

    record ValidatedOutput(String personaKey, UUID operationExecutionId, String storageRef, String contentHash) {}
}
