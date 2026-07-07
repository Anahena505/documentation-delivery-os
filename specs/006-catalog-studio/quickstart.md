# Quickstart: Catalog Studio (Admin UI) — Validation Guide

**Feature**: 006-catalog-studio · **Date**: 2026-07-07
Proves SC-001…SC-008. References: [data-model.md](data-model.md),
[contracts/api.yaml](contracts/api.yaml), [research.md](research.md).

## Prerequisites

- Docker (PostgreSQL 16 + pgvector, MinIO); Testcontainers; no AI provider needed (the studio
  runs no AI operations)
- Build: `./gradlew` (or Docker `gradle:8.10-jdk21`)
- **Phase 5 built and merged** — publish gates run the Phase 5 approval-gate subprocess; this is
  the phase's blocking upstream dependency

## One-command validation

```bash
./gradlew :app:test
```

All Phase 1–5 suites re-run unchanged plus the five new suites below
(`ResolutionBenchmarkIT` tagged slow — nightly CI).

## Scenario walkthroughs

### 1. All eight types authorable as drafts (SC-001) — `StudioAuthoringIT`

1. Via `POST /catalog/drafts`, create a draft of each of the 8 types; a Rule draft with DMN XML
   content and a Prompt/Rubric draft with typed-slot bodies (slot validation rejects malformed
   slots).
2. Reload each draft (`GET /catalog/drafts/{id}`) → full content restored for editing.
3. Assert: drafts are not resolvable by any case (resolution filters `Published`); the studio
   pages render each editor (MockMvc/HtmlUnit smoke on Thymeleaf routes).

### 2. Publish governance with prompt diffs (SC-002, SC-003, SC-004) — `PublishGovernanceIT`

1. Draft a new version of a published Prompt → `POST …/submit-review` → status `InReview`,
   D4 gate opened, `deltaReportId` renders the prompt-text diff against the prior version.
2. Attempt `PUT` edit while InReview → `409` (frozen); attempt publish before the gate → `409`.
3. Decide the D4 gate APPROVE (Catalog Owner role) → `POST …/publish` → `Published` with
   checksum recorded; V3 immutability trigger locks the row.
4. Conflict subtests: duplicate `(type, key, version)` → `409`; semver not ordered after the
   prior published version → `409`; content hash changed between submit and publish → `409`.
5. MAJOR subtest: a MAJOR-increment draft opens **two** gates; publish blocked until the
   architecture-board gate also passes (SC-004: 100% blocked without it).
6. Non-prompt type in review → canonical content diff attached (fallback edge case).

### 3. Impact report, matrix, fork provenance (SC-005, SC-008) — `LifecycleToolingIT`

1. Start a case pinning definition X v1; `GET …/deprecation-impact` on X v1 → the active case
   is listed (exact-pin JSONB containment; zero omissions), plus definition dependents and
   subscription copies.
2. `POST …/deprecate` without a report id → `409`; with it → Deprecated + audit; the running
   case keeps executing on its pinned version.
3. Fork X into a new draft → `derived_from_id` set (SC-008: zero forks without provenance);
   forking a Deprecated source works and yields an independent draft.
4. Compatibility matrix: declare ranges in two definitions' bodies, publish, and assert the
   matrix flags an out-of-range pin.

### 4. Copy-on-subscribe (SC-006) — `CopyOnSubscribeIT`

1. Publish a definition in the Global library (zero-UUID workspace); from workspace A,
   `POST /library/definitions/{id}/subscribe` → A holds its own copy (`copied_from_id` set,
   checksum equality verified — T4-d proof), `library_subscription` recorded.
2. Deprecate the Global source → A's copy still resolves unchanged (insulation).
3. Re-subscribe same source → `409`. Cross-workspace probe: workspace B cannot see A's copy
   (leakage suite extension).

### 5. Resolution benchmark (SC-007) — `ResolutionBenchmarkIT` [slow]

1. Seed 500 published definition versions through the real seed path.
2. Run 1 000 mixed `(type, key, version)` resolutions + case-start snapshot pinning.
3. Assert p95 and worst case ≤ 2 s (expected ms-scale; regression tripwire).

### 6. Prior-phase guarantees

Re-run unchanged: all Phase 1–5 suites (gates, knowledge, routing, replay, leakage, audit chain)
— green with the studio module active.

## Expected results summary

| Scenario | Proves | Suite |
|---|---|---|
| 1 | SC-001 | StudioAuthoringIT |
| 2 | SC-002, SC-003, SC-004 | PublishGovernanceIT |
| 3 | SC-005, SC-008 | LifecycleToolingIT |
| 4 | SC-006 | CopyOnSubscribeIT |
| 5 | SC-007 | ResolutionBenchmarkIT |
| 6 | regression | existing suites |
