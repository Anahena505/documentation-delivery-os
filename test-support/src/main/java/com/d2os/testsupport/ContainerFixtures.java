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
                    // Spring's test framework caches one ApplicationContext per IT class (each class's
                    // @DynamicPropertySource makes its context key unique), and every cached context holds
                    // its Hikari pool (default 10) open for the whole JVM. 17+ suites × 10 connections blew
                    // past Postgres's default max_connections=100 mid-run, cascading "Failed to load
                    // ApplicationContext" into every later suite. 400 gives the full board headroom.
                    .withCommand("postgres", "-c", "max_connections=400")
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
