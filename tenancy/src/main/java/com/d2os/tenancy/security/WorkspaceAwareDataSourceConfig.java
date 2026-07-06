package com.d2os.tenancy.security;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Defines the app's runtime {@code DataSource} bean explicitly so it can be wrapped in
 * {@link WorkspaceAwareDataSource}. Defining this bean here means Spring Boot's
 * {@code DataSourceAutoConfiguration} backs off (it only activates when no {@code DataSource}
 * bean already exists).
 *
 * <p>Uses {@link DataSourceProperties#initializeDataSourceBuilder()} rather than binding
 * {@code @ConfigurationProperties} directly onto an already-built pool object — the latter looks
 * like the simpler idiom but silently fails: generic property binding tries
 * {@code HikariDataSource.setUrl(...)}, which doesn't exist (Hikari's setter is
 * {@code setJdbcUrl}), so the URL never actually gets set and Hibernate fails at bootstrap with
 * "Unable to determine Dialect without JDBC metadata." {@code DataSourceProperties} is Spring
 * Boot's own type with the correct field names, and its builder applies the url/jdbcUrl aliasing
 * that generic binding does not — this is the officially documented decorator pattern.
 */
@Configuration
public class WorkspaceAwareDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        DataSource real = dataSourceProperties.initializeDataSourceBuilder().build();
        return new WorkspaceAwareDataSource(real);
    }
}
