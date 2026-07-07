# Research: Full Persona Suite + Parallel Execution

**Feature**: 002-full-persona-parallel · **Date**: 2026-07-07
All NEEDS CLARIFICATION items from the Technical Context are resolved below.

## R1. Parallel execution mechanism in embedded Flowable

**Decision**: BPMN `parallelGateway` fork/join in a new `initiation` **process version 2**, with every
parallel service task marked `flowable:async="true"` **and** `flowable:exclusive="false"`.

**Rationale**: Flowable's async job executor is already the Phase 1 execution substrate
(queue-and-resume, NFR-4). But Flowable's default `exclusive="true"` serializes async jobs *per process
instance* — with the default, the four specialist branches would fork structurally yet still run one at
a time. `exclusive="false"` is the documented switch that lets multiple jobs of the same process
instance run concurrently on the executor pool. The parallel join gateway gives exactly the required
barrier semantics: proceeds only when all inbound branches complete, discards nothing.

**Alternatives considered**:
- *Application-level fan-out* (`CompletableFuture`/`@Async` inside one service task): rejected — loses
  per-branch job persistence/retry, wait-state survival across restart (NFR-4), and per-branch engine
  history that FR-010 reconciliation and the audit trail depend on.
- *Four separate process instances + message correlation*: rejected — fragments Case identity, makes
  the join and snapshot pinning (AD-4) manual, and breaks the "one WorkflowInstance per Case" spine.
- *Inclusive gateway*: unnecessary — the four branches are unconditional in the canonical shape.

## R2. Worker pool sizing and AI-call capping (FR-003)

**Decision**: The bounded pool is Flowable's async executor thread pool, configured explicitly
(`flowable.process.async-executor` core/max pool size, queue capacity — externalized to
`application.yml` under `d2os.orchestration.*` defaults: core 8, max 16, queue 256). A separate
**semaphore in the AI Gateway** (`d2os.ai-gateway.max-concurrent-calls`, default 8) caps simultaneous
provider calls independently of pool size.

**Rationale**: Two distinct limits are being conflated by "worker pool": (a) how many engine jobs run
at once (DB-connection and CPU bound), (b) how many AI provider calls are in flight (rate-limit and
cost bound). Coupling them means tuning one breaks the other. The executor pool handles (a); a
gateway-level semaphore handles (b) and is also where per-workspace rate limits (R6) naturally attach.
Excess jobs queue in the executor's persisted job table — nothing is dropped (FR-003), which is
Flowable's out-of-the-box behavior.

**Alternatives considered**: single shared `ExecutorService` injected everywhere — rejected; the engine
already owns job scheduling, a second scheduler adds a coordination layer with no benefit.

## R3. Branch failure / escalation semantics at the parallel join (FR-004/005)

**Decision**: A persona branch that exhausts its bounded revise loop does **not** throw out of the
delegate (which would roll back the branch and poison the join). Instead the branch transitions into an
**escalation wait state**: the service task is followed by an exclusive gateway on the invocation's
validation outcome; the failure path enters an intermediate **signal catch event** (`escalation-resolved-<activityId>`)
scoped to that execution. `EscalationBridge` records the escalation (Case → `Escalated`, audit entry,
existing `EscalationController` surface) and, on human resolution (revised output accepted or manual
retry), signals that execution so the branch flows to the join. Sibling branches are unaffected
throughout; the join simply waits (FR-004).

**Rationale**: This reuses Phase 1's escalation model (Case-level `Escalated` status +
EscalationController) and BPMN-native wait states that survive restarts. Error boundary events with a
compensation subprocess were considered but compensations *undo* work — the requirement is the
opposite: retain sibling outputs and wait.

**Alternatives considered**: terminate-and-restart the parallel block on any failure — explicitly
contradicts the clarified requirement (siblings retained); Flowable's `failedJobRetry` alone — retries
the whole delegate blindly instead of the rubric-driven revise loop, and ends in a dead job needing ops
intervention rather than an auditable human decision.

## R4. Concurrency vs. Row-Level Security (Principle IV under parallel jobs)

**Decision**: Keep the Phase 1 mechanism unchanged — `PersonaStepDelegate` sets `WorkspaceContext` and
calls `WorkspaceRlsBinder.bindCurrentTransaction(workspaceId)` at the top of every job — and **verify**
it under concurrency: LeakageSuiteIT gains a two-workspace *concurrent* scenario (parallel blocks of
two cases from different workspaces running simultaneously on the shared executor pool).

