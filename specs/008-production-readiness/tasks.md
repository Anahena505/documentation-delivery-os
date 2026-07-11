---
description: "Task list for feature 008 — production readiness & verification"
---

# Tasks: Production Readiness & Verification Enhancement

**Input**: Design documents from `/specs/008-production-readiness/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test tasks ARE included — for this feature, executable verification is the deliverable
(US1) and several stories are only provable by a behavior test (US3/US5/US6).

## Conventions for the implementer (read first)

These make every task below self-contained so it can be done without extra investigation:

- **Build/verify command**: use the system Gradle at `/opt/gradle/bin/gradle` (the `./gradlew` wrapper
  cannot download its distribution here). After a code task, verify with
  `/opt/gradle/bin/gradle :<module>:compileJava :<module>:compileTestJava -q`. After an ArchUnit-related
  task, run `/opt/gradle/bin/gradle :test-support:test --tests ArchitectureRulesTest`.
- **Flyway migrations share ONE global version namespace.** The current highest is **V29**. New
  migrations are **V30, V31, V32…** regardless of which module's `src/main/resources/db/migration/`
  directory they live in. Always pick the next unused number.
- **Dependency versions**: add new dependencies in the relevant module's `build.gradle`. Spring Boot
  BOM (already applied) manages Spring/Micrometer versions — omit the version for BOM-managed
  artifacts; pin a version only for non-BOM libraries (noted per task).
- **Do not move any class between modules.** All work is additive within existing module boundaries.
- **Commit after each task** with a message naming the task ID.

---

## Phase 1: Setup (Shared Build Infrastructure)

**Purpose**: fix the build so tests can run and quality is measured. No behavior change.

- [X] T001 In `build.gradle` (root, `subprojects { dependencies { … } }` block, lines ~44-49), replace
  the three per-artifact Testcontainers declarations (`org.testcontainers:junit-jupiter:1.20.3`,
  `:postgresql:1.20.3`, `:minio:1.20.3`) with a BOM import
  `testImplementation platform('org.testcontainers:testcontainers-bom:1.20.3')` plus versionless
  `testImplementation 'org.testcontainers:junit-jupiter'`, `':postgresql'`, `':minio'`. Do the same in
  `test-support/build.gradle` (lines 4-6), using `api platform(...)` + versionless `api` entries.
  Acceptance: `/opt/gradle/bin/gradle :app:dependencies --configuration testRuntimeClasspath` shows
  `org.testcontainers:testcontainers:1.20.3` (NOT `-> 1.19.8`).
- [X] T002 [P] In `build.gradle` root `subprojects` block, apply `id 'jacoco'` and add a
  `jacocoTestReport` task that depends on `test`. Acceptance: `/opt/gradle/bin/gradle :casecore:jacocoTestReport`
  produces `casecore/build/reports/jacoco/test/html/index.html`.
- [X] T003 [P] In `build.gradle` root, apply the Spotless plugin
  (`id 'com.diffplug.spotless' version '6.25.0'` in the root `plugins { … apply false }`, then
  `apply plugin: 'com.diffplug.spotless'` in `subprojects`) configured with
  `java { googleJavaFormat() }`. Acceptance: `/opt/gradle/bin/gradle spotlessCheck` runs (may report
  violations — that is fine for this task).
- [X] T004 [P] In `build.gradle` root, apply SpotBugs (`id 'com.github.spotbugs' version '6.0.26'`) in
  `subprojects` with `spotbugs { effort = 'default'; reportLevel = 'high' }`. Acceptance:
  `/opt/gradle/bin/gradle :casecore:spotbugsMain` runs.
- [X] T005 Run `/opt/gradle/bin/gradle compileJava compileTestJava -q --rerun` and confirm all 15
  modules still compile after the build changes above.

**Checkpoint**: build resolves aligned Testcontainers and has coverage/format/bug plugins wired.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: config every later story reads. **Complete before Phase 3+.**

- [X] T006 In `app/src/main/resources/application.yml`, add a top-level `management:` block exposing the
  operational endpoints both US2 and US4 need:
  `management.endpoints.web.exposure.include: health,info,prometheus,metrics` and
  `management.endpoint.health.probes.enabled: true` and
  `management.health.livenessState.enabled: true` / `readinessState.enabled: true`. Do NOT remove any
  existing config. Acceptance: file parses (`/opt/gradle/bin/gradle :app:compileJava` unaffected;
  YAML indentation matches the 2-space style already in the file).

**Checkpoint**: management surface configured; user stories can begin.

---

## Phase 3: User Story 1 — Every change is automatically proven safe (Priority: P1) 🎯 MVP

**Goal**: CI runs build + the (now-runnable) integration suites + ArchUnit + coverage on every change,
fails closed; a fast unit layer covers core logic in < 60 s.

**Independent Test**: push a branch that breaks an ArchUnit rule → CI red; push a correct branch → CI
green; the unit tests run without Docker.

### Tests (fast unit layer — these ARE the deliverable)

- [X] T007 [P] [US1] Create `projection/src/test/java/com/d2os/projection/cycle/CycleDetectorTest.java`
  — plain JUnit 5, NO Spring, NO Testcontainers. Test the pure graph logic of
  `projection/src/main/java/com/d2os/projection/cycle/CycleDetector.java`: (a) a 3-node cycle → every
  member node reported; (b) an acyclic graph → zero cycles; (c) a self-loop. Instantiate only the pure
  method(s); if a method needs JDBC, extract the pure Kahn/DFS portion into a package-private static
  helper first and test that. Acceptance: `/opt/gradle/bin/gradle :projection:test --tests CycleDetectorTest`
  passes in < 5 s.
- [X] T008 [P] [US1] Create
  `casecore/src/test/java/com/d2os/casecore/audit/AuditChainCanonicalizerTest.java` — pure JUnit.
  Verify `AuditChainCanonicalizer` produces identical canonical bytes for equal inputs and different
  bytes when any field changes (tamper sensitivity). Acceptance: passes without a DB.
- [X] T009 [P] [US1] Create
  `governance/src/test/java/com/d2os/governance/escalation/EscalationPolicyResolverTest.java` — pure
  JUnit. Verify step-duration resolution: policy value wins when present; configured default
  (`P3D`) used when the policy omits a step. Acceptance: passes without a DB.
- [X] T010 [P] [US1] Create `persona/src/test/java/com/d2os/persona/TokenBudgetGuardTest.java` — pure
  JUnit. Verify the budget cap admits usage at/under the cap and rejects over the cap (boundary
  values). Acceptance: passes without a DB.
- [X] T011 [P] [US1] Create
  `persona/src/test/java/com/d2os/persona/gateway/WorkspaceScopeGuardTest.java` — pure JUnit. Verify a
  knowledge item from a foreign workspace is rejected (`KnowledgeScopeViolationException`) and a
  same-workspace item passes. Acceptance: passes without a DB.

### Implementation (CI)

- [X] T012 [US1] Create `.github/workflows/ci.yml`. Triggers: `pull_request` and `push` to `main`.
  One job on `ubuntu-latest`, JDK 21 (`actions/setup-java@v4`, temurin), Gradle cache
  (`gradle/actions/setup-gradle@v4`). Steps: `./gradlew build` (runs unit + integration + ArchUnit),
  then `./gradlew jacocoTestReport`, then upload `**/build/reports/**` via `actions/upload-artifact@v4`.
  The runner provides Docker, so Testcontainers ITs execute. Add a comment noting: if Docker is
  unavailable the integration step MUST fail, not skip (fail-closed). Acceptance: YAML is valid
  (`python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"`).
- [X] T013 [US1] Create `.github/workflows/nightly.yml`. Trigger: `schedule` (cron `0 3 * * *`) +
  `workflow_dispatch`. Job runs `./gradlew test -PincludeSlow=true` (or the project's `slowTest` task
  if present) so `@Tag("slow")` benchmark ITs run. Acceptance: YAML valid.
- [X] T014 [US1] Run the whole fast unit layer to confirm the pyramid base is green:
  `/opt/gradle/bin/gradle :projection:test :casecore:test :governance:test :persona:test --tests '*Test'`
  and confirm the five new `*Test` classes pass. (Integration suites still require a Docker runtime and
  run in CI, not here.)

**Checkpoint**: change verification exists; core logic has fast tests. US1 shippable.

---

## Phase 4: User Story 2 — Operators can see and be alerted (Priority: P1)

**Goal**: Micrometer→Prometheus metrics for HTTP + the 9 jobs + domain thresholds; JSON logs with
correlation IDs; alert rules shipped.

**Independent Test**: `GET /actuator/prometheus` returns the meter families; stalling the projector
raises the lag gauge and fires the shipped alert.

### Implementation

- [X] T015 [P] [US2] In `app/build.gradle` and `observability/build.gradle`, add
  `implementation 'io.micrometer:micrometer-registry-prometheus'` (BOM-managed, no version).
  Acceptance: `/opt/gradle/bin/gradle :app:dependencies` lists micrometer-registry-prometheus.
- [X] T016 [P] [US2] In `app/build.gradle`, add
  `implementation 'io.micrometer:micrometer-tracing-bridge-otel'` and
  `implementation 'io.opentelemetry:opentelemetry-exporter-otlp'` (BOM-managed). In
  `application.yml` add `management.tracing.sampling.probability: 1.0` and an OTLP endpoint property
  `management.otlp.tracing.endpoint: ${D2OS_OTLP_ENDPOINT:}` (empty default = no-op). Acceptance:
  compiles; app still boots with the env var unset.
- [X] T017 [P] [US2] Add JSON logging: add `implementation 'net.logstash.logback:logstash-logback-encoder:8.0'`
  to `app/build.gradle`, then create `app/src/main/resources/logback-spring.xml` using
  `LogstashEncoder` and an MDC pattern including `workspace_id`, `case_id`, `trace_id`. Acceptance:
  running `:app:bootRun` (or a `@SpringBootTest`) emits JSON lines.
- [X] T018 [US2] Create
  `observability/src/main/java/com/d2os/observability/JobMetrics.java` — a small Spring `@Component`
  holding a `MeterRegistry` that exposes helper methods to record per-job execution/duration/failure
  (`d2os.job.executions{job}`, `d2os.job.duration{job}`, `d2os.job.failures{job}`). Follow the
  constructor-injection style used elsewhere in the module (see `KpiEmitter.java`). Acceptance:
  compiles; unit-instantiable with a `SimpleMeterRegistry`.
- [X] T019 [US2] Wire `JobMetrics` into each of the 9 `@Scheduled` methods to time the run and count
  failures. Files: `casecore/.../progress/ProgressHeartbeat.java`,
  `casecore/.../audit/AuditChainSealer.java`, `casecore/.../audit/AuditChainVerifier.java`,
  `orchestration/.../CaseDeliveredKnowledgeTrigger.java`, `orchestration/.../ReconciliationJob.java`,
  `projection/.../RebuildJob.java`, `projection/.../cycle/CycleDetector.java`,
  `projection/.../Projector.java`, `tenancy/.../RetentionVerificationJob.java`. Add `observability` as
  a dependency in each module's `build.gradle` only if not already present (check first; avoid a
  dependency cycle — if a cycle would result, inject a `MeterRegistry` directly instead of `JobMetrics`).
  Acceptance: each module compiles; a meter appears after one scheduled run.
- [X] T020 [P] [US2] Register domain-threshold gauges (Micrometer `Gauge`) for
  `d2os.projection.lag.seconds`, `d2os.projection.gap.open`, `d2os.gate.sla.breached`,
  `d2os.rebuild.equivalence.divergent`. Put the projection ones in a new
  `projection/src/main/java/com/d2os/projection/ProjectionMetrics.java` reading the same values the
  existing lag/gap logic computes; the gate one in a governance metrics component. Acceptance:
  compiles; gauges visible at `/actuator/prometheus`.
- [X] T021 [P] [US2] Create `deploy/prometheus/alert-rules.yml` with Prometheus alerting rules firing
  on: projection lag > 30s sustained; `d2os.projection.gap.open` > 10; `d2os.gate.sla.breached` > 0;
  `d2os.rebuild.equivalence.divergent` > 0; any increase in `d2os.job.failures`. Acceptance: valid YAML.
- [X] T022 [P] [US2] Create `deploy/grafana/d2os-overview.json` — a starter dashboard with panels for
  HTTP RED (from `http.server.requests`) and the four domain gauges. Acceptance: valid JSON.

**Checkpoint**: metrics, tracing, logs, and alerts exist. US2 shippable.

---

## Phase 5: User Story 3 — Runs safely on more than one instance (Priority: P2)

**Goal**: each of the 9 scheduled jobs runs once per cycle across instances via ShedLock.

**Independent Test**: two instances against one DB → each cycle's work happens once.

### Implementation

- [X] T023 [US3] In `app/build.gradle` add
  `implementation 'net.javacrumbs.shedlock:shedlock-spring:5.16.0'` and
  `implementation 'net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0'`. Acceptance:
  compiles.
- [X] T024 [US3] Create the ShedLock table migration as the next unused Flyway version (**V30**) at
  `tenancy/src/main/resources/db/migration/V30__shedlock.sql` with columns `name text PRIMARY KEY`,
  `lock_until timestamptz NOT NULL`, `locked_at timestamptz NOT NULL`, `locked_by text NOT NULL`
  (per data-model.md §1). Grant the app role read/write. Acceptance: migration file present, SQL valid.
- [X] T025 [US3] Create `app/src/main/java/com/d2os/app/ShedLockConfig.java` annotated
  `@Configuration @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")` with a `LockProvider` bean
  built from a `JdbcTemplateLockProvider` over the app `DataSource`. Acceptance: app context loads.
- [X] T026 [P] [US3] Add `@SchedulerLock(name = "projector-sweep", lockAtMostFor = "PT2M")` to the
  `@Scheduled` method in `projection/.../Projector.java`. Acceptance: compiles.
- [X] T027 [P] [US3] Add `@SchedulerLock(name = "graph-rebuild", lockAtMostFor = "PT15M")` to
  `projection/.../RebuildJob.java`'s scheduled method. Acceptance: compiles.
- [X] T028 [P] [US3] Add `@SchedulerLock(name = "cycle-sweep", lockAtMostFor = "PT10M")` to
  `projection/.../cycle/CycleDetector.java`'s scheduled sweep method. Acceptance: compiles.
- [X] T029 [P] [US3] Add `@SchedulerLock(name = "reconciliation", lockAtMostFor = "PT2M")` to
  `orchestration/.../ReconciliationJob.java`. Acceptance: compiles.
- [X] T030 [P] [US3] Add `@SchedulerLock(name = "delivered-knowledge-trigger", lockAtMostFor = "PT2M")`
  to `orchestration/.../CaseDeliveredKnowledgeTrigger.java`. Acceptance: compiles.
- [X] T031 [P] [US3] Add `@SchedulerLock(name = "audit-chain-sealer", lockAtMostFor = "PT5M")` to
  `casecore/.../audit/AuditChainSealer.java`. Acceptance: compiles.
- [X] T032 [P] [US3] Add `@SchedulerLock(name = "audit-chain-verifier", lockAtMostFor = "PT5M")` to
  `casecore/.../audit/AuditChainVerifier.java`. Acceptance: compiles.
- [X] T033 [P] [US3] Add `@SchedulerLock(name = "retention-verification", lockAtMostFor = "PT30M")` to
  `tenancy/.../RetentionVerificationJob.java`. Acceptance: compiles.
- [X] T034 [US3] Give `ProgressHeartbeat` a **per-case** lock so different cases still beat
  concurrently: in `casecore/.../progress/ProgressHeartbeat.java` use
  `@SchedulerLock(name = "progress-heartbeat")` if the beat is a single global sweep, OR if it already
  iterates cases, leave it unlocked and add a code comment explaining it is naturally partitioned (do
  NOT put a single global lock on a per-case beat — that would serialize all heartbeats). Acceptance:
  compiles; comment or lock present as appropriate.
- [X] T035 [US3] Create `app/src/test/java/com/d2os/app/ShedLockMultiInstanceIT.java` — a Testcontainers
  IT that simulates two schedulers against one Postgres (e.g. acquire the same lock name from two
  `LockProvider` instances) and asserts only one holds the lock per window. Follow an existing IT's
  `@DynamicPropertySource` container setup. Acceptance: compiles (`:app:compileTestJava`); runs in CI.

**Checkpoint**: scheduled work is single-run across instances. US3 shippable.

---

## Phase 6: User Story 4 — Deployable versioned unit (Priority: P2)

**Goal**: versioned image, health probes, Helm chart, managed secrets, fail-loud on missing secret.

**Independent Test**: build image, `helm install`, pod goes Ready only after readiness=UP; removing a
required secret fails startup.

### Implementation

- [X] T036 [US4] In `app/build.gradle`, configure the Spring Boot `bootBuildImage` task with
  `imageName = "d2os/app:${project.version}"`. Acceptance: `/opt/gradle/bin/gradle :app:bootBuildImage --dry-run`
  resolves the task (a full image build needs a Docker daemon and runs in CI).
- [X] T037 [P] [US4] In `app/src/main/resources/application.yml`, add graceful shutdown
  (`server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`). Acceptance:
  compiles/boots.
- [X] T038 [P] [US4] Create `deploy/helm/Chart.yaml` (name `d2os`, version from project). Acceptance:
  valid YAML.
- [X] T039 [P] [US4] Create `deploy/helm/values.yaml` mapping the `.env.example` surface: image repo/tag,
  a `config:` map for non-secrets (DB URL/user, storage endpoint/bucket, AI provider/model, JWT
  issuer), and a `secretRefs:` list for credentials (DB passwords, storage secret key, OIDC/JWKS if
  any). Acceptance: valid YAML.
- [X] T040 [US4] Create `deploy/helm/templates/deployment.yaml` with a Deployment that: sets env from a
  ConfigMap and a Secret; wires `livenessProbe` → `GET /actuator/health/liveness` and `readinessProbe`
  → `GET /actuator/health/readiness` on port 8080. Acceptance: `helm template deploy/helm` renders (or
  YAML is structurally valid if helm is unavailable).
- [X] T041 [P] [US4] Create `deploy/helm/templates/configmap.yaml` and `deploy/helm/templates/secret.yaml`
  from the values in T039 (Secret keyed for external-secrets injection; no literal secret values).
  Acceptance: valid YAML; no plaintext credentials committed.
- [X] T042 [US4] Confirm fail-loud on a missing required secret: verify (and add a short comment in
  `application.yml` next to `D2OS_DB_APP_PASSWORD` / `D2OS_JWT_SECRET` if not already clear) that these
  have NO default, so startup fails when unset — matching the existing datasource convention.
  Acceptance: with the env var unset, a boot attempt fails with a clear message (this is already the
  design; task is to confirm + document, not weaken it).

**Checkpoint**: the app has a deployable unit with probes and managed secrets. US4 shippable.

---

## Phase 7: User Story 5 — Authenticated per-user identity + RBAC (Priority: P2)

**Goal**: OIDC resource-server auth (RS256/JWKS) adding user identity + roles; enforced RBAC; actor
recorded (hash-chained) on trust-sensitive decisions.

**Independent Test**: role-restricted call without the role → 403; with the role → 200 and the audit
record names the user + role.

### Implementation

- [X] T043 [US5] In `tenancy/build.gradle` add
  `implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'`. Acceptance:
  compiles.
- [X] T044 [US5] Rewrite `tenancy/src/main/java/com/d2os/tenancy/security/SecurityConfig.java`: replace
  `anyRequest().permitAll()` with `anyRequest().authenticated()` EXCEPT `/actuator/health/**` and
  `/actuator/prometheus` permitted; enable `oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`. Keep CSRF
  disabled for the stateless API. Acceptance: `:tenancy:compileJava` passes; existing security intent
  preserved for health/metrics.
- [X] T045 [US5] In `application.yml` add
  `spring.security.oauth2.resourceserver.jwt.issuer-uri: ${D2OS_OIDC_ISSUER_URI:}` and
  `jwk-set-uri: ${D2OS_OIDC_JWKS_URI:}`. Acceptance: parses.
- [X] T046 [US5] Create `tenancy/src/main/java/com/d2os/tenancy/security/RolesJwtConverter.java` — a
  `Converter<Jwt, AbstractAuthenticationToken>` mapping the `roles` (or `groups`) claim to
  `SimpleGrantedAuthority` (prefix `ROLE_`). Register it in `SecurityConfig` (T044). Acceptance:
  compiles; a token with `roles:["catalog-owner"]` yields authority `ROLE_catalog-owner`.
- [X] T047 [US5] Create `tenancy/src/main/java/com/d2os/tenancy/security/AuthenticatedPrincipal.java` —
  a small helper reading the current `SecurityContext` to expose `userId()` (JWT `sub`) and
  `roles()`. Acceptance: compiles; unit-testable with a mocked `SecurityContext`.
- [ ] T048 [US5] Update `tenancy/src/main/java/com/d2os/tenancy/security/WorkspaceContextFilter.java`:
  resolve `workspace_id` from the validated OIDC JWT (via `JwtAuthenticationToken` claims) instead of
  the local HS256 `JwtService`; REMOVE the `X-Workspace-Id` header-fallback branch and the
  `allowHeaderWorkspaceFallback` property usage. Keep binding `WorkspaceContext` exactly as today.
  Acceptance: compiles; health paths still bypass (unchanged `shouldNotFilter`).
- [X] T049 [US5] Create the audit-actor columns migration as the next unused Flyway version (**V31**) at
  `casecore/src/main/resources/db/migration/V31__audit_actor.sql`: add nullable `actor_user_id text`
  and `actor_role text` to the audit/decision tables that record gate approvals, rejections, reopens,
  and package grants (per data-model.md §2). Nullable so pre-existing rows remain valid. Acceptance:
  SQL valid.
- [ ] T050 [US5] Include the actor fields in the hash chain: in
  `casecore/src/main/java/com/d2os/casecore/audit/AuditChainCanonicalizer.java` add
  `actor_user_id` and `actor_role` to the canonical form so altering "who decided" breaks the seal.
  Update `AuditChainCanonicalizerTest` (T008) to assert actor sensitivity. Acceptance:
  `:casecore:test --tests AuditChainCanonicalizerTest` passes.
- [ ] T051 [US5] Populate the actor fields when writing decisions: in the services that record gate
  decisions/reopens/grants (governance + casecore writers), read `AuthenticatedPrincipal.userId()` and
  the acting role and persist them; validate the acting role is one the principal holds (reject
  otherwise). Acceptance: compiles; a decision row carries actor values.
- [ ] T052 [P] [US5] Add `@PreAuthorize("hasRole('...')")` to the role-restricted controller methods
  named in `contracts/auth-and-rbac.yaml`: gate decision (governance approver), catalog publish
  (`catalog-owner`), knowledge promotion (promotion approver). Enable method security with
  `@EnableMethodSecurity` in `SecurityConfig`. Acceptance: compiles.
- [ ] T053 [US5] Update existing ITs to authenticate via a test OIDC token: add a test JWKS + a helper
  that mints RS256 tokens with `sub`, `workspace_id`, and `roles` claims (mirror the existing
  `JwtWorkspaceAuthIT` approach but for the resource-server path); point the resource server at the
  test JWKS via `@DynamicPropertySource`. Replace `X-Workspace-Id` header usage in test support.
  Acceptance: `:app:compileTestJava` passes.
- [ ] T054 [US5] Create `app/src/test/java/com/d2os/app/RbacAndActorIT.java`: (a) role-restricted call
  without the role → 403; (b) with the role → 200 and the persisted audit record has `actor_user_id`
  + `actor_role`; (c) tamper the actor and confirm `AuditChainVerifier` reports a break. Acceptance:
  compiles; runs in CI.

**Checkpoint**: per-user identity + enforced RBAC + actor-stamped audit. US5 shippable.

---

## Phase 8: User Story 6 — Real artifact content with provenance (Priority: P3)

**Goal**: render artifact content from immutable template definitions, stamping provenance; extend
projection/replay coverage.

**Independent Test**: a completed case produces template-derived artifacts carrying
`source_template_id` + `template_version`; replay is byte-identical.

### Implementation

- [ ] T055 [US6] Create the provenance-columns migration as the next unused Flyway version (**V32**) at
  `artifacts/src/main/resources/db/migration/V32__artifact_provenance.sql`: add nullable
  `source_template_id uuid` and `template_version text` to the artifact-revision table (per
  data-model.md §3). Acceptance: SQL valid.
- [ ] T056 [US6] In `artifacts/src/main/java/com/d2os/artifacts/ArtifactService.java`, replace the
  deferred template association (the note near line 166 where the persona key "stands in") with real
  rendering: look up the pinned `TemplateDefinition` version from the case snapshot, render its body
  deterministically (reuse the studio slot convention `{{slot}}` from
  `studio/.../editor/PromptEditorModel.java`, or Thymeleaf text — pick the deterministic one), and set
  `source_template_id` + `template_version` on the produced revision. Do NOT introduce any AI call in
  the rendering path (must stay reproducible). Acceptance: compiles; a produced revision has non-null
  provenance.
- [ ] T057 [US6] In `catalog/src/main/java/com/d2os/catalog/CatalogSeedLoader.java`, replace the
  placeholder operation/template content (notes near lines 147, 634, 700, 740) with real template
  bodies for the seeded operations so rendered artifacts are meaningful. Acceptance: compiles; seed
  loads.
- [ ] T058 [US6] Extend `projection/src/main/java/com/d2os/projection/EquivalenceVerifier.java` (and the
  `Projector`/`RebuildJob` scans it mirrors) to cover the `TEMPLATE`/`DEFINITION_VERSION` nodes and the
  `PRODUCED_FROM` edge now that provenance flows — following the same full-rescan wiring pattern the
  file already uses for `trace_link`/`dependency`. Acceptance: `:projection:compileJava` passes.
- [ ] T059 [US6] Create `app/src/test/java/com/d2os/app/TemplateProvenanceIT.java`: run a case to
  Delivered; assert each artifact's content derives from its template (not placeholder) and the
  revision carries provenance; assert replay is byte-identical. Acceptance: compiles; runs in CI.

**Checkpoint**: deliverables carry real content + provenance. US6 shippable.

---

## Phase 9: User Story 7 — Contracts + performance/DR stay honest (Priority: P3)

**Goal**: contract-conformance in CI; scheduled benchmark + DR-rehearsal + backup verification.

**Independent Test**: a breaking controller change fails openapi-diff; nightly records benchmark/DR
results; DR rehearsal restores the real schema and passes integrity checks.

### Implementation

- [X] T060 [US7] In `app/build.gradle` add
  `implementation 'org.springdoc:springdoc-openapi-starter-webmvc-api:2.6.0'`. Acceptance: compiles;
  `/v3/api-docs` served when running.
- [X] T061 [US7] Add an openapi-diff step to `.github/workflows/ci.yml` (from T012): boot the app (or
  use a generated static `/v3/api-docs`), then run an openapi-diff action/CLI comparing the live spec
  to the checked-in `specs/**/contracts/*.yaml`; fail the job on breaking drift. Acceptance: YAML valid;
  step present.
- [X] T062 [P] [US7] Ensure the two benchmark ITs are `@Tag("slow")` and included by `nightly.yml`
  (T013): check `app/src/test/java/com/d2os/app/TraceabilityBenchmarkIT.java` and the
  `ResolutionBenchmarkIT` carry the tag; add if missing. Acceptance: `:app:compileTestJava` passes.
- [X] T063 [P] [US7] Create `ops/dr-rehearsal.sh` — a script that stands up the real Flyway schema on a
  scratch Postgres, restores from a base backup + WAL per `ops/dr-drill.md`, then runs
  `AuditChainVerifier` and a smoke case check against the restored instance (per FR-017). Acceptance:
  script is syntactically valid (`bash -n ops/dr-rehearsal.sh`).
- [X] T064 [P] [US7] Create `ops/backup-verification.md` describing (and, where scriptable in
  `ops/backup-verify.sh`) a scheduled job that restores the latest base backup into a scratch instance
  and runs `AuditChainVerifier` automatically. Acceptance: doc present; script (if added) passes
  `bash -n`.

**Checkpoint**: contracts and targets are continuously checked. US7 shippable.

---

## Phase 10: Polish & Cross-Cutting

- [X] T065 [P] Create a root `README.md`: what D2OS is, the 15-module map, a `docker compose up` +
  `bootRun` quickstart, the env-var surface (link `.env.example`), and the test tiers (unit vs
  Testcontainers ITs vs `@Tag("slow")`). Acceptance: file present, links resolve.
- [X] T066 [P] Create `CLAUDE.md` at repo root capturing the conventions in this file's header (system
  Gradle path, global Flyway V-namespace, additive-within-module rule, SPI dependency-inversion
  pattern) so future contributors/agents inherit them. Acceptance: file present.
- [X] T067 [P] Update `docs/enhancement-plan.md` to mark the E1–E14 items now delivered by this
  feature, cross-referencing spec 008. Acceptance: file updated.
- [ ] T068 Run `/opt/gradle/bin/gradle spotlessApply` then `spotlessCheck` to normalize formatting
  across all new files. Acceptance: `spotlessCheck` passes.
- [ ] T069 Run the full local verification: `/opt/gradle/bin/gradle compileJava compileTestJava -q --rerun`
  and `/opt/gradle/bin/gradle :test-support:test --tests ArchitectureRulesTest`. Acceptance: both green.
- [ ] T070 Execute the `specs/008-production-readiness/quickstart.md` scenarios that do not require a
  live cluster (unit layer, metrics endpoint via `@SpringBootTest`, migration presence) and record
  results in a new `specs/008-production-readiness/quickstart-results.md`. Acceptance: results file
  present.

---

## Dependencies & Execution Order

### Phase dependencies
- **Setup (P1)** → no deps; start immediately. **T001 unblocks all integration tests.**
- **Foundational (P2: T006)** → after Setup; blocks US2 + US4 (they read the management config).
- **User stories (P3-P9)** → after Foundational. Priority order US1 → US2 → US3 → US4 → US5 → US6 → US7.
- **Polish (P10)** → after the desired stories.

### Cross-story notes
- US1's T007-T011 (unit tests) are independent of everything except Setup — do them first for the MVP.
- US5 T050 edits `AuditChainCanonicalizer`; if US1 T008 already created the test, update it in T050.
- US6 T058 edits `EquivalenceVerifier`; independent of US5.
- US7 T061 edits the `ci.yml` created in US1 T012 — US7 depends on US1 for that file.

### Parallel opportunities
- All of T002-T004 (Setup) run in parallel.
- All five unit tests T007-T011 run in parallel (different files).
- The nine `@SchedulerLock` tasks T026-T033 run in parallel (different files) after T023-T025.
- Metrics deps T015-T017 run in parallel; alert/dashboard files T021-T022 in parallel.
- Helm files T038/T039/T041 in parallel.

---

## Parallel Example: User Story 1 (fast unit layer)

```bash
Task: "T007 CycleDetectorTest in projection/src/test/java/com/d2os/projection/cycle/CycleDetectorTest.java"
Task: "T008 AuditChainCanonicalizerTest in casecore/src/test/java/com/d2os/casecore/audit/AuditChainCanonicalizerTest.java"
Task: "T009 EscalationPolicyResolverTest in governance/src/test/java/com/d2os/governance/escalation/EscalationPolicyResolverTest.java"
Task: "T010 TokenBudgetGuardTest in persona/src/test/java/com/d2os/persona/TokenBudgetGuardTest.java"
Task: "T011 WorkspaceScopeGuardTest in persona/src/test/java/com/d2os/persona/gateway/WorkspaceScopeGuardTest.java"
```

---

## Implementation Strategy

### MVP (User Story 1 only)
1. Phase 1 Setup (T001 is the linchpin — realigns Testcontainers).
2. Phase 2 Foundational (T006).
3. Phase 3 US1 — CI + fast unit layer.
4. **STOP and VALIDATE**: CI runs the previously-dormant ITs green; unit layer < 60 s. This alone
   converts every "the suites pass" claim into an observed fact — the single highest-value increment.

### Incremental delivery
US1 (verification) → US2 (observability) → US3 (scale-safety) → US4 (deploy) → US5 (auth/RBAC) →
US6 (real content) → US7 (contracts/perf/DR). Each ships independently and adds value without breaking
prior stories.

## Notes
- `[P]` = different files, no incomplete-task dependency.
- Every code task ends with a concrete Acceptance check runnable with the system Gradle.
- Integration/`@SpringBootTest` suites need a Docker runtime; they compile locally and run in CI (T012).
- Total: 70 tasks (Setup 5, Foundational 1, US1 8, US2 8, US3 13, US4 7, US5 12, US6 5, US7 5, Polish 6).
