# Research: Assessment + Enhancement Case Types

**Feature**: 004-assessment-enhancement-case-types · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. Zero-schema-change mechanics — where the "new case type" actually lives

**Decision**: A case type is entirely catalog content: a `CaseTypeDefinition` (binding workflow
key, expected artifact set, capability flags), a `WorkflowDefinition` whose body is the BPMN
resource, plus Template/Rule/Rubric/Prompt Definitions — all loaded as published DefinitionAssets
by `CatalogSeedLoader` (v4 seed set) into the **existing** `definition_asset` table. Case startup
resolves the confirmed type's definitions and pins them into `CaseDefinitionSnapshot` exactly as
Initiation does. `SchemaFreezeIT` asserts the `information_schema.tables` inventory is unchanged
from V14 (modulo nothing — V15/V16 add columns only).

**Rationale**: FR-016/SC-007 is the phase's headline proof (§16: extension by authoring). The
Phase 1 definition core was explicitly built for this — 8 definition-type tables with JSONB bodies
and `(key, version)` resolution — so the proof is that we *use* it, adding no parallel structure.

**Alternatives considered**: per-case-type tables or subclassed case entities — rejected
(precisely what §16 forbids and Constitution IV discourages); a `case_type` enum column — rejected
(the type is already a pinned definition reference; an enum would hardcode catalog content in
schema).

## R2. Read-only enforcement for Assessment (FR-005/006)

