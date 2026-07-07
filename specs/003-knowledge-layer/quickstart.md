# Quickstart: Knowledge Layer — Validation Guide

**Feature**: 003-knowledge-layer · **Date**: 2026-07-07
Proves the spec's Success Criteria (SC-001…SC-009) end-to-end. References:
[data-model.md](data-model.md) for entities/states, [contracts/api.yaml](contracts/api.yaml) for
endpoints, [research.md](research.md) for mechanism decisions.

## Prerequisites

- Docker running (PostgreSQL 16 + pgvector, MinIO via `docker-compose.yml`; Testcontainers uses
  docker-outside-of-docker as in Phases 1–2)
- Build via the pinned toolchain image: `docker run --rm -v "$PWD":/w -w /w gradle:8.10-jdk21 gradle <task>`
  (or local JDK 21 + `./gradlew`)
- No AI provider needed: ITs run with `StubAiGatewayClient` (deterministic outputs **and**
  deterministic hash-derived embeddings — research R3)

## One-command validation

```bash
./gradlew :app:test
```

Runs all Phase 1 + 2 suites unchanged (SC-009) plus the six new Phase 3 suites below. All green =
phase exit criteria demonstrated.

## Scenario walkthroughs

### 1. Scoped retrieval + injection snapshot (SC-001, SC-002) — `KnowledgeRetrievalIT`

1. Seed workspace A with KnowledgeItems: workspace-scoped, project-scoped (matching + non-matching
   tags/profile), plus a `DEPRECATED` item.
2. Run an Initiation persona operation (stub gateway) in a project of workspace A.
3. Assert: envelope's knowledge slot contains only PUBLISHED, scope-ancestor, tag∩profile-matched
   items (≤ configured cap); `knowledge_injection_snapshot` rows exist for exactly those items with
   exact `(key, version)` + `content_hash` + `position`, written in the same transaction as the
   `operation_execution` row (crash-injection subtest: no execution without its snapshots).

### 2. Byte-identical replay after item changes (SC-003) — `KnowledgeReplayIT`

1. Execute a knowledge-injected operation; record output hash.
2. Publish a new version of an injected item, deprecate another.
3. Replay the execution via the replay harness → knowledge context reconstructed from snapshots
   (verifying `content_hash`), output byte-identical to the original.

### 3. Cross-workspace leakage (SC-004) — `KnowledgeLeakageIT`

1. Seed workspaces A and B with distinctively tagged items.
2. Retrieval in A: assert zero B items regardless of tag/similarity match (partition pruning —
   T2-b).
3. Bypass subtest: hand the gateway an envelope referencing a B item under A's scope → gateway
   scope assertion refuses the call and writes an audit entry (T2-c). Both layers must
   independently block.

### 4. Capture → pre-filter → redaction → D4 → publish (SC-005, SC-006) — `CapturePromotionIT`

1. Drive a demo case to `Delivered` → `knowledge-capture` process starts (outbox trigger);
   candidates appear `CAPTURED`, project-confidential (`GET /knowledge/candidates`).
2. Pre-filter runs: seeded PII (email/phone/tagged-sensitive field) produces `prefilter_finding`
   rows; sensitive-tagged fields already excluded from candidate content; status `PREFILTERED`.
3. Curator persona drafts redaction (rubric-validated, stub gateway); save via
   `POST .../redaction` → new revision, status `REDACTED`; prior revision intact.
4. D4 via `POST .../d4`:
   - approver == redaction actor → `403` (non-self-satisfiable);
   - workspace owner APPROVE → candidate `PUBLISHED`, KnowledgeItem version created with
     `source_candidate_id` provenance, Decision + AuditEntry rows present;
   - sibling candidate REJECT (each stage tested) → stays non-promotable, `rejection_stage` +
     reason recorded, zero partial promotion; gate-order violation attempts → `409`.

### 5. Deprecation flags without rewriting history (SC-007) — `DeprecationIT`

1. Execute operations injecting item K; then `POST /knowledge/items/{K}/deprecate`.
2. Assert: K excluded from new retrievals; `knowledge_affected_execution` flags exactly the
   executions whose snapshots referenced K (count returned by the endpoint matches); snapshots and
   outputs byte-unchanged; replay of a flagged execution still reproduces its original output.

