# Implementation Plan: Governance & Review Gates

**Branch**: `005-governance-review-gates` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-governance-review-gates/spec.md`

## Summary

Phase 5 promotes review/approval from inline steps to first-class machinery in a new `governance`
module: reusable **Review-Gate / Approval-Gate BPMN subprocesses** (callActivities with durable
user tasks) that every D4 decision flows through, each producing a `gate_instance` + Decision
record capturing who/what/when/on-what-information/why. Reviewer interaction is strictly
**comment-and-regenerate** (Q4): REQUEST_CHANGES triggers the existing persona regeneration path,
yielding a new immutable ArtifactRevision plus a deterministic delta report — no in-place edit
path exists. The **regeneration policy** (Q3) runs a DMN over `DERIVES_FROM`/`SATISFIES`
`trace_link` edges to find *direct* dependents of a revised approved artifact; their gates reopen
only after an `impact_assessment` row (reason/scope/risk) is captured, each reopen a recorded
Decision; transitive dependents are flagged for manual review. **Advisory SLA timers** (Q9) ride
BPMN boundary timers on gate user tasks and versioned EscalationPolicy definitions with role
chains — they notify and escalate visibly, never auto-route. Audit hardening: periodic
**hash-chaining** of the audit stream (`audit_chain_segment` sealed hourly, tamper detection),
workspace retention policy config (NFR-5, 7-year floor — configurable longer, never shorter),
role-scoped package access grants (T3-d), and a
documented **DR drill** meeting RPO ≤ 15 min / RTO ≤ 1 h (NFR-8). All gate lifecycle events emit
full payloads to the outbox (FR-019) — completing the projection-sufficient set Phase 7 needs.

## Technical Context

**Language/Version**: Java 21 (Gradle toolchain; build runs via Docker `gradle:8.10-jdk21`)

**Primary Dependencies**: Spring Boot 3.3.5, Flowable 7.0.1 (gate subprocesses as callActivities,
durable user tasks, boundary timer events for advisory SLAs, DMN for reopen candidates), Spring
Data JPA, Flyway; java-diff-utils (new — deterministic delta reports); Notification via Spring
events persisted as in-app notifications surfaced in the workspace UI (no email/webhook in v1)

**Storage**: PostgreSQL 16 (RLS-enforced, `d2os_app` role); new governance tables V17, audit
chain V18, package grants V19, workspace retention columns V20; WAL archiving config for the DR
drill (RPO ≤ 15 min)

**Testing**: JUnit 5 + Testcontainers; existing suites re-run unchanged (SC-008); new
GateFlowIT, CommentRegenerateIT, ReopenPolicyIT, EscalationTimerIT (Flowable async timer test
support), AuditChainIT (tamper detection), PackageAccessIT, retention config test; DR drill is a
documented runbook executed against the compose stack, results recorded in quickstart-results

**Target Platform**: Linux server (single-region cloud), local dev via docker-compose

**Project Type**: Modular-monolith web service (adds a 12th bounded-context Gradle module:
`governance`)

**Performance Goals**: Gate open/decide adds no AI calls (human-latency dominated); SLA timers
are engine-scheduled (no polling); hash-chain sealing is an hourly job (fixed interval per
FR-013) sized to seal an hour's audit volume in seconds; reopen-candidate DMN over edges resolves
in one indexed query pass

**Constraints**: No in-place editing path for AI artifact content anywhere (Q4/Principle II);
reopen requires impact assessment BEFORE gate reopen (Q3/Principle V — never a silent side
effect); SLA firing must never decide/route a gate (Q9); audit stream stays append-only —
hash-chaining adds tamper-evidence on top (T6-b); gate events must carry projection-sufficient
payloads (Phase 7 dependency); D4 actor ≠ redaction/draft actor where the spec requires
non-self-satisfiable gates

**Scale/Scope**: 1 new module, ~5 new tables (V17–V19) + workspace columns (V20), 2 gate BPMN
subprocess definitions + 1 reopen DMN, EscalationPolicy as a new definition-asset type
(content-level), ~9 new API endpoints, 6 new IT suites + DR runbook

## Constitution Check

*GATE: evaluated against Constitution v1.0.0 before Phase 0 research; re-checked after Phase 1 design.*

| # | Principle | Status | How this plan complies |
|---|---|---|---|
| I | Definition/Instance Immutability | ✅ PASS | Gate subprocesses and EscalationPolicies ship as published, versioned DefinitionAssets (new `type` values on the existing supertype — content, not schema forks); gates in flight pin the policy version they opened with. Regeneration produces new ArtifactRevisions; prior completions are never mutated. Workflow definitions that embed gates ship as new versions (initiation-v3 etc.); running cases keep pinned versions. |
| II | Reproducible, Bounded AI (NON-NEGOTIABLE) | ✅ PASS | Q4 enforced structurally: the gate surface offers APPROVE / REJECT / REQUEST_CHANGES(comments) only — there is no content-write endpoint for AI artifacts. Regeneration re-enters the standard persona execution path (snapshots, rubric, knowledge injection snapshot), so every regenerated completion is replayable; delta reports are deterministic diffs of stored revisions. |
| III | System of Record Integrity | ✅ PASS | Gate instances, decisions, impact assessments, escalation activations, chain segments, and grants are relational rows written with AuditEntry in the same transaction; gate lifecycle events go through the existing outbox (no dual write). Reopen candidates come from the existing polymorphic `trace_link` edges (AD-7) — no new relationship tables. |
| IV | Workspace Isolation & Provenance | ✅ PASS | All new tables carry `workspace_id` + standard RLS; escalation role chains resolve within workspace role assignments; package access grants narrow (never widen) visibility inside a workspace. EscalationPolicy versions carry provenance like every definition. |
| V | Default-Deny Security & Auditable Gates | ✅ PASS | This phase *is* Principle V's implementation: every D4 decision is a tamper-evident record (who/when/on-what/why); reopen is gated on a documented impact assessment — never automatic; SLA timers are advisory by construction (timer path has no transition authority); hash-chaining makes retroactive tampering detectable; package access is role-scoped by default. |

**Post-design re-check (after data-model + contracts)**: no violations introduced — the gate
decision endpoint accepts only the three verbs; the reopen endpoint 409s without an impact
assessment; timer handlers emit events and notifications only. **GATE: PASS.**

## Project Structure

### Documentation (this feature)

```text
specs/005-governance-review-gates/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.yaml         # Phase 1 output (delta over Phase 4 api.yaml)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

