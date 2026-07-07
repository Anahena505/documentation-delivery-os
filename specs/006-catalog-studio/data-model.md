# Data Model: Catalog Studio (Admin UI)

**Feature**: 006-catalog-studio · **Date**: 2026-07-07
Delta over the Phase 1–5 schema (V1–V20). Migrations: **V21** (catalog), **V22** (governance).
All new tables carry `workspace_id uuid NOT NULL` + standard RLS + `d2os_app` grants. Research
references: [research.md](research.md) R2–R6.

## New Entities

### LibrarySubscription (V21, catalog)

The audited record of a copy-on-subscribe act (R6, T4-d).

| Field | Type | Notes |
|---|---|---|
| id | uuid PK | |
| workspace_id | uuid NOT NULL | the subscribing workspace (RLS scope) |
| source_definition_id | uuid NOT NULL | FK → definition_asset (Global-library row, zero-UUID workspace) |
| copied_definition_id | uuid NOT NULL | FK → definition_asset (the workspace's own copy) |
| subscribed_by | text NOT NULL | user id |
| created_at | timestamptz NOT NULL | |

**Invariants**: UNIQUE (workspace_id, source_definition_id) — subscribe once per version;
`copied_definition_id`'s checksum equals the source's checksum (copy-integrity, asserted by the
T4 suite); written in the same transaction as the copy row + AuditEntry.

## Modified Entities

### definition_asset (V21, catalog — CHECK swap + columns only)

| Change | Notes |
|---|---|
| status CHECK widened | `('Draft','InReview','Published','Deprecated')` — adds `InReview` (R2). The V3 immutability trigger is untouched (fires on Published as before). |
| + derived_from_id | `uuid NULL REFERENCES definition_asset(id)` — authoritative in-catalog fork provenance (R4, FR-012). Existing `derived_from_key/version` text columns remain for v0-import lineage. |
| + copied_from_id | `uuid NULL REFERENCES definition_asset(id)` — copy-on-subscribe provenance to the Global source (R6, FR-015). |
| + GIN index | on `case_definition_snapshot.entries` (jsonb_path_ops) — exact-pin containment queries for the impact report (R5, SC-005). |

**Extended state machine** (per definition version row):

```
Draft ──submit-for-review──▶ InReview ──D4 gate PASS (+ architecture board if MAJOR)──▶ Published
  ▲                             │ gate REJECT / withdraw                                    │
  └─────────────────────────────┘                                              Published ──▶ Deprecated
```

- `Draft`: editable in the studio; not resolvable by cases (resolution filters `Published`).
- `InReview`: content frozen (app-enforced + content-hash pinned at submission); a gate decision
  or withdrawal moves it back to `Draft` or on to `Published`.
- `Published`: immutable (V3 trigger); checksum + `published_at` stamped in the publish
  transaction; semver ordering enforced against the prior published `(type, key)` version.
- `Deprecated`: status flip only, requires a generated impact report id at confirmation (R5).

### gate_instance (V22, governance — polymorphic subject)

| Change | Notes |
|---|---|
| + subject_type | `text NOT NULL DEFAULT 'ARTIFACT_REVISION'` — now also `DEFINITION_VERSION` |
| + subject_id | `uuid NULL` — backfilled from `subject_artifact_revision_id`; the artifact-specific column becomes a deprecated read alias |

Publish gates are ordinary GateInstances: `gate_type=APPROVAL`,
`subject=('DEFINITION_VERSION', definition_asset.id)`, `inputs_ref` carrying the delta-report id
(prompt diff for prompt/persona; canonical-JSON diff otherwise) and the pinned content hash.
MAJOR publishes require a second APPROVAL gate bound to the `architecture-board` role — both
Decisions first-class (R3).

## Computed Views (no storage — R5)

| View | Sources | Serves |
|---|---|---|
| Deprecation impact report | non-terminal `case_instance` × `case_definition_snapshot.entries` exact-pin containment · published definitions whose body references the key · `library_subscription` downstream copies | FR-010 / SC-005 (zero pinned dependents omitted) |
| Compatibility matrix | `compatible_with` range declarations in definition bodies (content convention) evaluated against published versions | FR-011 |

## Relationships (summary)

```
definition_asset (Draft) ──submit──▶ gate_instance('DEFINITION_VERSION') ──PASS──▶ Published
definition_asset ──derived_from_id──▶ definition_asset       (fork lineage, R4)
definition_asset ──copied_from_id──▶ definition_asset        (Global source, R6)
library_subscription: workspace ── source (Global row) ── copy (workspace row)
case_definition_snapshot.entries ⊇ {type,key,version} ──▶ impact report rows (computed)
```

## Validation Rules (from requirements)

- Draft edit: refused unless status `Draft` (FR-001/R2); `InReview` content-hash mismatch at
  publish ⇒ publish refused (tamper guard).
- Publish: requires PASS on the D4 gate (+ architecture-board gate iff MAJOR semver diff);
  duplicate `(type, key, version)` or checksum conflict ⇒ rejected with the conflict surfaced
  (FR-006/007/018 — the UNIQUE constraint is the backstop).
- Deprecate: requires a freshly generated impact-report id; flip + AuditEntry in one transaction
  (FR-010/017).
- Fork: always records `derived_from_id`; forking a Deprecated source allowed, resurrect never
  (fork is a new independent version) (FR-012).
- Subscribe: copies within one transaction (copy row + subscription + AuditEntry); Global rows
  are never writable by workspaces; copies never re-point (FR-013–015).
- Studio actions role-gated: Catalog Owner owns draft→publish lifecycle (Q8/FR-009);
  architecture-board role decides MAJOR gates.
