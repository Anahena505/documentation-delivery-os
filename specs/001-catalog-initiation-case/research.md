# Research: Catalog Spine + Initiation Case Type

**Date**: 2026-07-06 · **Feature**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md)

All Technical Context unknowns were resolved. Most decisions inherit evidence-backed rulings
already recorded in `d2os-implementation-plan.md` (FM.3a–FM.3k, verified 2026-07-06); they are
consolidated here so this feature's planning chain is self-contained. No NEEDS CLARIFICATION
markers remain.

## R1. BPMN/DMN engine

- **Decision**: Embedded Flowable 7, in-process; DMN decision tables on the same engine.
- **Rationale**: Apache 2.0 (no production license cost for a 1–3 dev team); embeds directly
  in a Spring Boot process — no Kubernetes/gRPC operational burden; keeps on-prem path open.
  Camunda 7 is in wind-down; Camunda 8 requires commercial licensing + SaaS-first posture.
- **Alternatives considered**: Camunda 8 (rejected: license cost, K8s-first self-managed path);
  Camunda 7 (rejected: EOL); bespoke state machine (rejected: re-implements BPMN semantics AD-2
  already mandates).
- **Caveat carried forward**: Flowable documents DMN 1.1/1.2 conformance, not the DMN 1.4 named
  in AD-2. Immaterial for decision-table-only usage; if literal 1.4 certification is ever
  contractually required, swap in Drools DMN (conformance level 3, DMN 1.1–1.4) behind the same
  business-rule-task interface — one adapter, not a re-architecture.

## R2. Language / framework / deployment shape

- **Decision**: Java 21 + Spring Boot 3.x modular monolith; one module per bounded context;
  single deployable.
- **Rationale**: Flowable embeds natively in Java/Spring; one deployable fits AS-1 team size;
  ArchUnit-enforced module boundaries + transactional outbox make later service extraction
  mechanical (PD-1).
- **Alternatives considered**: Microservices per bounded context (rejected: operational
  overhead unjustifiable at AS-1 staffing); Node/Python stack (rejected: Flowable embedding
  requires JVM; a REST-remote engine reintroduces the operational burden the embedded choice
  avoids).

## R3. Database & tenancy enforcement

- **Decision**: PostgreSQL 16 as sole system of record; `workspace_id` column on every row;
  row-level security policies; JSONB for definition bodies; append-only grants on audit stream.
- **Rationale**: RLS gives defense-in-depth for the hard workspace boundary (Principle IV)
  below the application layer; JSONB stores heterogeneous definition bodies without one table
  per shape; append-only grants make audit tampering a privilege violation, not just a code bug.
- **Alternatives considered**: App-layer filtering only (rejected: single missed `WHERE` clause
  = tenant leak); schema-per-tenant (rejected: operational explosion at catalog-definition
  granularity); graph-primary store (rejected by AD-6: dual-write risk).

## R4. Runtime granularity of Action records

- **Decision**: Action is definition-time only. `ActionItem` carries
  `(action_definition_id, action_definition_version)` directly; no persisted ActionExecution row.
- **Rationale**: Risk-based audit granularity — the crown-jewel audit unit is
  OperationExecution (where validation + AI snapshots live); an interposed ActionExecution row
  adds volume, not audit content. ActionItem already carries full provenance.
- **Alternatives considered**: Full ActionExecution runtime table (rejected: must independently
  satisfy AD-1 versioning discipline for zero incremental traceability).

## R5. Replay semantics (clarified this session)

- **Decision**: Recorded-output replay — the harness verifies each stored snapshot (prompt
  version, model version, injected knowledge, inputs) resolves to the exact recorded output;
  match is byte-identical because comparison is against what was stored, not a fresh model call.
- **Rationale**: AI providers do not guarantee cross-time determinism even at temperature 0;
  anchoring replay to stored outputs gives an objective, testable audit guarantee (SC-002)
  immune to provider drift.
- **Alternatives considered**: Deterministic re-call (rejected: brittle across model versions);
  semantic-similarity match (rejected: subjective threshold weakens the audit claim).

## R6. Validation gate & revise loop (clarified this session)

- **Decision**: Pass = weighted rubric score ≥ 80% AND no critical-criterion failure. Bounded
  loop: original + 2 automated revise attempts (3 generations), then human escalation.
- **Rationale**: 80%/no-critical is the industry-common two-part gate (quality floor + hard
  safety rail); 2 revises balances token cost against escalation load for a 1–3 person team.
- **Alternatives considered**: 90% gate (rejected: retry/cost inflation in v1 before rubric
  calibration data exists); 1 revise (rejected: escalates too eagerly); unlimited retry
  (rejected outright: unbounded cost + violates escalation principle).

## R7. Concurrency control on Feature aggregate

- **Decision**: Optimistic concurrency — version/timestamp check on the Feature aggregate at
  Case creation; one active mutating Case per Feature; Assessment (read-only) cases exempt.
- **Rationale**: Conflicting mutating cases are a rare user error, not routine contention;
  pessimistic locks held across a Case lifetime are a deadlock/scalability hazard.
- **Alternatives considered**: Pessimistic row lock (rejected: lock lifetime = case lifetime is
  untenable); no enforcement (rejected: contradicts spec FR-016).

## R8. Object storage & artifact integrity

- **Decision**: S3 API (AWS S3 in prod, MinIO in dev/tests) for artifact content; SHA-256 hash
  recorded per ArtifactRevision; package manifest hash-stamped over member hashes.
- **Rationale**: Content-addressable verification chain: artifact hash → manifest hash →
  package integrity stamp (SC-005) with ubiquitous tooling; MinIO keeps integration tests
  hermetic via Testcontainers.
- **Alternatives considered**: BLOBs in Postgres (rejected: bloats the SoR, complicates
  retention); filesystem paths (rejected: no integrity or replication story).

## R9. AI Gateway

- **Decision**: Thin internal Spring service wrapping provider SDKs; logs every call with
  prompt version, model id/version, parameters, token counts; enforces per-case token budget
  (breach → Case `Suspended`).
- **Rationale**: AS-5 mandates provider-agnostic access; a single choke point is where
  Principle II's snapshot requirements and NFR-7's budget enforcement live naturally.
- **Alternatives considered**: Direct SDK calls from persona code (rejected: scatters logging/
  budget logic, violates gateway-only constitution constraint); third-party LLM proxy
  (rejected for v1: new infra dependency for what one internal module does).

## R10. Embedding index

- **Decision**: pgvector, partitioned by `workspace_id` — provisioned in Phase 1 schema but
  exercised in Phase 3 (knowledge retrieval is out of this feature's scope).
- **Rationale**: One fewer system; workspace partitioning inherits the RLS tenancy story.
- **Alternatives considered**: Dedicated vector DB (rejected: second store with its own tenancy
  model, unneeded before Phase 3).
