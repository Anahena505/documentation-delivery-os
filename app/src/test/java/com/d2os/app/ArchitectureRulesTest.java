package com.d2os.app;

import com.d2os.testsupport.ArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Runs the module-boundary rules over the full {@code com.d2os} production classpath (T039). The app
 * module is the only place every bounded-context module is on the classpath together, so this is where
 * the whole-graph rules can actually see all the arrows. Plain JUnit — no Spring context, no containers.
 */
class ArchitectureRulesTest {

    @Test
    void moduleBoundariesHold() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.d2os");
        ArchitectureRules.checkAll(productionClasses);
    }
}
