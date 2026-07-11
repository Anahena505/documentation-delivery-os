package com.d2os.app;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock wiring (feature 008 US3, T025, research R5) — makes each of the 9 {@code @Scheduled} jobs
 * run once per cycle across instances. {@code @EnableSchedulerLock} activates the {@code @SchedulerLock}
 * interception co-located on each job; {@code defaultLockAtMostFor = "PT10M"} is a safety ceiling for
 * any job that does not override {@code lockAtMostFor} (each of the 9 does, sized to its cadence).
 *
 * <p>The {@link LockProvider} is backed by ShedLock's {@link JdbcTemplateLockProvider} over the app's
 * primary {@code DataSource} — the {@code d2os_app} pool (tenancy's {@code WorkspaceAwareDataSource},
 * the sole {@code @Primary} bean). Injecting a plain {@link DataSource} resolves to that primary bean;
 * the projector's second datasource ({@code projectorDataSource}, deliberately not {@code @Primary})
 * is untouched, so the projector's sole-writer role invariant is unaffected. The {@code shedlock}
 * table (V30) carries no RLS, so the workspace-routing wrapper's default connection is correct here.
 *
 * <p>{@code usingDbTime()} makes ShedLock compute lock timing from the Postgres clock rather than each
 * node's wall clock, so lock windows stay consistent even if instance clocks drift.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
