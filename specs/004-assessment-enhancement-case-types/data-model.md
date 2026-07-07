# Data Model: Assessment + Enhancement Case Types

**Feature**: 004-assessment-enhancement-case-types · **Date**: 2026-07-07
Delta over the Phase 1–3 schema (V1–V14). **Zero new tables** (FR-016/SC-007) — this phase is
catalog content plus two column-only migrations. Research references:
[research.md](research.md) R1–R6.

## New Tables

**None.** `SchemaFreezeIT` asserts the `information_schema.tables` inventory after V16 equals the
inventory after V14.

## Modified Entities

### feature (V15, tenancy — columns only)

Optimistic single-active-mutating-case guard (Q2, R3).

| New field | Type | Notes |
|---|---|---|
| aggregate_version | bigint NOT NULL DEFAULT 0 | advances on every acquire/release |
| active_mutating_case_id | uuid NULL | the one active mutating case; NULL = slot free |

**Transitions** (single guarded UPDATEs, R3):

```
slot free  ──acquire (create mutating case; WHERE aggregate_version=:v AND slot IS NULL)──▶ occupied
occupied   ──release (case terminal: Delivered/Cancelled/Failed, same tx)────────────────▶ slot free
occupied   ──second acquire attempt──▶ 0 rows updated ⇒ 409 CONFLICT (no queue, no lock)
```

**Invariants**: Assessment (read-only type) never touches these columns; release is idempotent
(`WHERE active_mutating_case_id = :caseId`); every acquire/release/conflict writes an AuditEntry
in the same transaction.

### problem_submission (V16, intake — columns only)

Case-type classification proposal + human confirmation (R5, FR-019).

| New field | Type | Notes |
|---|---|---|
| proposed_case_type | text NULL | DMN output: `INITIATION` \| `ASSESSMENT` \| `ENHANCEMENT` \| `UNDETERMINED` |
| confirmed_case_type | text NULL | set by the human confirm step; the authority of record |
| classification_status | text NOT NULL DEFAULT 'PROPOSED' | `PROPOSED → CONFIRMED` |
| classification_overridden | boolean NOT NULL DEFAULT false | true when confirmed ≠ proposed |

**Invariants**: a Case is created only from a `CONFIRMED` submission; the original proposal is
never overwritten (override sets `confirmed_case_type` + flag, proposal column untouched);
confirm/override writes a Decision + AuditEntry in the same transaction.

## New Catalog Content (definitions in existing tables — the actual "schema" of this phase)

| Definition (type) | Key (proposed) | Body highlights |
|---|---|---|
| CaseTypeDefinition | `case-type.assessment` | `mutating: false`; artifact-kind allowlist `[FINDINGS, RECOMMENDATION]`; workflow binding `workflow.assessment` (R2) |
| CaseTypeDefinition | `case-type.enhancement` | `mutating: true`; requires delivered baseline; workflow binding `workflow.enhancement` |
| WorkflowDefinition | `workflow.assessment` | body = `assessment-v1.bpmn20.xml` (R7 shape) |
| WorkflowDefinition | `workflow.enhancement` | body = `enhancement-v1.bpmn20.xml` (baseline resolution first step) |
| RuleDefinition | `rule.case-type-classification` | DMN, hit policy UNIQUE, no-match ⇒ `UNDETERMINED` (R5) |
| RuleDefinition | `rule.conditional-artifacts` | DMN, hit policy COLLECT; e.g. `personal_data = true ⇒ template.dpia` (R6) |
| TemplateDefinition ×~8 | `template.assessment-findings`, `template.assessment-recommendation`, `template.delta-doc`, `template.impact-analysis`, `template.dpia`, … | revised from v0 sources per the catalog audit where available |
| RubricDefinition ×~4 | `rubric.assessment-findings`, `rubric.assessment-recommendation`, `rubric.delta-doc`, `rubric.impact-analysis` | scoring gates for the new artifact kinds |
| PromptDefinition set | per persona×operation for the two shapes | delimited-data framing unchanged (T1-a) |

All loaded by `CatalogSeedLoader` v4 as published, checksummed, semver'd DefinitionAssets —
immutable once published (Principle I).

## Reused Structures (no change)

| Structure | Role in this phase |
|---|---|
| `case_definition_snapshot` (V3) | Pins the confirmed type's definitions at `Planned`; conditional-artifact DMN output merged into its expected-artifact set **before** pinning (R6) — this is where FR-014/015 live |
| `trace_link` (V6, polymorphic edges) | `DERIVES_FROM` edges from every Enhancement delta/impact ArtifactRevision to its specific pinned baseline ArtifactRevision (R4); written in the same tx as the artifact |
| `execution_package` / `artifact_revision` | Baseline = the Feature's most recent Delivered package's pinned revisions; Assessment packages carry only allowlisted kinds |
| `decision` / `audit_entry` | Confirm/override decisions, blocked read-only writes, guard conflicts, baseline resolution record |
| `knowledge_injection_snapshot` (V14) | New case types' persona operations inject knowledge exactly as Phase 3 (FR-018) |

## Relationships (summary)

```
problem_submission (proposed/confirmed type) ──confirm──▶ case_instance (pins case-type defs
        │                                                   + conditional artifacts into snapshot)
        └─ Decision (override provenance)
feature (aggregate_version, active_mutating_case_id) ◀──guard──  mutating case create/terminal
enhancement delta/impact artifact_revision ──DERIVES_FROM (trace_link)──▶ baseline artifact_revision
```

## Validation Rules (from requirements)

- Assessment write path: artifact kind ∉ allowlist ⇒ refuse + audit, case continues (FR-006).
- Enhancement confirm: Feature has no Delivered package ⇒ reject/redirect at confirm (FR-010).
- Delivery gate: package must satisfy the snapshot's expected set including conditional additions
  (FR-015) — existing completeness check, no new mechanism.
- Guard: acquire only via the versioned predicate; release only in the terminal transition's
  transaction (FR-012/013).
