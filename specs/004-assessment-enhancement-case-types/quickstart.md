# Quickstart: Assessment + Enhancement Case Types — Validation Guide

**Feature**: 004-assessment-enhancement-case-types · **Date**: 2026-07-07
Proves SC-001…SC-008. References: [data-model.md](data-model.md),
[contracts/api.yaml](contracts/api.yaml), [research.md](research.md).

## Prerequisites

- Docker (PostgreSQL 16 + pgvector, MinIO via `docker-compose.yml`); Testcontainers as in prior
  phases; `StubAiGatewayClient` — no provider needed
- Build: `./gradlew` (or Docker `gradle:8.10-jdk21`)
- Phase 3 merged (knowledge injection active — FR-018)

## One-command validation

```bash
./gradlew :app:test
```

All Phase 1–3 suites re-run unchanged (SC-008) plus the six new suites below.

## Scenario walkthroughs

### 1. Three-way routing with human confirm (SC-001, SC-002) — `CaseRoutingIT`

1. Submit three problems: from-scratch, evaluation-of-existing, change-against-baseline.
2. `GET /submissions/{id}/case-type` → proposals INITIATION / ASSESSMENT / ENHANCEMENT.
3. Confirm each (`POST …/case-type/confirm`) → Cases created with confirmed types; all three run
   to Delivered (SC-001).
4. Override subtest: confirm a different type than proposed → `overridden=true`, Decision recorded,
   original proposal preserved. Ambiguity subtest: attribute set matching no rule → proposal
   `UNDETERMINED`, case creation blocked (`412`) until human confirm resolves it.

### 2. Assessment is read-only (SC-003) — `AssessmentReadOnlyIT`

1. Run an Assessment case end to end → package contains only `FINDINGS` + `RECOMMENDATION` kinds.
2. Seeded rogue step attempts a mutating artifact write → refused at the artifact write path,
   AuditEntry recorded, case still completes.
3. Assert Feature baseline byte-unchanged and `active_mutating_case_id` never set.

### 3. Enhancement anchors to the baseline (SC-004) — `EnhancementBaselineIT`

1. Deliver an Initiation baseline on a Feature; run an Enhancement case against it.
2. Assert: `GET /cases/{id}/baseline` lists the pinned baseline revisions; package contains
   delta-docs + impact analysis; **100% of delta/impact revisions carry `DERIVES_FROM` trace links**
   to specific baseline revisions.
3. No-baseline subtest: confirm ENHANCEMENT on a baseline-less Feature → `422`.
4. Superseded-baseline subtest: publish a newer revision after case start → case keeps its pinned
   references; impact analysis surfaces the supersession.

### 4. One active mutating case per Feature (SC-005) — `MutatingGuardIT`

1. Fire two concurrent mutating case creations on one Feature → exactly one `201`, one `409` with
   the active case reference (no queueing, no lock wait).
2. Create an Assessment case on the same Feature while the mutating case runs → admitted.
3. Drive the mutating case terminal → slot released (same tx); a new mutating case now succeeds.

### 5. Conditional artifacts + schema freeze (SC-006, SC-007) — `ConditionalArtifactIT`, `SchemaFreezeIT`

1. Submit with `personal_data=true` → `GET /cases/{id}/required-artifacts` shows `template.dpia`
   (source CONDITIONAL); delivery blocked until the DPIA artifact exists; without the flag, base
   set only.
2. `SchemaFreezeIT`: assert `information_schema.tables` inventory after V16 equals the V14
   inventory (zero new tables); assert both case types exist purely as published DefinitionAssets.

### 6. Prior-phase guarantees (SC-008)

Re-run unchanged: SubmitToDeliver, ParallelExecution, Leakage, InjectionSeed, TokenBudget,
AuditGrant, Knowledge* suites — all green with three case types active.

## Expected results summary

| Scenario | Proves | Suite |
|---|---|---|
| 1 | SC-001, SC-002 | CaseRoutingIT |
| 2 | SC-003 | AssessmentReadOnlyIT |
| 3 | SC-004 | EnhancementBaselineIT |
| 4 | SC-005 | MutatingGuardIT |
| 5 | SC-006, SC-007 | ConditionalArtifactIT, SchemaFreezeIT |
| 6 | SC-008 | existing suites |
