# Implementation Plan: Production Readiness & Verification Enhancement

**Branch**: `claude/close-repo-emniau` (feature dir `specs/008-production-readiness`) | **Date**: 2026-07-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-production-readiness/spec.md`

## Summary

Operationalize the already-spec-complete D2OS platform: make change-verification real and continuous,
make the running system observable and alertable, make it safe on more than one instance, give it a
deployable unit and per-user accountability, and complete the template→artifact content path. The
approach is deliberately **additive** — no module boundary moves, no engine swap. Every new concern
lands in the module that already owns the seam it touches (metrics in `observability`, auth in
`tenancy/security`, locks alongside each `@Scheduled` job, template rendering in `artifacts`), plus
two new repo-level surfaces that don't exist yet: `.github/workflows/` (CI) and `deploy/` (image +
chart). The design keeps the app 12-factor so secrets and deployment stay outside the code.

## Technical Context

**Language/Version**: Java 21 (toolchain; builds on JDK 25) — unchanged.

**Primary Dependencies**: Spring Boot 3.3.5, Spring Security (OAuth2 Resource Server — *new*),
Micrometer + Micrometer-Tracing (*new wiring*), ShedLock (*new*), springdoc-openapi (*new*), Flowable
7.0.1, Flyway, Testcontainers (*version realigned via BOM*), JaCoCo/Spotless/SpotBugs (*new, build-only*).

**Storage**: PostgreSQL 16 (+ pgvector) and MinIO/S3 — unchanged. One new small table for ShedLock
lock rows; audit records gain actor columns.

**Testing**: JUnit 5 unit tests (*new fast layer*), Testcontainers integration suites (*made
executable*), ArchUnit boundary rules (existing), openapi-diff contract check (*new*), scheduled
benchmark + DR-rehearsal suites (existing, now run).

**Target Platform**: Linux server, container-orchestrated (Kubernetes assumed for probes + secrets +
multi-instance; the orchestrator is the reference target, not a hard dependency).

**Project Type**: Modular-monolith web service (15 Gradle modules, single Spring Boot app). Retained.

**Performance Goals**: Inherited, now *measured not asserted* — traceability query p95 ≤ 2 s at 50k
nodes/200k edges; pin resolution ≤ 2 s at 500 versions; DR RTO ≤ 1 h / RPO ≤ 15 min against the real
schema. New: fast unit layer completes < 60 s with no external infra.

**Constraints**: No microservice split; no replacement of Flowable/Postgres/graph-projection; all
changes additive and provenance-preserving per the constitution; verification gates fail *closed*.

**Scale/Scope**: 15 modules, 36 controllers / 80 endpoints, 29 migrations, 43 integration suites, 9
scheduled jobs. This feature touches cross-cutting config in ~6 modules + 2 new repo-level surfaces;
it adds no new bounded context.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Impact | Verdict |
|---|---|---|
| **I. Definition/Instance Immutability** | Template→artifact rendering (US6) reads immutable `TemplateDefinition` versions and pins provenance to the exact version; no in-place mutation. | Reinforces |
| **II. Reproducible, Bounded AI Participation** | No change to the AI path or the D1–D4 ladder. Tracing adds a span around the gateway call (observation only). | Neutral/positive |
| **III. System of Record Integrity** | No new source of truth. ShedLock rows and metrics are operational, not domain state. Graph projection unchanged. | Neutral |
| **IV. Workspace Isolation & Provenance** | OIDC adds *user* identity *on top of* existing workspace scoping — the `workspace_id` claim/RLS path is preserved, not replaced. Artifact provenance strengthened. | Reinforces |
| **V. Default-Deny Security & Auditable Gates** | Enforced RBAC + per-user identity on every gate decision directly advances "who decided" and role-gated cross-boundary movement. Secrets move to a managed store. | Strongly reinforces |
| **Additive, not silently swapped** | Testcontainers realigned via BOM (same library, correct versions); OIDC *adds* to workspace scoping; new deps are additive. HS256→RS256 is a documented auth upgrade, not a silent redefinition. | Compliant |
| **Contract-first** | springdoc + openapi-diff makes the existing YAML contracts *enforced* rather than advisory. | Reinforces |

**Result: PASS.** No violations; no Complexity Tracking entries required. The one item warranting a
note is the auth-scheme change (HS256 shared secret → RS256/OIDC): an explicit, documented security
upgrade consistent with Principle V and the "additive, not silently swapped" rule (the old header
fallback is retired with a recorded rationale, and workspace scoping is preserved throughout).

## Project Structure

### Documentation (this feature)

```text
specs/008-production-readiness/
├── plan.md              # This file
├── research.md          # Phase 0 — technology decisions
├── data-model.md        # Phase 1 — entities (operational + audit-actor + provenance)
├── quickstart.md        # Phase 1 — validation guide
├── contracts/           # Phase 1 — auth/RBAC + operational-interface contracts
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT here)
```

### Source Code (repository root)

```text
# New repo-level surfaces
.github/workflows/
├── ci.yml                     # build + unit + IT (container-capable) + ArchUnit + coverage + openapi-diff
└── nightly.yml                # @Tag("slow") benchmarks + DR-rehearsal + backup-verification
deploy/
├── image build (Dockerfile or bootBuildImage)   # versioned OCI image (research picks one)
└── helm/                      # chart: config, Secrets/ConfigMap, liveness/readiness probes

# Existing modules that gain cross-cutting wiring (no boundary changes)
observability/                 # Micrometer registry, RED/USE meters, tracing config, alert-rule starters
tenancy/src/main/java/.../security/   # OIDC resource-server config, AuthenticatedPrincipal + roles, @PreAuthorize
casecore/, governance/         # audit records gain actor_user_id + actor_role; RBAC on gate endpoints
artifacts/, catalog/           # TemplateDefinition -> Artifact rendering with version provenance
app/                           # ShedLock config bean; springdoc; management endpoint exposure
<each module with @Scheduled>   # @SchedulerLock on the 9 jobs (projection, rebuild, cycle, reconciliation,
                                #   audit seal/verify, retention, heartbeat, delivered-knowledge trigger)
test-support/                  # BOM realignment consumers; new unit-test fixtures

# Build
build.gradle                   # testcontainers-bom; jacoco/spotless/spotbugs; new runtime deps
```

**Structure Decision**: **Single modular-monolith app, retained.** Cross-cutting concerns are placed
in the module that owns their seam rather than a new "platform" module, to honor the constitution's
"extend, don't add speculative structure." The only genuinely new top-level directories are
`.github/workflows/` and `deploy/`, which are build/ops surfaces, not bounded contexts. ShedLock
annotations live *with* each job (co-located with the behavior they protect), not centralized.

## Complexity Tracking

> No Constitution Check violations. Table intentionally empty.
