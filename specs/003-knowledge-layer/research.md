# Research: Knowledge Layer

**Feature**: 003-knowledge-layer · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. Module placement — new `knowledge` bounded context

**Decision**: A new Gradle module `knowledge` (the 11th BC), exposing retrieval as an Open Host
Service. `persona` defines a `KnowledgeProvider` SPI (same pattern as the existing `persona/spi`
package); `knowledge` implements it; `app` wires the implementation. `knowledge` may depend on
`tenancy`, `catalog`, and `casecore` types, but `persona` never depends on `knowledge`.

**Rationale**: The phase plan names "Knowledge Retrieval Svc" as its own service with an OHS
boundary (E3.1), and the knowledge lifecycle (capture → curation → publish → deprecate) has a
governance and API surface disjoint from persona execution. Folding it into `persona` would fuse
two bounded contexts and create a cycle the moment orchestration needs capture delegates.
Constitution IV's "extend existing structure" preference is satisfied: this is a named BC with
demonstrated need, not speculative structure.

**Alternatives considered**: package inside `persona` — rejected (BC fusion, cycle risk, and the
capture pipeline has nothing to do with persona execution mechanics); package inside `catalog` —
rejected (KnowledgeItems are workspace content with a runtime lifecycle, not governed definitions
distributed by the catalog; only the Curator persona/rubric are catalog assets).

## R2. Per-workspace vector index partitioning (T2-b)

**Decision**: `knowledge_item` is **LIST-partitioned by `workspace_id`**, one partition per
workspace, created by the tenancy provisioning hook at workspace creation (same transactional
path that creates the workspace row). Each partition carries its own HNSW index on the `embedding
vector` column. The parent table carries the standard RLS policy (`app.workspace_id` session
setting) — partitioning and RLS are independent layers.

**Rationale**: T2-b's requirement is that similarity search be *structurally* incapable of
returning another workspace's items — with LIST partitions, an ANN scan is confined to the
caller's partition (partition pruning on the mandatory `workspace_id = ?` predicate), so even a
mis-scoped query cannot traverse foreign vectors. HNSW-per-partition also keeps recall/latency
stable per tenant instead of degrading as the global corpus grows. RLS stays on as the second
layer (defense in depth), consistent with every other table.

**Alternatives considered**: single table + RLS only — rejected (RLS filters rows *after* index
traversal; a policy bug would be a leak, and T2-b explicitly demands a partitioned index); hash
partitioning (fixed N) — rejected (co-locates multiple workspaces per partition, which defeats the
isolation argument); one physical table per workspace managed by the app — rejected (reinvents
declarative partitioning with more DDL surface and no pruning support).

## R3. Embeddings — AI Gateway `embed` operation

**Decision**: Extend `AiGatewayClient` with an `embed(EmbedRequest) → EmbedResult` operation
(text → float vector + model identity/version), implemented in `HttpAiGatewayClient` against the
provider's embeddings endpoint and in `StubAiGatewayClient` as a **deterministic hash-derived
vector** (seeded from content bytes) so Testcontainers ITs are reproducible without a provider.
Embedding happens once, at item publish (and re-embed on new version); the model id/version used
is recorded on the item version row.

**Rationale**: AS-5/Constitution constraint — LLM/provider access only via the AI Gateway, and
model identity must be recorded per execution (Principle II). Embedding at publish time (not at
query time per item) keeps retrieval read-only and fast; query-side embedding is one gateway call
per retrieval. Deterministic stub vectors keep replay and leakage suites free of network flakes.

**Alternatives considered**: local embedding library in-process — rejected (violates the
gateway-only rule and adds a native dependency); embedding lazily at first retrieval — rejected
(makes the first retrieval slow and pushes writes into the read path).

## R4. Scope lattice representation

**Decision**: Two-level lattice in v1, encoded as `scope_level` (`WORKSPACE` | `PROJECT`) +
`scope_ref` (workspace id or project id). Retrieval resolves the requesting operation's context
(workspace, project) and returns items whose scope is an **ancestor-or-equal** of that context
(workspace-scoped items visible to all projects in the workspace; project-scoped items visible
only inside their project), intersected with tag match and the persona's knowledge profile
(allowed tags/domains carried on the PersonaDefinition). Capture candidates are born
`PROJECT`-scoped and confidential; the D4 gate is what raises scope to `WORKSPACE`.

