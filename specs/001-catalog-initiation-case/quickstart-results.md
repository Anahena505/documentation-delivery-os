# Quickstart Validation Results

**Date**: 2026-07-06 · **Feature**: Catalog Spine + Initiation Case Type
**Verified against**: live PostgreSQL 16 (+pgvector) and MinIO via Testcontainers, Gradle 8.10 / JDK 21 (Docker), Flowable 7 embedded.

All validation was executed automatically as JUnit integration tests under `app/src/test/java/com/d2os/app/`
(not manual `curl`), since those are the repeatable, CI-runnable form of the quickstart scenarios.

## Command

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "C:\MySpace\Documentation-os":/app \
  -v d2os-gradle-cache:/home/gradle/.gradle \
  -v //var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -w /app gradle:8.10-jdk21 ./gradlew :app:test
```

**Result: BUILD SUCCESSFUL — 5 tests, 0 failures, 0 errors.**

## Scenario coverage

| Scenario | Success criterion | Test | Status |
|---|---|---|---|
| 1 — Submitted → Delivered, zero manual DB edits | SC-001 | `SubmitToDeliverIT` | ✅ PASS |
| 2 — Replay reconstructs every AI output byte-identically | SC-002 | `SubmitToDeliverIT` (replay assertions) | ✅ PASS |
| 3 — Tenant isolation, zero cross-workspace leak | SC-003 | `LeakageSuiteIT` | ✅ PASS |
| 4 — Seeded malicious submission blocked | SC-004 | `InjectionSeedSuiteIT` | ✅ PASS |
| 5 — Package integrity stamp verifies + Handover Record present | SC-005 | `SubmitToDeliverIT` (`/package/verify`) | ✅ PASS |
| 6 — Three KPIs emitting | SC-006 | `SubmitToDeliverIT` (`/metrics/kpis`) | ✅ PASS |
| 7 — Token budget breach → Suspended | SC-007 | `TokenBudgetSuiteIT` | ✅ PASS |
| Bounded revise loop → Escalated after 3 generations | FR-005 | `InjectionSeedSuiteIT` (every attempt fails → escalate) | ✅ PASS |
| Append-only audit stream (UPDATE/DELETE denied) | T6-a | `AuditGrantSuiteIT` | ✅ PASS |

Additional low-level guarantees separately verified directly against a live Postgres container during the build:
- RLS default-deny + cross-tenant block + correct-workspace access (least-privilege `d2os_app` role vs. table-owner exemption).
- DefinitionAsset publish immutability trigger (Principle I).
- One-active-mutating-Case-per-Feature partial unique index (FR-016).
- All 9 Flyway migrations (V1–V9) apply clean; 22+ tables, all RLS-enabled.

## Notes / documented deferrals (not failures)

- **AI Gateway** is exercised via a deterministic stub in tests (no live provider key in CI); the real
  `HttpAiGatewayClient` (Anthropic Messages API shape) is wired for production use.
- **Rubric scoring** uses a length/structure heuristic in v1; an AI-judge scorer is future work. The
  pass/fail contract (weighted ≥80%, no critical fail) is real and enforced.
- **Catalog content**: the runtime catalog is seeded programmatically; authoring the real persona/
  template prose (7 revised v0 + 2 greenfield templates, 3 playbooks) is a content exercise, out of
  scope for this implementation pass (see T020).
- **Field-level encryption-at-rest** relies on the storage tier in v1; column-level crypto deferred.
- **Escalation resolution** performs the state transition + Decision record; fully re-driving the
  Flowable job from the failed step is future work (see T059).
