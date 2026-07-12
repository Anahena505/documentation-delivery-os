# D2OS Enhancement Plan

**Date**: 2026-07-11 · **Scope**: whole repository (15 modules, specs 001–007) · **Basis**: full
source review against the project constitution and the delivered spec backlog (73/73 tasks complete,
PRs #10–#15 merged).

This is a forward-looking plan, not a defect list. The delivered system is architecturally sound and
functionally complete against its own specs. The enhancements below take it from *"spec-complete and
compiles"* to *"continuously verified, observable, and production-deployable."*

---

## 1. Health assessment

### Scale
| Metric | Value |
|---|---|
| Bounded-context modules | 15 (modular monolith) |
| Main source | 312 Java files · ~22,600 LOC |
| REST controllers / endpoints | 36 / 80 |
| Flyway migrations | 29 (single global V-namespace) |
| OpenAPI contract files | 7 |
| Integration-test suites (`*IT`) | 43 |
| Unit-test classes (`*Test`) | 6 |
| Enforced ArchUnit boundary rules | 9 |

### What is strong (preserve these)
- **Clean modular-monolith boundaries**, mechanically enforced by 9 ArchUnit rules (persona
  statelessness, sole-writer graph tables, engine-coupling boundaries, UI-agnostic catalog, etc.).
- **Principled domain model** — immutable semver Definitions vs. runtime Instances, CQRS graph
  projection that is rebuildable from relational truth, polymorphic edge tables.
- **Defense-in-depth tenant isolation** — cryptographic JWT `workspace_id` scoping *plus* Postgres
  RLS bound per-connection via a least-privilege `d2os_app` role.
- **Disciplined secret hygiene** — every credential env-driven with fail-loud (no silent defaults),
  `.env` gitignored, `*.key`/`*.pem` gitignored, `.env.example` documents the surface.
- **Real, provider-agnostic AI gateway** (`HttpAiGatewayClient`) behind a single choke point with a
  workspace-scope guard and per-workspace rate limiter — not a stub-only path.
- **Exceptionally well-documented rationale** in config and code (the `application.yml` comments alone
  are a design record).

### The central risk
> **The entire integration-test safety net is unverified.** 43 IT suites exist but none has ever
> executed — a Testcontainers dependency-version skew (below) prevents them from running in this
> environment, and there is no CI to run them anywhere else. Everything downstream ("re-ran Phase 1–N
> suites", "benchmark passes") is therefore a *compile-and-audit* claim, not an *observed-green*
> claim. Closing this gap is the highest-leverage work in this plan and unblocks trustworthy
> verification of everything else.

---

## 2. Enhancement themes

Priorities: **P0** = unblock verification (do first, mostly low-effort, high-leverage) · **P1** =
production readiness · **P2** = completeness & hardening. Effort: **S** ≤2d · **M** 3–8d · **L** >8d.

---

### P0 — Unblock verification

#### E1 · Stand up CI/CD  *(S, high impact)*
**Evidence**: no `.github/` directory exists; nothing runs the build, tests, migrations, or ArchUnit
rules on push. The five merged PRs were verified by hand.
**Action**:
- Add `.github/workflows/ci.yml`: matrix on JDK 21, run `./gradlew build`, `test`, and the ArchUnit
  suite on every PR and push to `main`. Cache Gradle. Upload test reports as artifacts.
- Add a `slow`-tagged job (nightly) for the `@Tag("slow")` benchmark ITs.
- Gate merges on green (branch protection). Fail the build on ArchUnit violations (already wired as
  a test — just needs to run in CI).
**Why**: turns every "re-ran the suites" claim into an observed fact and stops regressions the moment
they land. Prerequisite for trusting E2/E3.

#### E2 · Fix the Testcontainers version skew so the 43 ITs can actually run  *(S–M, high impact)*
**Evidence**: `build.gradle` requests `org.testcontainers:*:1.20.3`, but with no BOM the transitive
graph resolves **core down to 1.19.8** (`gradle :app:dependencies` shows
`testcontainers:1.20.3 -> 1.19.8`), which ships `docker-java 3.3.6`. That old client negotiates a
Docker API version this environment's daemon (Engine 29.3.1) rejects — the standing "compile-verified
only" caveat repeated across every spec's quickstart.
**Action**:
1. Import the Testcontainers BOM (`platform("org.testcontainers:testcontainers-bom:1.20.x")`) in
   `build.gradle` and drop the per-artifact versions, so **core, jdbc, postgresql, and minio all
   align** and pull the newer `docker-java` that does correct API negotiation.
2. Verify locally in CI (where a modern Docker daemon is available) that one representative IT
   (`AuditChainIT` or `TraceabilityQueryIT`) goes green.
3. If a specific daemon still refuses negotiation, pin `DOCKER_API_VERSION` in the CI job env as a
   documented fallback.
**Why**: this is a genuine build defect (version skew), independent of the sandbox. Fixing it converts
43 dormant suites into a live regression net covering RLS isolation, gate escalation, audit
hash-chaining, graph equivalence, and copy-on-subscribe.

#### E3 · Re-balance the test pyramid — add a fast unit layer  *(M, high impact)*
**Evidence**: 6 unit classes vs 43 heavyweight ITs. Pure algorithmic logic is only exercised (and only
nominally) through Spring + Testcontainers: `CycleDetector` (Kahn's algorithm + bounded DFS),
`AuditChainCanonicalizer`/`AuditChainSealer` (hash-chaining), `EscalationPolicyResolver`,
`TokenBudgetGuard`, `WorkspaceScopeGuard`, DMN hit-policy resolution, version resolution.
**Action**: add plain-JUnit unit tests (no Spring, no Docker) for the deterministic core of each of
those classes — cycle graphs with/without loops, hash-chain tamper detection, budget-cap boundaries,
scope-violation rejection. Target: sub-second feedback for domain logic; keep ITs for wiring/RLS/engine
integration only.
**Why**: gives a feedback loop that runs in seconds without Docker, and puts the *freshest, least-run*
code (projection/cycle/influence) under tests that actually execute today.

#### E4 · Add coverage + static-analysis quality gates  *(S)*
**Evidence**: no JaCoCo, Spotless, Checkstyle, SpotBugs, or PMD anywhere.
**Action**: JaCoCo report + a modest coverage floor on the domain modules (not the app slice); Spotless
(google-java-format) for consistent formatting; SpotBugs for null/resource-leak classes of bug. Wire
all into the E1 CI job.
**Why**: cheap, standard guardrails; makes E3's coverage visible and prevents style drift across 15
modules and multiple contributors/agents.

---

### P1 — Production readiness

#### E5 · Real authentication + RBAC enforcement  *(L, high impact)*
**Evidence**: `SecurityConfig` is `anyRequest().permitAll()`; the JWT proves *workspace* but carries
**no user identity and no roles**. Role-gated operations (`catalog-owner`, `architecture-board`,
governance approvers) look up role *names* with no authenticated principal behind them. The signing
key is symmetric **HS256** — anyone holding the shared secret can mint a valid token for *any*
workspace.
**Action**:
- Integrate an OIDC IdP (Keycloak/Auth0/Entra). Move to **asymmetric RS256/ES256** verified against
  the IdP's JWKS, so the app never holds signing material and keys rotate independently.
- Carry `sub` (user identity) and `roles`/`groups` claims; bind them to the request principal.
- Replace `permitAll()` with method-level `@PreAuthorize` on the role-gated controllers/services, and
  record the **authenticated user** (not just workspace) in every audit record — Principle V requires
  "who … decided," which today has no real subject.
**Why**: the system explicitly targets regulated/compliance-sensitive workloads; per-user identity and
enforced RBAC are the difference between "auditable in principle" and "auditable in fact."

#### E6 · Production observability  *(M, high impact)*
**Evidence**: `spring-boot-starter-actuator` is on the classpath but only `/health`+`/info` are
exposed (no explicit `management` config); metrics are a **bespoke** `kpi_sample` table +
`MetricsController` + `KpiEmitter`; there is no Micrometer wiring, no `/prometheus` endpoint, no
distributed tracing, and no structured logging. Code computes `lag-threshold-seconds`,
`gap-alert-threshold`, and SLA timers but only ever raises **in-app notifications** — nothing an
operator's monitoring can page on.
**Action**:
- Expose Micrometer → Prometheus; add RED metrics on the 80 HTTP endpoints and USE metrics on the 9
  scheduled jobs (execution count, duration, lag, failures).
- Add OpenTelemetry tracing across the request → engine → gateway path (the AI gateway call is the
  natural first span to instrument).
- JSON structured logging with `workspace_id`/`case_id`/`trace_id` MDC.
- Turn the existing threshold constants into real **alerting rules** (projection lag > 30s, open gaps
  > 10, gate SLA breached, rebuild-equivalence divergence) and ship starter Grafana dashboards.
- Keep `kpi_sample` for business KPIs; stop using it as the *infrastructure* metrics store.
**Why**: a system with backup/restore, hash-chain sealing, and drift-detection jobs needs to *tell you*
when those fail. Right now failures are silent unless someone reads a notifications table.

#### E7 · Horizontal-scale correctness for scheduled jobs  *(S–M)*
**Evidence**: 9 `@Scheduled` jobs (`Projector`, `RebuildJob`, `CycleDetector`, `ReconciliationJob`,
`AuditChainSealer`, `AuditChainVerifier`, `RetentionVerificationJob`, `ProgressHeartbeat`,
`CaseDeliveredKnowledgeTrigger`) and **zero** leader-election/locking. On more than one instance each
job runs on *every* node — double-projection, duplicate seals, racing rebuilds.
**Action**: add **ShedLock** (JDBC-backed, the DB is already there) with `@SchedulerLock` on each job,
sized to each cadence. Document single-writer invariants (the projector is already meant to be sole
writer via `d2os_projector` — make that operationally true, not just role-true).
**Why**: the current design silently assumes a single instance. This is a correctness bug the moment
anyone scales out or runs blue/green.

#### E8 · Containerize + deployment manifests  *(M)*
**Evidence**: `docker-compose.yml` runs only `postgres`/`minio`/wal-archive; there is **no Dockerfile
for the app**, no image build, no k8s/Helm. The app can't be deployed as-is.
**Action**: multi-stage Dockerfile (or Spring Boot buildpacks) producing a slim JRE-21 image; wire
liveness/readiness to the actuator probes (the `WorkspaceContextFilter` already exempts
`/actuator/health` for exactly this); a Helm chart or Kustomize base with the env-var surface from
`.env.example` mapped to Secrets/ConfigMaps; build+push the image in CI.
**Why**: completes the "operating system" story — today the delivery target is undefined.

#### E9 · Secrets & key management  *(S–M, pairs with E5)*
**Evidence**: all secrets are plain env vars; JWT is a shared HS256 secret with no rotation story.
**Action**: integrate a secrets manager (Vault/cloud KMS) for DB, MinIO, and IdP material; adopt
asymmetric JWT verification (E5) so there is no app-held signing secret to rotate; document a rotation
runbook.
**Why**: removes the single most sensitive shared secret and makes rotation a routine op, not an
outage.

---

### P2 — Completeness & hardening

#### E10 · Close the deferred functional gaps  *(M)*
**Evidence**: `ArtifactService` and `CatalogSeedLoader` both note that **TemplateDefinition→Artifact
content wiring is deferred** and the persona key "stands in" for the template association; the real
catalog content (the §8 operation roster) is seeded as placeholders (`CatalogSeedLoader` T065 note).
The *mechanics* run end-to-end; the *content* that makes generated artifacts real does not yet flow
from templates.
**Action**: wire `TemplateDefinition` → generated `Artifact` (template body → rendered artifact,
carrying the definition-version provenance the constitution requires); replace placeholder operation
content with the real roster; extend `EquivalenceVerifier`/replay coverage to the newly-wired
`TEMPLATE`/`DEFINITION_VERSION` node types it currently skips.
**Why**: turns the platform from "correct plumbing" into "produces the actual documentation deliverables"
— the product's reason for existing.

#### E11 · API contract conformance testing  *(S–M)*
**Evidence**: contracts are hand-authored OpenAPI YAML (7 files, per the constitution's contract-first
rule), but **no `springdoc`/OpenAPI runtime** is present and nothing checks the 80 live endpoints
against the 7 contracts — they can drift silently.
**Action**: add `springdoc-openapi` to generate the live spec at runtime; run `openapi-diff` in CI
against the checked-in contracts to fail on drift; optionally generate typed clients from the contracts
for the ITs.
**Why**: makes contract-first a *checked* guarantee instead of a convention.

#### E12 · Actually measure the performance targets  *(S, needs E1/E2)*
**Evidence**: `TraceabilityBenchmarkIT` (p95 ≤ 2s at 50k nodes/200k edges) and `ResolutionBenchmarkIT`
(pin resolution ≤ 2s at 500 versions) exist but have **never run**; the numbers are asserted, not
observed.
**Action**: once E2 lands, run the `slow` benchmarks in the nightly CI job and record baselines; add a
regression guard (fail if p95 regresses > X%). Feed results into the observability dashboards (E6).
**Why**: NFR-8/NFR-9 are currently unproven; this makes them continuously proven.

#### E13 · DR at real scale + automated backup verification  *(M)*
**Evidence**: the executed DR drill (`ops/dr-drill.md`, `quickstart-results.md`) proved the **Postgres
PITR mechanism** (real base backup + WAL replay, measured RTO 81.5s, exact recovery boundary) but only
against a single throwaway table — **never against the real D2OS schema or the app stack**, and the
idle `archive_timeout` path was inconclusive.
**Action**: re-run the drill in a Docker-capable environment against the real Flyway schema + booted
app, including the runbook's Post-Restore Validation (`AuditChainVerifier`, `ReplayHarness`, a smoke
Case to `Delivered`); add a scheduled **backup-verification job** that restores the latest base backup
into a scratch instance and runs `AuditChainVerifier` automatically; re-verify the idle-timeout path on
a non-sandboxed host.
**Why**: upgrades DR from "mechanism proven" to "recovery of *this system* proven, continuously."

#### E14 · Developer & operator entry-point docs  *(S)*
**Evidence**: no root `README`, no `CLAUDE.md`, no consolidated architecture overview. The `specs/` and
`docs/d2os-implementation-plan.md` are excellent but there is no "start here / how to run / how to
operate" page.
**Action**: a root `README` (what it is, module map, `docker compose up` + `bootRun` quickstart, env
surface, test tiers); a one-page architecture overview (the 15 modules + the 5 constitutional
principles + the CQRS projection); a `CLAUDE.md` capturing the conventions this codebase already
follows (system Gradle at `/opt/gradle`, the global Flyway V-namespace, the SPI dependency-inversion
pattern) so future contributors/agents inherit them.
**Why**: lowers onboarding cost and encodes the (currently tribal) conventions that keep the module
boundaries clean.

---

## 3. Sequenced roadmap

**Wave 1 — Verification foundation (≈1–2 weeks).** E1 (CI) → E2 (Testcontainers BOM) → E3 (unit layer)
→ E4 (quality gates). Order matters: CI first so the Testcontainers fix is *proven* green somewhere,
then the unit layer and gates ride the same pipeline. Exit criterion: a green CI run that executes the
ArchUnit rules, a representative sample of the 43 ITs, and the new unit tests.

**Wave 2 — Production readiness (≈3–5 weeks).** E6 (observability) and E7 (ShedLock) first — they are
independent and de-risk everything operational. Then E8 (containerize) + E9 (secrets), then E5
(auth/RBAC) as the largest single item. Exit criterion: the app builds an image, deploys with real
health probes, emits Prometheus metrics, runs safely on >1 instance, and enforces per-user RBAC.

**Wave 3 — Completeness & hardening (≈2–4 weeks).** E10 (template→artifact wiring — the product-value
item), E11 (contract conformance), E12 (run the benchmarks), E13 (DR at scale), E14 (docs). Exit
criterion: generated artifacts carry real template content; contracts and performance targets are
continuously checked; DR is proven against the real schema.

---

## 4. Quick wins (high value, ≤1 day each)
1. **`.github/workflows/ci.yml`** running `build` + ArchUnit — instant regression protection (E1).
2. **Testcontainers BOM** one-block change in `build.gradle` — unblocks 43 suites (E2).
3. **Root `README`** — the missing front door (E14).
4. **Expose `management.endpoints.web.exposure.include=health,info,prometheus,metrics`** + Micrometer
   Prometheus registry — metrics endpoint in minutes (first slice of E6).
5. **JaCoCo report task** — makes the (currently invisible) coverage story visible (E4).

---

## 5. Explicitly out of scope / non-goals
- Rewriting the modular monolith into microservices — the boundaries are clean and enforced; splitting
  now would add operational cost without demonstrated need (and would violate the constitution's
  "extend, don't add speculative structure" principle).
- Replacing Flowable, Postgres, or the graph-as-projection design — all are sound constitutional
  choices; the graph store *interface* already allows a PD-4 swap later if benchmarks (E12) ever demand
  it.
- Adding new product features (new case types, new personas) ahead of the deferred-wiring completion
  (E10) — finish the content path before widening it.

---

## 6. Delivery status (feature 008)

This plan was turned into the spec-kit feature **`specs/008-production-readiness/`** (spec → plan →
70 tasks) and is being implemented on branch `claude/close-repo-emniau`. Mapping of the E-items above
to that feature's user stories and current state:

| E-item | 008 story | Status |
|---|---|---|
| E1 CI/CD | US1 | **Delivered** — `.github/workflows/ci.yml` + `nightly.yml` |
| E2 Testcontainers fix | US1 (T001) | **Delivered & proven** — Boot-managed `testcontainers.version`→1.20.3 moves docker-java to 3.4.0; API-negotiation error gone. ITs run in CI (sandbox lacks image-registry egress only) |
| E3 unit layer | US1 | **Delivered** — 17 fast unit tests, no infra |
| E4 coverage/static analysis | Setup | **Delivered** — JaCoCo + Spotless + SpotBugs (non-breaking) |
| E5 auth + RBAC | US5 | In progress |
| E6 observability | US2 | In progress |
| E7 ShedLock | US3 | In progress |
| E8 containerize/deploy | US4 | **Partly delivered** — Helm chart + probes; image/build config pending |
| E9 secrets/keys | US4/US5 | Helm secret-refs delivered; asymmetric-key cutover with US5 |
| E10 template→artifact | US6 | Pending |
| E11 contract conformance | US7 | Pending |
| E12 run benchmarks | US7 | `nightly.yml` wired; baselines pending a CI run |
| E13 DR at scale | US7 | **Partly delivered** — `ops/dr-rehearsal.sh` + `ops/backup-verify.sh` |
| E14 docs | Polish | **Delivered** — root `README.md` + `CLAUDE.md` |

See `specs/008-production-readiness/tasks.md` for per-task `[X]` state.
