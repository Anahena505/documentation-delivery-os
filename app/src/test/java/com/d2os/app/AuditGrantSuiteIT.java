package com.d2os.app;

import com.d2os.testsupport.ContainerFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Append-only audit grant test (T063, T6-a, Principle V). The runtime {@code d2os_app} role may
 * INSERT and SELECT the audit stream but must NOT be able to UPDATE or DELETE it — tamper-evidence
 * enforced at the DB grant level, not just in application code. (Also proven ad hoc against a live
 * Postgres earlier in the build; this pins it as a repeatable regression test.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditGrantSuiteIT {

    @Autowired DataSource dataSource;

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
    void appRoleCannotUpdateOrDeleteAuditStream() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Permission is checked at statement execution regardless of matching rows, so an
            // always-false predicate still trips the grant denial.
            assertThrows(SQLException.class, () -> exec(conn, "UPDATE audit_entry SET action = 'tampered' WHERE 1 = 0"),
                    "d2os_app must not be able to UPDATE audit_entry");
            assertThrows(SQLException.class, () -> exec(conn, "DELETE FROM audit_entry WHERE 1 = 0"),
                    "d2os_app must not be able to DELETE from audit_entry");
            assertThrows(SQLException.class, () -> exec(conn, "UPDATE event_outbox SET event_type = 'tampered' WHERE 1 = 0"),
                    "d2os_app must not be able to UPDATE event_outbox");
            // progress_event is append-only too (T048, T6-a): INSERT/SELECT allowed, mutation denied.
            assertThrows(SQLException.class, () -> exec(conn, "UPDATE progress_event SET kind = 'tampered' WHERE 1 = 0"),
                    "d2os_app must not be able to UPDATE progress_event");
            assertThrows(SQLException.class, () -> exec(conn, "DELETE FROM progress_event WHERE 1 = 0"),
                    "d2os_app must not be able to DELETE from progress_event");
        }
    }

    private void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
