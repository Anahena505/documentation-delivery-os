package com.d2os.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.d2os.testsupport.ContainerFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 008 US3 (T035, SC-006): proves the once-per-cycle guarantee at the lock level. Two acquisitions of
 * the SAME lock name against the SAME database (standing in for two app instances sharing one
 * Postgres) must not both succeed within the lock window — exactly one holder per cycle, so a
 * scheduled job body runs once across the deployment, never once per node.
 *
 * <p>Compile-verified here; executes in CI where a Docker daemon is available (the sandbox blocks
 * container-image pulls — see the delivery notes). The ShedLock table is provisioned by V30.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShedLockMultiInstanceIT {

  @Autowired LockProvider lockProvider;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    ContainerFixtures.startAll();
    String jdbcUrl = ContainerFixtures.POSTGRES.getJdbcUrl();
    registry.add("spring.flyway.url", () -> jdbcUrl);
    registry.add("spring.flyway.user", ContainerFixtures.POSTGRES::getUsername);
    registry.add("spring.flyway.password", ContainerFixtures.POSTGRES::getPassword);
    registry.add("spring.datasource.url", () -> jdbcUrl);
    registry.add("spring.datasource.username", () -> "d2os_app");
    registry.add("spring.datasource.password", () -> "d2os_app");
    registry.add("d2os.storage.endpoint", () -> ContainerFixtures.MINIO.getS3URL());
    registry.add("d2os.storage.access-key", ContainerFixtures.MINIO::getUserName);
    registry.add("d2os.storage.secret-key", ContainerFixtures.MINIO::getPassword);
  }

  @Test
  void onlyOneInstanceHoldsALockPerWindow() {
    LockConfiguration cfg =
        new LockConfiguration(Instant.now(), "it-shedlock-probe", Duration.ofMinutes(5), Duration.ZERO);

    Optional<SimpleLock> first = lockProvider.lock(cfg);
    assertTrue(first.isPresent(), "the first acquisition of a free lock must succeed");

    // A second instance trying the same lock while the first still holds it must be denied.
    Optional<SimpleLock> second = lockProvider.lock(cfg);
    assertFalse(second.isPresent(), "a second holder must NOT acquire the lock within the window");

    first.get().unlock();

    // After release the lock is acquirable again (next cycle).
    Optional<SimpleLock> third = lockProvider.lock(cfg);
    assertTrue(third.isPresent(), "the lock must be acquirable again after release");
    third.get().unlock();
  }
}