### 6. Knowledge-influence KPI (SC-008) — `InfluenceKpiIT`

1. `POST /knowledge/items/{id}/influence-evaluations` for a seeded item + operation.
2. Assert: two evaluation-flagged OperationExecutions (with/without) under the same rubric
   version; `kpi_sample` row `metric=knowledge_influence` with value = score delta;
   `GET /metrics/knowledge-influence` returns `MEASURED` with the delta.
3. Never-injected item → `NOT_YET_MEASURABLE`, no fabricated sample.

### 7. Prior-phase guarantees hold (SC-009)

Re-run unchanged: `SubmitToDeliverIT`, `ParallelExecutionIT`, `LeakageIT`, `InjectionSeedIT`,
`TokenBudgetIT` (injected knowledge tokens now counted against the case budget), `AuditGrantIT`,
replay suite. All must pass with the knowledge layer active.

## Manual demo (optional, real stack)

```bash
docker compose up -d          # postgres+pgvector, minio
./gradlew :app:bootRun        # seeds Curator persona + KnowledgeItem set via CatalogSeedLoader
```

Then: submit a demo problem → deliver → watch candidates appear → walk the promotion pipeline in
the API per scenario 4 → deprecate an item per scenario 5. Expected outcomes identical to the IT
assertions above.

## Expected results summary

| Scenario | Proves | Suite |
|---|---|---|
| 1 | SC-001, SC-002 (scoped retrieval, 100% snapshot coverage) | KnowledgeRetrievalIT |
| 2 | SC-003 (byte-identical replay) | KnowledgeReplayIT |
| 3 | SC-004 (zero cross-workspace injection, both layers) | KnowledgeLeakageIT |
| 4 | SC-005, SC-006 (default-deny pipeline, zero bypass) | CapturePromotionIT |
| 5 | SC-007 (flags 100%, history untouched) | DeprecationIT |
| 6 | SC-008 (measured delta / not-yet-measurable) | InfluenceKpiIT |
| 7 | SC-009 (Phase 1+2 criteria unchanged) | existing suites |

## Success checklist (T040 — phase exit)

Run `docker run --rm -v "$PWD":/w -w /w gradle:8.10-jdk21 gradle :app:test` and confirm:

- [ ] `KnowledgeRetrievalIT` green — scoped/tagged/capped retrieval + same-tx injection snapshots (SC-001/002)
- [ ] `KnowledgeReplayIT` green — byte-identical replay after supersede + deprecate (SC-003)
- [ ] `KnowledgeLeakageIT` green — zero cross-workspace items at BOTH layers: retrieval/RLS and the
      audited gateway seam (`SCOPE_VIOLATION_BLOCKED` AuditEntry present) (SC-004)
- [ ] `CapturePromotionIT` green — default-deny pipeline: gate order 409s, non-self-satisfiable D4
      403s, publish only on APPROVE with provenance (SC-005/006)
- [ ] `DeprecationIT` green — exact flags, snapshots/outputs byte-unchanged, flagged execution still
      replays (SC-007)
- [ ] `InfluenceKpiIT` green — two `evaluation=true` runs, MEASURED delta emitted with (key,version)
      dimensions, never-evaluated item NOT_YET_MEASURABLE (SC-008)
- [ ] `ArchitectureRulesTest` green — persona ↛ knowledge (SPI-only retrieval), gateway-only providers
- [ ] Phase 1/2 suites green **unchanged**: `SubmitToDeliverIT`, `ParallelExecutionIT`,
      `LeakageSuiteIT`, `InjectionSeedSuiteIT`, `TokenBudgetSuiteIT` (injected-knowledge tokens now
      counted against the case budget), `AuditGrantSuiteIT`, `ConsistencyCheckIT`,
      `AttachmentSandboxIT` (SC-009)
- [ ] Startup seeds verified: Curator persona/playbook/rubric/prompt (CatalogSeedLoader) + the
      KnowledgeItem set incl. non-matching-tag, project-scoped, and DEPRECATED fixtures
      (KnowledgeSeedLoader) — `GET /api/v1/knowledge/items` shows all five with correct statuses
