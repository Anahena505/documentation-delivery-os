package com.d2os.projection.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The FIRST multi-datasource setup in this codebase (T007). Defines a SECOND {@code DataSource},
 * bound to {@code spring.datasource.projector.*} (see application.yml) — the {@code d2os_projector}
 * role V28 provisions, the sole writer of the five graph tables (research R2, Principle III).
 * Deliberately NOT {@code @Primary}: the app's normal datasource (tenancy's {@code
 * WorkspaceAwareDataSourceConfig}, bound to {@code d2os_app}) stays the sole {@code @Primary}
 * {@code DataSource} bean, so every other module's autowiring (plain {@code DataSource}/{@code
 * JdbcTemplate}/JPA injection with no qualifier) is completely unaffected by this addition.
 *
 * <h2>Why a plain {@code JdbcTemplate}, not a second {@code EntityManagerFactory}</h2>
 *
 * The GraphNode/GraphEdge/ProjectionCheckpoint/ProjectionState/ProjectionGap JPA entities (T007)
 * are mapped once, against the SINGLE auto-configured {@code EntityManagerFactory} Spring Boot
 * already wires to the {@code @Primary} (app/{@code d2os_app}) datasource — that covers every READ
 * path (generation-filtered finder methods), and {@code d2os_app} has SELECT-only on the graph
 * tables per V28, which is exactly the right privilege for reads. Standing up a SECOND,
 * entity-scoped {@code EntityManagerFactory}/{@code JpaTransactionManager} pair just for the WRITE
 * path (Spring's documented but fairly involved "multiple database" recipe: a distinct {@code
 * LocalContainerEntityManagerFactoryBean} with an explicit {@code packagesToScan}/persistence-unit
 * name, a matching {@code JpaTransactionManager}, and care that the default {@code
 * EntityManagerFactory} does NOT also pick up these same five entity classes) is real, ongoing
 * complexity for a write surface that is five tables' worth of upserts/updates. A plain {@link
 * JdbcTemplate} bound to {@link #projectorDataSource} plus a dedicated {@link
 * DataSourceTransactionManager} — used explicitly by name/qualifier wherever a write needs to go
 * through the {@code d2os_projector} role (see {@code com.d2os.projection.GraphWriteRepository}) —
 * is simpler, has no risk of the two EntityManagerFactories fighting over the same entity classes,
 * and is exactly as correct: every write still goes through the {@code d2os_projector} connection,
 * satisfying the sole-writer DB grant. This is a pragmatic choice, not JPA purity; documented here
 * per T007's own instruction to prefer correctness over ORM uniformity.
 *
 * <h2>Workspace RLS binding</h2>
 *
 * graph_node/graph_edge/etc. are RLS-policied (V28) and {@code d2os_projector} does not own them,
 * so it IS subject to RLS like every other non-owner role. Unlike the request-scoped {@code
 * WorkspaceAwareDataSource} (tenancy), the projector runs as a background job sweeping multiple
 * workspaces (the {@code ReconciliationJob} pattern) — so binding happens per-transaction via
 * {@code com.d2os.projection.ProjectorRlsBinder#bindCurrentTransaction}, the same {@code SET LOCAL
 * app.workspace_id} pattern {@code WorkspaceRlsBinder} uses, just issued against THIS module's own
 * {@link JdbcTemplate}/transaction manager instead of the app's. Actually invoking that
 * per-workspace loop is the {@code Projector}/{@code RebuildJob}'s job (T008/T009, a later phase);
 * T007 wires the datasource/binder so that work has something correct to call into.
 */
@Configuration
public class ProjectorDataSourceConfig {

  @Bean
  @ConfigurationProperties("spring.datasource.projector")
  public DataSourceProperties projectorDataSourceProperties() {
    return new DataSourceProperties();
  }

  /** NOT {@code @Primary} — see class javadoc. Bound to the {@code d2os_projector} role (V28). */
  @Bean
  public DataSource projectorDataSource(DataSourceProperties projectorDataSourceProperties) {
    return projectorDataSourceProperties.initializeDataSourceBuilder().build();
  }

  @Bean("projectorJdbcTemplate")
  public JdbcTemplate projectorJdbcTemplate(DataSource projectorDataSource) {
    return new JdbcTemplate(projectorDataSource);
  }

  @Bean("projectorTransactionManager")
  public PlatformTransactionManager projectorTransactionManager(DataSource projectorDataSource) {
    return new DataSourceTransactionManager(projectorDataSource);
  }
}
