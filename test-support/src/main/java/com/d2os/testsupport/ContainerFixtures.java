package com.d2os.testsupport;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers fixtures (T004). Reused (singleton-per-JVM) across integration/security/
 * replay suites so each test class doesn't pay container-startup cost independently.
 */
public final class ContainerFixtures {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("d2os")
                    .withUsername("d2os")
                    .withPassword("d2os")
                    .withReuse(true);

    public static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
                    .withReuse(true);

    private ContainerFixtures() {}

    public static void startAll() {
        if (!POSTGRES.isRunning()) POSTGRES.start();
        if (!MINIO.isRunning()) MINIO.start();
    }
}
