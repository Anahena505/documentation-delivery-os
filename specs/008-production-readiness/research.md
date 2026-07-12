# Phase 0 Research: Production Readiness & Verification

All decisions favor Spring Boot-native, additive choices that fit the existing stack (Java 21, Gradle,
Postgres, MinIO, Flowable, actuator-on-classpath) and avoid introducing a new bounded context.

---

## R1 — CI/CD system
**Decision**: GitHub Actions (`.github/workflows/ci.yml` + `nightly.yml`).
**Rationale**: The repository is hosted on GitHub (the session's GitHub MCP surface confirms it); no
new SaaS to onboard; Gradle caching and service containers are first-class. `ci.yml` runs on PR + push
to `main`: `./gradlew build`, unit tests, the ArchUnit suite, JaCoCo, openapi-diff, and a
container-capable slice of the integration suites. `nightly.yml` runs the `@Tag("slow")` benchmarks,
the DR rehearsal, and backup verification. Merges are gated on green via branch protection.
**Alternatives considered**: GitLab CI / Jenkins (extra infra, no benefit here); running only ArchUnit
in CI (leaves the integration net unproven — rejected).
**Fail-closed note (spec edge case)**: if the container runtime is unavailable in a CI run, the
integration job **fails**, it does not skip — a skipped suite must never read as green.

## R2 — Making the 43 integration suites executable (the linchpin)
**Decision**: Import `platform("org.testcontainers:testcontainers-bom:1.20.3")` in `build.gradle` and
drop the per-artifact versions so core/jdbc/postgresql/minio all align and pull the matching
`docker-java`.
**Rationale**: Today the direct `1.20.3` declarations resolve **core down to 1.19.8**
(`testcontainers:1.20.3 -> 1.19.8`, shipping `docker-java 3.3.6`) because `database-commons:1.19.8` is
pulled transitively with no BOM to align it. The old docker-java negotiates a Docker API version this
environment's Engine (29.3.1) rejects. The BOM is the canonical fix and is a real build defect
independent of any sandbox. `DOCKER_API_VERSION` pinned in the CI job env is the documented fallback if
a specific daemon still refuses negotiation.
**Alternatives considered**: Forcing docker-java via a resolution strategy (works but masks the root
skew); abandoning Testcontainers for a shared external DB (loses per-test isolation — rejected).

## R3 — Fast unit-test layer
**Decision**: Plain JUnit 5 unit tests (no Spring context, no Docker) for deterministic domain logic,
run in the main `test` task; keep Testcontainers ITs for wiring/RLS/engine integration only.
**Rationale**: Targets the highest-value, least-run code: `CycleDetector` (Kahn + bounded DFS),
`AuditChainCanonicalizer`/`AuditChainSealer`/`AuditChainVerifier` (hash chaining + tamper detection),
`EscalationPolicyResolver`, `TokenBudgetGuard`, `WorkspaceScopeGuard`, DMN hit-policy resolution.
Sub-60s feedback with zero infra. Refactor these classes for testability only where a pure seam already
exists (most already take their inputs as parameters).
**Alternatives considered**: `@DataJdbcTest`/slice tests (still boot a context — slower, keep for
repository logic only); mocking the DB in ITs (rejected — ITs exist precisely to prove real SQL/RLS).

## R4 — Metrics, tracing, structured logging
**Decision**: Micrometer → Prometheus registry (`micrometer-registry-prometheus`), expose
`/actuator/prometheus`; Micrometer Tracing with the OpenTelemetry bridge exporting OTLP; JSON logs via
`logstash-logback-encoder` with `workspace_id`/`case_id`/`trace_id` in MDC.
**Rationale**: All Spring Boot-native and already half-present (actuator on classpath). RED metrics on
HTTP come free from the Spring MVC observation; USE metrics on the 9 jobs are added as `@Timed` +
explicit gauges (lag, last-success timestamp, failure counter). The existing `kpi_sample` table stays
for *business* KPIs; infrastructure metrics move to Micrometer.
**Alternatives considered**: Home-grown metrics extension of `MetricsController` (reinvents Micrometer
— rejected); Spring Cloud Sleuth (superseded by Micrometer Tracing on Boot 3 — rejected).
**Alerting**: the existing threshold constants (`projection.lag-threshold-seconds`,
`projection.gap-alert-threshold`, gate SLA durations, rebuild-equivalence divergence) become
Prometheus alert rules shipped as starter YAML under `deploy/`, plus starter Grafana dashboards.

## R5 — Once-per-cycle scheduled jobs across instances
**Decision**: ShedLock (`net.javacrumbs.shedlock`) with `JdbcTemplateLockProvider` against Postgres;
`@SchedulerLock` on each of the 9 `@Scheduled` methods, `lockAtMostFor` sized per cadence.
**Rationale**: The database is already the shared coordination point; ShedLock adds one small
`shedlock` table (a new Flyway migration) and needs no ZooKeeper/Redis. `lockAtMostFor` bounds a dead
holder so a crashed instance's lock frees on the next cycle (spec edge case: work becomes eligible
again, never lost or double-applied). The projector's sole-writer role invariant is made operationally
true, not just role-true.
**Alternatives considered**: Leader election via Spring Integration `LockRegistry` (heavier, same DB
lock underneath); quartz clustering (Flowable already owns the async executor — avoid a second
scheduler cluster). Rejected.
**Nuance**: `ProgressHeartbeat` is per-Running-case, not global — it is scoped by case so it is
naturally partitioned; it takes a per-case lock, not a single global lock, so heartbeats for different
cases still run concurrently.