New 12th module `governance`; extensions in casecore (audit chain), artifacts (grants), tenancy
(retention), orchestration (gate BPMN + workflow v3s):

```text
governance/                    # NEW MODULE: Governance bounded context (BC-7)
├── src/main/java/com/d2os/governance/
│   ├── GateInstance.java / GateInstanceRepository.java   # runtime gate state
│   ├── GateService.java               # open/decide; decision verbs only (Q4)
│   ├── DeltaReportService.java        # deterministic diff between revisions
│   ├── reopen/                        # ReopenCandidateService (DMN over edges),
│   │   └── ...                        #   ImpactAssessment entity + ReopenService (Q3)
│   ├── escalation/                    # EscalationPolicyResolver (versioned defs),
│   │   └── ...                        #   EscalationActivation, TimerFiredHandler (advisory, Q9)
│   ├── notification/                  # NotificationService (in-app only in v1; no email/webhook)
│   └── api/                           # GateController, ReopenController, EscalationController
├── src/main/resources/db/migration/V17__governance_gates.sql
orchestration/
├── src/main/resources/processes/review-gate.bpmn20.xml    # NEW: callActivity subprocess
├── src/main/resources/processes/approval-gate.bpmn20.xml  # NEW: userTask + boundary timer(s)
├── src/main/resources/dmn/reopen-direct-dependents.dmn    # NEW: edge kinds → reopen candidates
├── src/main/java/com/d2os/orchestration/
│   ├── GateTaskBridge.java            # engine userTask ↔ governance GateInstance sync
│   └── RegenerationDelegate.java      # REQUEST_CHANGES → persona re-execution path
│   # initiation-v3 / assessment-v2 / enhancement-v2 workflow versions embed the gate callActivities
casecore/
├── src/main/java/com/d2os/casecore/audit/
│   ├── AuditChainSealer.java          # hourly segment sealing (T6-b, FR-013)
│   └── AuditChainVerifier.java        # full-chain verification + tamper alert
├── src/main/resources/db/migration/V18__audit_hash_chain.sql
artifacts/
├── src/main/java/com/d2os/artifacts/access/PackageAccessService.java  # role-scoped grants (T3-d)
├── src/main/resources/db/migration/V19__package_access_grants.sql
tenancy/
├── src/main/resources/db/migration/V20__workspace_retention.sql       # columns only (NFR-5)
catalog/                       # + seed: gate SubprocessDefinitions, EscalationPolicy definitions
ops/
└── dr-drill.md                # NEW: backup/restore runbook — RPO ≤ 15 min, RTO ≤ 1 h (NFR-8)
app/
└── src/test/java/com/d2os/app/
    ├── GateFlowIT.java                # US1: D4 through gates, full decision record
    ├── CommentRegenerateIT.java       # US2: no edit path; new completion + delta report
    ├── ReopenPolicyIT.java            # US3: direct-only reopen, impact assessment gate
    ├── EscalationTimerIT.java         # US4: timer fires → notify/escalate, never routes
    ├── AuditChainIT.java              # US5: tamper detection 100%
    └── PackageAccessIT.java           # US5: role-scoped access, no workspace-wide default
```

**Structure Decision**: `governance` is a real bounded context (the plan's BC-7 "Governance Svc")
with its own tables, API, and definitions — not an orchestration detail. Engine coupling is
confined to `GateTaskBridge` in orchestration (same pattern as `PersonaStepDelegate`), so
governance stays engine-agnostic. Migrations continue the ordered stream: **V17** (governance),
**V18** (casecore audit chain), **V19** (artifacts grants), **V20** (tenancy retention columns).

## Complexity Tracking

> No constitution violations — table intentionally empty. (The 12th module is the named
> Governance bounded context, demonstrated need per the phase plan.)

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| — | — | — |