**Rationale**: This is the minimal lattice that expresses Q5's boundary ("project-confidential
until promoted") without inventing levels the spec doesn't need. Cross-*workspace* promotion (to
a global library) is explicitly out of scope until Phase 6's copy-on-subscribe machinery exists —
the lattice tops out at WORKSPACE in v1 and the design leaves `scope_level` extensible
(`GLOBAL` reserved, unreachable in v1).

**Alternatives considered**: full generalized lattice table (arbitrary DAG of scopes) — rejected
(speculative structure, Constitution IV); per-case scope level — rejected (nothing retrieves at
case granularity; candidates are per-case but scoping is project/workspace).

## R5. Injection point and snapshot write

**Decision**: `ExecutionEnvelopeBuilder` gains a knowledge slot: before prompt rendering it calls
the `KnowledgeProvider` SPI with (workspace, project, operation tags, persona profile), renders
returned items into the envelope **as delimited data** (same T1-a framing as submission text), and
hands the resolved `(item id, version)` list to `OperationExecutionRecorder`, which writes
`knowledge_injection_snapshot` rows **in the same transaction** as the `operation_execution` row.
The AI Gateway receives the caller's workspace id with the call and independently re-asserts that
every injected item's workspace matches before prompt assembly (T2-c) — mismatch → refuse + audit.

**Rationale**: Same-transaction snapshot is what preserves Principle II/III (an execution either
exists with its full knowledge provenance or not at all). Reusing the delimited-data framing keeps
knowledge on the data side of the injection boundary (AD-12). The gateway assertion is deliberately
redundant with R2's partitioning — T2-c requires the check at the injection seam, not only at
retrieval.

**Alternatives considered**: snapshot written asynchronously after execution — rejected (a crash
window would leave executions with unrecorded knowledge provenance, breaking replay); recording
only item ids without versions — rejected (FR-006/AD-1: `(id, version)`, never a mutable pointer).

## R6. Case-end capture trigger and process shape