## R6 — Authentication + RBAC
**Decision**: Spring Security OAuth2 **Resource Server** validating OIDC JWTs (RS256/ES256) against the
IdP's JWKS; map `sub` → authenticated user and a `roles`/`groups` claim → authorities; retain the
`workspace_id` claim for tenant scoping. Replace `anyRequest().permitAll()` with authenticated-by-default
plus method-level `@PreAuthorize` on role-restricted operations. Record `actor_user_id` + `actor_role`
on every trust-sensitive decision.
**Rationale**: Removes the app-held HS256 secret (nothing to rotate in-app), gives real per-user
identity for the audit trail (Principle V "who decided"), and enforces the role gates
(`catalog-owner`, `architecture-board`, governance approvers) that today resolve role *names* with no
principal behind them. Provider-agnostic (Keycloak as the reference IdP); the existing
`WorkspaceContextFilter` keeps binding RLS but now from a verified OIDC token.
**Alternatives considered**: Keep HS256, add a user claim (still an app-held secret, no IdP, weak key
rotation — rejected); opaque-token introspection (extra round-trip per request — rejected, JWT+JWKS is
stateless). **Migration**: the `X-Workspace-Id` header fallback (already default-off, test-only) is
retired; existing ITs move to minting test OIDC tokens via a test JWKS, mirroring the current
`JwtWorkspaceAuthIT` pattern.

## R7 — Deployment packaging + secrets
**Decision**: Build the image with Spring Boot's `bootBuildImage` (Cloud Native Buildpacks) — a
layered, reproducible OCI image with no hand-maintained Dockerfile; ship a Helm chart under
`deploy/helm/` mapping the `.env.example` surface to a `ConfigMap` (non-secrets) and `Secret`
(credentials), wiring `/actuator/health/liveness` and `/readiness` to k8s probes. Secrets are sourced
into the `Secret` by the platform's external secrets mechanism (Vault / cloud KMS via External Secrets
Operator) — the app stays 12-factor and reads env, so no app code depends on a specific secrets vendor.
**Rationale**: Keeps the app deployment-agnostic (constitution: provider-agnostic, additive), completes
the missing deployable unit, and reuses the actuator health split the `WorkspaceContextFilter` already
exempts from auth. Image is versioned with the Gradle `version` (0.1.0 → CI tag).
**Alternatives considered**: Hand-written Dockerfile (more control, more maintenance — offered as the
alternative in `deploy/` if buildpacks prove limiting); baking secrets into the image or ConfigMap
(rejected outright — violates default-deny secret hygiene).

## R8 — API contract conformance
**Decision**: Add `springdoc-openapi` to emit the live OpenAPI at runtime; in CI run `openapi-diff`
comparing the generated spec against the 7 checked-in `specs/**/contracts/*.yaml`, failing on breaking
drift.
**Rationale**: Makes the constitution's contract-first rule a *checked* guarantee. springdoc reads the
existing annotations; openapi-diff distinguishes breaking vs non-breaking changes so additive evolution
isn't falsely failed.
**Alternatives considered**: Spring Cloud Contract (consumer-driven, heavier, and the contracts here
are provider-authored OpenAPI — rejected); manual review (the status quo that permits silent drift —
rejected).

## R9 — Template → Artifact content rendering (US6)
**Decision**: Render `Artifact` content from the immutable `TemplateDefinition` body via a deterministic
template step, stamping `source_template_id` + `template_version` onto the produced artifact revision;
wire the association that `ArtifactService`/`CatalogSeedLoader` currently defer (persona key stands in
today). Extend `EquivalenceVerifier`/replay coverage to the newly-flowing `TEMPLATE`/`DEFINITION_VERSION`
node types it presently skips.
**Rationale**: Completes the product's actual output while preserving Principle I (render from a pinned,
immutable version) and Principle II (the rendered content remains reproducible from recorded inputs).
Deterministic rendering keeps replay byte-identical.
**Alternatives considered**: AI-generated artifact bodies with no template (breaks reproducibility /
the D-ladder — rejected); a heavyweight document-composition engine (premature — the template body +
slot substitution the studio module already models is sufficient for v1).
**Open choice deferred to tasks**: exact template syntax (reuse the studio prompt-slot `{{...}}`
convention vs. Thymeleaf text templates) — both deterministic; pick during implementation to match the
existing `PromptEditorModel` slot model.

## R10 — Build-quality gates
**Decision**: JaCoCo (coverage report + a modest floor on domain modules, not the `app` slice),
Spotless with `google-java-format`, SpotBugs for null/resource-leak classes. All wired into `ci.yml`.
**Rationale**: Cheap, standard, makes R3's coverage visible and prevents style drift across 15 modules
and multiple contributors/agents. Floors are set per-module to avoid a single global number that either
blocks trivially or means nothing.
**Alternatives considered**: Checkstyle/PMD (overlaps Spotless+SpotBugs, more config churn — deferred);
SonarQube (needs a server — deferred, openapi-diff + JaCoCo + SpotBugs cover the near-term need).

---

### Resolved unknowns
No `NEEDS CLARIFICATION` markers remain. IdP vendor and orchestrator are intentionally reference-only
(Keycloak / Kubernetes) — the design depends on OIDC/JWKS and container-probe *standards*, not a
specific vendor, per the constitution's provider-agnostic constraint.