**Decision**: `CaseTypeDefinition` body carries a capability flag `mutating: true|false`
(Initiation/Enhancement true, Assessment false) plus an artifact-kind allowlist for read-only
types (`FINDINGS`, `RECOMMENDATION`). Enforcement sits in the **artifacts module write path**
(`ArtifactService.createRevision`): if the owning case's pinned snapshot says `mutating=false` and
the artifact kind is outside the allowlist — or the write would attach to another case's baseline
— the write is refused, an AuditEntry records the blocked attempt, and the pipeline continues
(the persona's step fails validation and revises toward allowed kinds; it does not kill the case).
The same flag drives the Q2 exemption (R3).

**Rationale**: The spec demands Assessment be read-only *by construction* — a persona prompt can't
guarantee that; the write path can. Putting the check where artifacts are born makes every future
read-only case type free (flag in the definition, zero code). Blocked-but-continue matches the
spec's acceptance scenario ("the attempt is blocked … and the case still completes").

**Alternatives considered**: DB-level enforcement (RLS/grants per case type) — rejected (case type
is definition content, not a DB principal; grants can't see snapshots); trusting playbook/prompt
authoring — rejected (not enforcement, just hope).

## R3. Q2 optimistic guard mechanics (FR-012/013)

**Decision**: V15 adds two columns to `feature` (tenancy): `aggregate_version bigint NOT NULL
DEFAULT 0` and `active_mutating_case_id uuid NULL`. `MutatingCaseGuard.acquire(featureId,
expectedVersion, caseId)` executes a single guarded UPDATE:
`SET active_mutating_case_id = :caseId, aggregate_version = aggregate_version + 1 WHERE id = :id
AND aggregate_version = :expected AND active_mutating_case_id IS NULL` — zero rows updated ⇒ HTTP
409 conflict with a clear message (never queue, never lock). Release happens in the **same
transaction** as the case's terminal transition (`Delivered`/`Cancelled`/`Failed`):
`SET active_mutating_case_id = NULL, aggregate_version = aggregate_version + 1 WHERE
active_mutating_case_id = :caseId`. Assessment case creation skips the guard entirely
(`mutating=false` from R2's flag).

**Rationale**: Q2's ruling verbatim — optimistic version check on the Feature aggregate at
creation time, not a pessimistic row lock. A guarded UPDATE is race-safe under READ COMMITTED
(two concurrent attempts serialize on the row write; exactly one sees the NULL slot), needs no
retry loop, and holds the row lock only for the statement, not the case lifetime.

**Alternatives considered**: JPA `@Version` on Feature — rejected (throws
`OptimisticLockException` on *any* concurrent feature write, coupling the guard to unrelated
updates; the explicit predicate encodes the actual invariant); partial unique index on
`case_instance(feature_id) WHERE status IN (…active…) AND mutating` — attractive but rejected
(mutating-ness lives in the pinned snapshot, not a column; adding a column duplicates definition
content into schema, against R1's grain).

## R4. Enhancement baseline resolution and trace-linking (FR-008–011)

**Decision**: `BaselineResolutionDelegate` runs as the first step of `enhancement-v1`: it resolves
the Feature's most recent **Delivered** ExecutionPackage and its ArtifactRevisions (exact pinned
revisions, not "latest"), records the baseline set on the case (audit + snapshot context), and
exposes baseline content to persona envelopes **as delimited data** (read-only context, same T1-a
framing). Every delta/impact ArtifactRevision the case produces gets a `DERIVES_FROM` `trace_link`
edge to the specific baseline ArtifactRevision it enhances (edge written in the same transaction
as the artifact row). Intake rejects an Enhancement submission whose Feature has no Delivered
package (FR-010) at the confirm step. A baseline artifact superseded or deprecated since delivery
still resolves — the reference is to the pinned revision — and the impact analysis template
receives the supersession/deprecation facts to surface (FR-011).

**Rationale**: Reuses AD-7's polymorphic edges exactly as Phase 1 built them (E1.7); pinning to
specific revisions is the same discipline as CaseDefinitionSnapshot (AD-4). Resolving at case
start (not per-persona) gives one consistent baseline for the whole case.

**Alternatives considered**: copying baseline artifacts into the new case — rejected (duplicates
the record, breaks provenance, and the spec forbids re-authoring); referencing "latest revision"
dynamically — rejected (a mid-case baseline change would make the case non-reproducible).

## R5. Case-type classification DMN + human confirm (FR-002–004, FR-019)

**Decision**: `case-type-classification.dmn` (a published RuleDefinition, executed by the embedded
Flowable DMN engine in the existing intake classify step) maps submission attributes
(`subject_exists`, `has_delivered_baseline`, `request_intent` …) to a proposed type with hit
policy **UNIQUE**; no rule matched ⇒ proposal `UNDETERMINED`. V16 adds columns to
`problem_submission`: `proposed_case_type text`, `confirmed_case_type text`,
`classification_status text` (`PROPOSED → CONFIRMED`), `classification_overridden boolean`.
`POST /submissions/{id}/case-type/confirm` records the human decision (Decision + AuditEntry in
the same transaction, original proposal preserved in the row) and only then does Case creation
proceed with the confirmed type. An `UNDETERMINED` proposal renders as "needs human choice" — the
confirm step is mandatory for every submission, so ambiguity is a UX state, not a special path.

**Rationale**: §9-D2 (rules route, humans decide) — the proposal is advisory, the human confirm is
the authority of record, and the audit question "why is this an Assessment case?" is answerable
from the submission row + Decision. UNIQUE hit policy makes genuine ambiguity explicit instead of
first-match-wins guessing (FR-004).

**Alternatives considered**: auto-create on high-confidence proposals — rejected (spec: confirm
before *any* Case is created); a separate classification table — rejected (columns on the
submission suffice; zero-new-tables posture).

## R6. Conditional artifacts folded into the snapshot (FR-014/015)

**Decision**: `conditional-artifacts.dmn` (hit policy **COLLECT**) runs at case start, after
confirm and **before** `CaseDefinitionSnapshot` pinning; its output rows (extra required artifact
template keys, e.g. DPIA iff `personal_data = true`) are merged into the snapshot's
expected-artifact set. The existing package-completeness check (E1.7/E1.9 — delivery requires the
expected set) then enforces FR-015 with **zero new code**: a case simply cannot deliver while a
conditionally required artifact is missing. The additions are visible in the snapshot (auditable
Case metadata, FR-019).

**Rationale**: The snapshot is already the frozen contract of "what this case must produce";
folding conditional requirements in before pinning makes them immutable for the case's lifetime
(Principle I) and reuses the delivery gate instead of adding a second completeness mechanism.

**Alternatives considered**: a `required_artifact` table — rejected (new table, forbidden);
checking conditions at delivery time — rejected (a submission edited mid-case could silently
change requirements; pin-at-start is the AD-4 discipline).

## R7. Workflow shapes for the two new types

**Decision**: `assessment-v1.bpmn20.xml`: intake context → subject analysis personas (reusing the
Phase 2 suite, parallel where independent) → findings consolidation → recommendation persona →
rubric gates → package (findings + recommendation kinds only) → deliver. `enhancement-v1.bpmn20.xml`:
baseline resolution delegate → delta-analysis personas (baseline as data) → impact-analysis
persona (traversing `DERIVES_FROM`/`SATISFIES` edges of the baseline) → rubric gates → package →
deliver. Both reuse `PersonaStepDelegate`, the escalation bridge, consistency-check subprocess,
and the knowledge-capture trigger (Phase 3 fires on their `Delivered` events for free — research
003/R6).

**Rationale**: New shapes, zero new execution machinery — the phase proves the platform executes
whatever the catalog describes. Reusing the Phase 2/3 delegates keeps FR-017/FR-018 (all
guarantees + knowledge injection) true by construction rather than by re-implementation.

**Alternatives considered**: one generic workflow parameterized by type — rejected (the shapes
genuinely differ; parameterizing them into one BPMN would be less readable than two authored
definitions, and definitions are cheap by design).
