# Quickstart: Production Readiness & Verification — Validation Guide

**Feature**: 008-production-readiness · **Date**: 2026-07-11
Proves SC-001…SC-011. References: [spec.md](spec.md), [plan.md](plan.md),
[research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/).

## Prerequisites
- A container-capable environment (Docker daemon reachable) — required now that the Testcontainers
  version skew is fixed (R2). The developer sandbox that could not run these suites is not a target.
- Build: `./gradlew` (or system Gradle at `/opt/gradle` per repo convention).
- For US4/US5: an OIDC provider (Keycloak reference) reachable, or the test JWKS fixture.

## One-command verification (US1)
```bash
./gradlew build            # unit + integration (Testcontainers) + ArchUnit + JaCoCo
```
Expected: all previously-dormant integration suites now **execute and pass** against real
Postgres/MinIO containers (SC-002); the fast unit layer completes in < 60 s (SC-003); ArchUnit rules
pass; a coverage report is produced (SC-001 evidence in CI).

## Scenario walkthroughs

### 1. Change verification is real and fails closed (US1 · SC-001/002)
1. Open a PR that violates a tenant-isolation ArchUnit rule → CI `ci.yml` reports **failing**, merge
   blocked.
2. Open a correct PR → CI runs build + unit + a container-capable IT slice + ArchUnit + openapi-diff →
   **green**, merge allowed.
3. Disable the CI runner's Docker → the integration job **fails** (not skipped) — verifies fail-closed.

### 2. Observability + alerting (US2 · SC-004/005)
1. `GET /actuator/prometheus` → RED metrics per route and USE metrics for all 9 jobs are present.
2. Stall the projector so lag exceeds `projection.lag-threshold-seconds` → `d2os.projection.lag.seconds`
   climbs and the shipped Prometheus alert fires to monitoring (not only an in-app notice).
3. Inspect a failed request's logs → they carry `workspace_id`/`case_id`/`trace_id` (SC-004).

### 3. Once-per-cycle across instances (US3 · SC-006)
1. Start two app instances against one Postgres.
2. Let a scheduled job's cycle elapse (e.g. the projector sweep).
3. Assert the job body executed **once** (one `shedlock` row claimed; one run's side effects), not
   twice — sustained over a multi-cycle window with zero duplicates.

### 4. Deployable unit + probes + secrets (US4 · SC-007)
1. `./gradlew bootBuildImage` → a versioned OCI image.
2. `helm install` into a clean namespace with values → pods start from the image + config, no build
   step.
3. Orchestrator marks a pod Ready only after `GET /actuator/health/readiness` = `UP`; traffic routes
   only then. Remove a required Secret key → startup **fails loudly** (SC-007 fail-loud edge case).

### 5. Authenticated identity + RBAC (US5 · SC-008)
1. Call `POST /gates/{gateId}/decision` with a valid OIDC token **lacking** the approver role → `403`.
2. Call it with an authorized user's token → `200`; the persisted audit record carries
   `actor_user_id` + `actor_role` (data-model §2) and the values are inside the tamper-evident hash
   chain (alter the actor → `AuditChainVerifier` reports a break).
3. Present an expired token → `401`, never partially trusted (edge case).

### 6. Real artifact content with provenance (US6 · SC-009)
1. Run a case to `Delivered`.
2. Each produced artifact's content derives from its `TemplateDefinition` body (no placeholder), and
   the revision carries `source_template_id` + `template_version` (data-model §3).
3. Replay the case → byte-identical (deterministic rendering preserves the replay contract);
   `EquivalenceVerifier` now covers the `TEMPLATE`/`DEFINITION_VERSION` node + `PRODUCED_FROM` edge.

### 7. Contracts + performance/recovery stay honest (US7 · SC-010/011)
1. Introduce a breaking change to a controller without updating its contract → CI `openapi-diff` step
   **fails** (SC-010).
2. `nightly.yml` runs the traceability p95 benchmark (≤ 2 s at 50k/200k) and pin-resolution benchmark
   (≤ 2 s at 500 versions) → results recorded; a regression beyond the agreed margin is flagged
   (SC-011).
3. The DR rehearsal restores the **real D2OS schema** and runs `AuditChainVerifier` + a smoke Case to
   `Delivered` post-restore before the rehearsal is deemed successful (FR-017).

## Expected results summary
| Scenario | Proves | Evidence |
|---|---|---|
| 1 | SC-001, SC-002 | CI run status + IT results |
| 2 | SC-004, SC-005 | `/actuator/prometheus` + firing alert |
| 3 | SC-006 | single `shedlock` claim per cycle, 2-instance test |
| 4 | SC-007 | image + Helm install + Ready gating + fail-loud secret |
| 5 | SC-008 | 403/200 + actor-stamped, hash-chained audit record |
| 6 | SC-009 | rendered artifact content + provenance columns + replay |
| 7 | SC-010, SC-011 | openapi-diff failure + recorded benchmark/DR results |

## Notes
- This feature adds no new tenant-facing domain endpoints; it changes the security scheme on the
  existing 80 endpoints (`contracts/auth-and-rbac.yaml`) and adds operator/orchestrator surfaces
  (`contracts/operational-interfaces.md`).
- Concrete implementation (CI YAML, Helm chart, ShedLock wiring, OIDC config, rendering code, unit
  tests) belongs to `/speckit-tasks` + implementation, not here.
