package com.d2os.replay;

import com.d2os.persona.OperationExecution;
import org.springframework.stereotype.Component;

/**
 * Verifies an OperationExecution snapshot carries everything needed to reproduce its output
 * (T043, FR-006): prompt version, model id + version, inputs, and injected knowledge all present.
 * A row missing any of these is a reproducibility gap even if its output happens to match.
 */
@Component
public class SnapshotCompletenessCheck {

    public boolean isComplete(OperationExecution op) {
        return notBlank(op.getPromptDefinitionVersion())
                && notBlank(op.getModelId())
                && notBlank(op.getModelVersion())
                && notBlank(op.getInputs())
                && op.getInjectedKnowledge() != null;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