**Decision**: A standalone **`knowledge-capture` BPMN process**, started by
`CaseDeliveredKnowledgeTrigger` — an outbox-event consumer that reacts to the Case reaching
`Delivered` — correlated by `caseInstanceId`. Process shape: capture service task (harvest
candidates from the case's artifacts/decisions) → deterministic pre-filter service task → Curator
persona service task (redaction draft, rubric-validated, reusing the `PersonaStepDelegate`
execution path) → **D4 user task** (workspace owner approve/reject) → publish or reject service
task. Escalation/wait semantics reuse Phase 2's `EscalationBridge` pattern.

**Rationale**: A standalone process keeps the initiation workflow definition untouched — running
and pinned cases are unaffected (Principle I), and Phase 4's new case types get capture for free
by emitting the same `Delivered` event, instead of every case-type workflow embedding a
callActivity. The D4 gate is a plain Flowable user task + Decision row in v1 — the first-class
Review/Approval-Gate subprocess is explicitly Phase 5; building it early would duplicate E5.1.

**Alternatives considered**: callActivity appended to initiation-v3 — rejected (requires a new
workflow version per case type per Phase, couples capture to authoring workflows, no benefit);
purely service-driven pipeline without BPMN — rejected (the human D4 wait state needs the engine's
durable user task + restart survival, same reason gates are engine-managed elsewhere).

## R7. Sensitivity/PII pre-filter — deterministic, not AI

**Decision**: `SensitivityPreFilter` is a **deterministic classifier**: pattern detectors (email,
phone, national-id/IBAN-like, credential/secret shapes) plus propagation of the intake field-level
sensitivity tags (T3-a, E1.3) carried on the source submission/artifacts. Output is
`prefilter_finding` rows (span, category, source) attached to the candidate; findings pre-populate
the Curator's redaction view, and fields tagged sensitive are **excluded by default** from the
candidate content before the Curator ever sees them (T3-c).

**Rationale**: The pre-filter is an assist gate whose job is recall on *known* shapes with an
auditable, reproducible verdict — a deterministic pass is replayable byte-for-byte and cannot
hallucinate a clean bill. The spec makes the human redaction step authoritative precisely so the
pre-filter doesn't need semantic judgment. An AI-assisted second-opinion pass can be added later as
an additional (never replacing) filter without schema change.

**Alternatives considered**: LLM-based PII detection — rejected for v1 (non-deterministic verdicts
in a compliance gate, cost per candidate, and the human step already covers semantic cases);
external DLP service — rejected (new vendor surface, AS-5 gateway-only posture, overkill at this
scale).

## R8. Deprecation flagging mechanics

**Decision**: `DeprecationService.deprecate(itemKey, version-range, reason)` — in one
transaction: set item status `DEPRECATED` (audited governance action + Decision row), and insert
one `knowledge_affected_execution` row per distinct `operation_execution` whose injection snapshot
references a deprecated version (derived by a single insert-select over
`knowledge_injection_snapshot`). Flags are their own append-only rows; snapshots and outputs are
never touched. New retrievals exclude `DEPRECATED` items by status predicate; in-flight envelopes
already built keep their snapshotted versions.

**Rationale**: FR-014/015/016 verbatim — discoverability requires a queryable flag artifact (an
operator lists affected executions per SC-007), and materializing flags at deprecation time makes
the flag itself auditable ("what was known-affected, when, why") rather than a moving query result.
Insert-select in the same transaction gives an exact, point-in-time affected set.

**Alternatives considered**: purely derived view (join snapshots × deprecated items at query time)
— rejected (the affected set silently changes as items change state; no audit anchor); mutating a
flag column on `operation_execution` — rejected (touches Phase 1 runtime rows and mixes concerns;
append-only side table is cleaner and RLS-scoped the same way).

## R9. Knowledge-influence KPI (E3.4)

**Decision**: `InfluenceEvaluationService` runs an explicit, on-demand **paired evaluation**: for a
target (item, operation definition, fixed input set), execute the operation twice through the
normal persona execution path — once with the item force-included, once with it force-excluded —
score both outputs against the same RubricDefinition version, and emit
`kpi_sample(metric='knowledge_influence', value = score_with − score_without)` tagged with the
item id/version. Both runs record full OperationExecution snapshots (they are real, replayable
executions, flagged `evaluation=true` so they never feed delivery). Items never injected anywhere
report `not-yet-measurable` (no sample emitted, API returns the state explicitly).

**Rationale**: The spec defines the metric as a with/without rubric delta (Clarifications); the
only honest way to produce it is actually running both arms under identical inputs and rubric
version. Reusing the persona execution path means the evaluation inherits snapshots, budget
enforcement, and audit for free. Emitting through the existing `kpi_sample` stream (V9) needs no
new observability schema.

**Alternatives considered**: observational estimation from historical runs (compare cases that
happened to use the item vs. not) — rejected (confounded, not attributable per item, spec asks for
the controlled delta); making the evaluation a delivery gate — rejected (spec: KPI is stewardship
reporting, it "does not itself gate delivery").

## R10. Retrieval query shape and budget

**Decision**: Retrieval = single SQL query per operation: mandatory predicates
(`workspace_id = :ws`, status `PUBLISHED`, scope ancestor-or-equal of the operation's context,
tag overlap with operation tags ∩ persona profile) ordered by vector similarity to the operation's
context embedding (embedded via one gateway `embed` call on the operation's task framing), capped
at `d2os.knowledge.max-items-per-operation` (default 5) and a per-item token ceiling before
envelope insertion (existing TokenBudgetGuard accounts injected tokens against the case budget).
Budget: ≤ 500 ms p95 at seeded scale, measured in KnowledgeRetrievalIT.

**Rationale**: Deterministic filters do the isolation/governance work; similarity only ranks
within the already-entitled set — so a ranking bug can mis-order but never widen entitlement.
Capping items + counting their tokens against the existing per-case budget keeps NFR-7 intact now
that prompts grow.

**Alternatives considered**: rerank via LLM — rejected for v1 (cost/latency per operation, no
requirement); no cap — rejected (unbounded prompt growth breaks the token budget posture).
