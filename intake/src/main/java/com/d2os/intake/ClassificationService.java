package com.d2os.intake;

import java.util.Map;

/** Classifies a submission into a D2OS case type (T024). */
public interface ClassificationService {
    ClassificationResult classify(Map<String, Object> formData);
}