**Rationale**: The binder issues `SELECT set_config('app.workspace_id', …, true)` on the *current
transaction's* connection — transaction-local by construction, so concurrent jobs on different pooled
connections cannot cross-contaminate. The risk to guard against is not the design but regression:
a future code path that touches the DB before binding. The concurrent leakage test makes that a CI
failure instead of a production incident.

**Alternatives considered**: connection-pool-level binding per thread — already exists
(`WorkspaceAwareDataSource` stamps at checkout for request threads); job threads bypass checkout-time
context, which is exactly why the per-transaction binder was built in Phase 1. No change needed.

## R5. Consistency-Check design (FR-006/007/008/019)

**Decision**: A reusable BPMN subprocess (`consistency-check`, invoked via callActivity after the
join) with two sequential steps:

1. **Deterministic cross-checks** (`DeterministicCrossChecks`, pure Java, no AI): parse the case's
   full artifact set (parallel-block *and* upstream sequential artifacts — FR-019) for
   (a) *dangling references* — an entity/endpoint/requirement id referenced in one artifact with no
   definition in the artifact that owns that id namespace, and (b) *attribute contradictions* — the
   same named attribute asserted with different values in two artifacts. Findings persist as
   `consistency_finding` rows (tier `DETERMINISTIC`), each linked to both artifacts via `trace_link`
   edges. Any deterministic finding → the subprocess routes to a blocking escalation (package cannot
   advance) — FR-007.
2. **Semantic coherence review** (`SemanticConsistencyOperation`): an AI operation executed through the
   standard persona-execution machinery (envelope, gateway, snapshot, rubric — Principle II) with a
   dedicated `consistency-reviewer` prompt + rubric scoring cross-artifact coherence. Findings persist
   as tier `SEMANTIC` (advisory). Advisory-only findings → recorded, surfaced on the Case, and the
   subprocess escalates for a human decision rather than hard-blocking — FR-008.

Artifacts declare their reference vocabulary via a lightweight convention: templates render a
machine-readable index block (`defines:` / `references:` lists of namespaced ids) that the
deterministic checker parses — no NLP required for tier 1.

**Rationale**: Splitting tiers keeps the blocking path 100% deterministic (auditable, replayable,
no false-positive AI blocks on delivery) while still catching semantic drift. Running the semantic
review as a normal AI operation means zero new reproducibility machinery.

**Alternatives considered**: single AI-only consistency pass — rejected (an AI false negative would
silently deliver contradictory packages; an AI false positive would block delivery on unexplainable
grounds — both violate Principle V's "explainable after the fact"); full artifact NLP diffing —
overkill; the template-rendered index block is cheap because we author the templates ourselves.

## R6. Per-workspace rate limits and budget rollup (FR-017, T5-b)

**Decision**: `WorkspaceRateLimiter` inside the AI Gateway: a DB-backed token/cost ledger — new
`workspace_budget` table (per-workspace period cap + consumed rollup, updated in the same transaction
as each OperationExecution's cost record) plus an in-memory sliding-window request-rate limiter
(requests/minute per workspace) in front of provider calls. Breach of the workspace *cost* cap
suspends the offending Case exactly like the Phase 1 per-Case budget (reuses
`TokenBudgetExceededException` → `Suspended` path); breach of the *rate* limit queues (delays) the
call rather than failing it.

**Rationale**: Cost must be durable and exact across restarts/instances → DB in the same transaction
(Principle III). Request-rate is transient smoothing → in-memory is fine for a single deployable
(modular monolith, PD-1); the interface isolates it so a distributed limiter can replace it if the
monolith is ever split.

**Alternatives considered**: Redis/bucket4j distributed limiter — rejected; adds infrastructure for a
single-node deployment (violates the "one deployable" simplicity of PD-1) with no current requirement.

## R7. Progress events ≤ 5 s apart (FR-011, NFR-3)

**Decision**: A `progress_event` table (append-only, workspace-scoped) + emitter API. Three sources:
(a) lifecycle emissions at every state/step boundary (delegate start/end, validation attempt, branch
fork/join); (b) a **heartbeat scheduler** (every 4 s) that emits a `HEARTBEAT` progress event for every
OperationExecution currently in `RUNNING`, guaranteeing the ≤5 s cadence even during a long AI call;
(c) escalation/suspension events. Client surface: `GET /cases/{id}/progress` supporting both plain
paging and long-poll (`waitAfter=<eventId>` with a 25 s server timeout) — Server-Sent Events is layered
on the same table later if a UI needs it.

**Rationale**: The 5 s bound is about *perceived liveness during a single long AI call* — only a
heartbeat can satisfy it, since a 8-minute provider call emits no natural boundary events. DB-backed
events keep the stream replayable/auditable and multi-consumer without a broker. Long-poll avoids
committing to SSE/WebSocket infrastructure ahead of an actual UI.

**Alternatives considered**: SSE from an in-memory event bus — rejected as primary (events lost on
restart, not auditable); Flowable event listeners alone — cover boundaries but not intra-call liveness.

## R8. Dual-state reconciliation job (FR-010, §20)

**Decision**: `ReconciliationJob` — a Spring `@Scheduled` sweep (default every 60 s, configurable)
that, per active Case, compares the Flowable runtime state (process instance + active executions +
job/deadletter queue) against the domain `CaseInstance.status` and `PersonaInvocation` records, using
the Case id business key as the join key. Disagreements are classified: *transient* (job queued but
domain still shows previous step — ignore under a grace window), *repairable* (engine finished a step
but a crash prevented the domain event — re-emit domain transition, audited as `RECONCILED`), and
*stuck* (dead-letter job or >grace-window divergence — mark Case `Escalated` + audit + progress event).
Every repair writes an AuditEntry; the job never silently mutates state.

**Rationale**: Parallel async jobs widen the window where engine history and domain state legitimately
diverge mid-transaction; the plan's E2.2 explicitly calls for this reconciler. A sweep with a grace
window is simple, idempotent, and restart-safe.

**Alternatives considered**: transactional outbox from engine events only — already exists for domain
events but cannot detect *missing* transitions; two-phase commit between engine and domain tables —
they share one datasource/transaction already for delegate work, so the residual divergence is crash
timing, which a sweep handles.

## R9. Attachment sandbox (FR-015, T1-d, US5)

**Decision**: Attachment pipeline in `intake`:
1. **Upload**: `POST /submissions/{id}/attachments` (multipart) → allowlist check (PDF, DOCX, XLSX,
   PPTX, TXT, MD, CSV, PNG/JPEG for OCR-less caption only), size cap (default 20 MB), stored to the
   object store under a workspace-scoped key; DB row `attachment` (status `RECEIVED`).
2. **Sandboxed extraction**: Apache Tika text extraction run in a **separate forked JVM process**
   (Tika's `ForkParser`) with a hard timeout (default 60 s), no network access needed, memory-capped —
   a hostile file can crash the fork, never the app. Unparseable/oversized/timeout → status `REJECTED`
   + audit + progress event (edge case in spec), never passed through raw.
3. **Summarization**: extracted text is summarized via the AI Gateway using the Phase 1
   delimited-untrusted-data framing (T1-a) — the summary operation's prompt marks content as data.
   Result stored as `attachment_summary` (status `SUMMARIZED`), snapshot-recorded like any operation.
4. **Consumption**: `ExecutionEnvelopeBuilder` gains an attachment-summary slot; personas only ever
   receive summaries. Raw object-store bytes are never read by envelope construction — enforced by
   module boundaries (persona module has no dependency on the attachment storage path) and asserted by
   AttachmentSandboxIT.

**Rationale**: Tika `ForkParser` is the standard JVM-native process-isolation approach — parser
exploits and pathological files (zip bombs, malformed PDFs) are contained in a disposable child
process. Running summarization through the gateway keeps AD-12's single enforcement pattern (data,
never instructions) and makes the summary replayable.

**Alternatives considered**: container-per-extraction (gVisor/Firecracker) — strongest isolation but
disproportionate infrastructure for Phase 2's single-node posture; in-process Tika with a
SecurityManager — SecurityManager is deprecated for removal in modern JDKs, not a real boundary.

## R10. Load-test approach (FR-012/013/014, NFR verification)

**Decision**: `LoadPostureIT` — a JUnit-driven load harness (tagged `@Tag("load")`, excluded from the
default test task, run via `./gradlew loadTest`): boots the full app against Testcontainers
Postgres/MinIO with `StubAiGatewayClient` configured with a **realistic latency profile** (configurable
per-call delay distribution, default 2–20 s) so concurrency pressure is real while AI cost is zero.
Drives 50 concurrent active cases in one workspace (plus 10 in a second workspace to exercise
isolation under load), asserts: zero stalled/dropped/deadlocked cases, per-operation p95 ≤ 10 min
(will be seconds with the stub — assertion parameterized against the profile), progress-event gap
≤ 5 s per running case, and throughput extrapolation ≥ 200 cases/month. Metrics captured from the
existing KPI tables + OperationExecution timestamps into a `load-report.md` artifact.

**Rationale**: A JUnit harness reuses the entire existing Testcontainers/IT infrastructure and runs in
CI on demand; the stub-with-latency isolates *system* concurrency behavior (executor, DB, RLS, joins)
from provider variance, which is what NFR-1/2/3 actually measure ("excluding human waits", and provider
latency is not ours to test). Gatling/JMeter drive HTTP front-doors well but the bottleneck under test
is the async pipeline, not the HTTP layer.

**Alternatives considered**: Gatling scenario against a deployed environment — valuable later for
front-door capacity, but requires a standing environment and real AI keys or a mock server; deferred to
a production-readiness phase.

## R11. Persona suite authoring & canonical workflow shape (FR-001, US1)

**Decision**: Author 13 operational personas as published DefinitionAssets (Knowledge Curator deferred
to Phase 3). The three Phase 1 placeholders (`persona-1/2/3`) are superseded by real definitions —
placeholder keys remain published v1 for replay of old cases; new cases pin only real keys. Canonical
Initiation **workflow v2** stage order (BPMN activity ids = persona keys):

```
start
→ intake-analyst                      (normalize, elicit gaps; wait-state if input needed)
→ business-analyst                    (BRD, stakeholder map, acceptance criteria)
→ product-functional-analyst          (functional spec, use-case catalog)
→ [Review-Gate(BRD) — Phase 1 treatment: validation + audit, first-class subprocess in Phase 5]
→ solution-architect                  (SAD, ADRs, ERD skeleton)
→ api-designer                        (OpenAPI skeleton — native YAML per Q7/FM.3g)
→ ∥ parallel gateway fork
    ├ security-architect              (threat model, security controls matrix)
    ├ ux-architect                    (IA, wireframe/flow spec)
    ├ data-architect                  (database design, data dictionary)
    └ infrastructure-engineer         (infra guide, environment matrix)
→ join → callActivity: consistency-check
→ qa-test-strategist                  (test plan, test case catalog)
→ risk-governance-officer             (risk register, RACI, compliance evidence index)
→ delivery-planner                    (WBS/Task Breakdown, ActionItems, dependencies, milestones)
→ technical-writer                    (glossary, release-notes skeleton, package front matter)
→ assemble-package → end
```

Each persona ships: PersonaDefinition (charter, competency, operation bindings, knowledge profile
stub — real retrieval lands Phase 3), PromptDefinition(s) with the Phase 1 typed-slot framing,
RubricDefinition (weighted criteria, critical flags, ≥80% pass), and its TemplateDefinitions revised
from the v0 audit sources (Appendix B mapping) — ~14 template revisions batched with their owning
persona. `PersonaStepDelegate` is reused unchanged (activity id → persona key), so the engine needs no
new delegate per persona.

**Rationale**: Matches the §7 canonical shape and §8 roster, keeps the sequential spine before/after
the parallel block (dependencies are real: specialists consume the architecture baseline), and honors
AD-11 (revise v0, don't discard). QA/Risk/Writer run after consistency so they document a coherent set.

**Alternatives considered**: running QA/Risk/Writer inside the parallel block — rejected; they consume
the *reconciled* artifact set, so they belong after the consistency join. Replacing the placeholder
definitions in place — violates Principle I (immutability); supersession is the constitutional path.

## R12. KPI additions (§KP, plan E2.x)

**Decision**: Extend `KpiEmitter` with two Phase 2 metrics: **gate cycle time** (wait-state entry →
resume, from progress events) and **regeneration rate** (revise-loop generations beyond first per
delivered package). Both computed from existing tables (OperationExecution attempts, progress events)
— no new counters to maintain — and exposed on the existing `MetricsController` dashboard payload.

**Rationale**: The phased plan assigns these to Phase 2 (E2 KPIs). Deriving from source-of-truth rows
rather than incrementing counters keeps them rebuildable (Principle III).
