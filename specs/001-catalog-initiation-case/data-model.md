# Data Model: Catalog Spine + Initiation Case Type

**Date**: 2026-07-06 · **Feature**: [spec.md](spec.md) · **Research**: [research.md](research.md)

Conventions (apply to every table unless noted):
- `id` UUID PK · `workspace_id` FK NOT NULL with RLS policy (exception: global catalog rows use a reserved system workspace) · `created_at`/`created_by` audit columns.
- Definition-referencing columns always come in pairs: `<x>_definition_id` + `<x>_definition_version` (`(id, version)`, never a mutable pointer) — Constitution Principle I.
- Audit-relevant tables (`audit_entry`, `event_outbox`, runtime executions) are INSERT-only at the DB-grant level (T6-a).

## 1. Tenancy & organization

| Entity | Key fields | Notes |
|---|---|---|
| **Workspace** | `id`, `name`, `status` | Hard isolation boundary (Principle IV). Root of all RLS. |
| **Project** | `workspace_id`, `name` | Groups versions/features. |
| **ProjectVersion** | `project_id`, `label` | Organizational version of a project. |
| **Feature** | `project_version_id`, `name`, `agg_version` (bigint) | `agg_version` is the optimistic-concurrency token (FR-016): incremented on mutating-case attach; case creation compares-and-swaps. |

**Invariant**: at most one active mutating Case per Feature — enforced by CAS on
`feature.agg_version` + partial unique index on `case_instance(feature_id) WHERE status NOT IN
('Delivered','Cancelled','Suspended') AND mode = 'mutating'`.

## 2. Catalog (definitions)

| Entity | Key fields | Notes |
|---|---|---|
| **DefinitionAsset** (supertype) | `id`, `key`, `version` (semver), `type`, `status` (Draft→Published→Deprecated), `locale` (default `en`, Q11), `body` JSONB, `checksum` (SHA-256, set at publish, T4-a), `published_at` | UNIQUE `(key, version)`. Immutable once `Published` — UPDATE forbidden by trigger + grant; new content ⇒ new version row. |
| **CaseTypeDefinition** | subtype | Initiation is the only instance in Phase 1. |
| **WorkflowDefinition** | subtype; `bpmn_xml` | Initiation sequential BPMN. |
| **PersonaDefinition** | subtype | 3 authored in Phase 1. Stateless by construction (no state fields exist). |
| **PlaybookDefinition** | subtype | 3 authored. |
| **TemplateDefinition** | subtype | 7 revised from v0 + 2 greenfield (Task Breakdown, Handover Record). v0 provenance kept via `derived_from_key`/`derived_from_version`. |
| **RuleDefinition** | subtype; `dmn_xml` | 1 authored: submission classification decision table. |
| **RubricDefinition** | subtype; criteria with `weight` + `critical` flag | Pass gate: weighted ≥ 80% AND no critical fail (FR-005). |
| **PromptDefinition** | subtype | Every prompt body embeds delimited-untrusted-data framing (T1-a, AD-12). |
| **CaseDefinitionSnapshot** | `case_instance_id`, `entries` JSONB `[{type, key, version}]`, `frozen_at` | Written once at Case `Planned` (AD-4); immutable; the sole definition-resolution source for the running case. |

**State machine (DefinitionAsset.status)**: `Draft → Published → Deprecated`. Only `Draft` is
editable. `Published` requires checksum computation. No transition back from `Published`.

## 3. Intake

| Entity | Key fields | Notes |
|---|---|---|
| **ProblemSubmission** | `workspace_id`, `form_data` JSONB, `sensitivity_tags` JSONB (T3-a), `classification` (from DMN), `classification_confirmed_by` (human, FR-002), `status` | Encrypted at rest (T3-b). Body is always data, never instructions (AD-12). |

## 4. Case & runtime spine

| Entity | Key fields | Notes |
|---|---|---|
| **CaseInstance** | `workspace_id`, `feature_id`, `submission_id`, `case_type` (def pair), `mode` ('mutating'/'assessment'), `status`, `token_budget`, `tokens_spent` | State machine below. |
| **WorkflowInstance** | `case_instance_id`, `engine_instance_id` | Correlates Flowable execution ↔ Case (E1.5). |
| **PersonaInvocation** | `case_instance_id`, `persona_definition_id+version`, `status`, `sequence_no` | One per persona step. |
| **OperationExecution** | `persona_invocation_id`, `prompt_definition_id+version`, `model_id`, `model_version`, `inputs` JSONB, `injected_knowledge` JSONB, `output_ref`, `output_hash`, `attempt_no` (1–3), `validation_result` JSONB, `tokens_used` | **Crown-jewel audit row** (Principle II). One row per generation attempt; replay target. INSERT-only. |
| **ActivityExecution** | `persona_invocation_id`, `activity_definition_id+version`, `status` | Internal decomposition trace. |
| **ActionItem** | `case_instance_id`, `action_definition_id`, `action_definition_version`, `payload` JSONB | Direct definition reference (R4/Q1) — NOT transitively through ActivityExecution. INSERT-only. |

**State machine (CaseInstance.status)**:
`Submitted → Classified → Planned → Running ⇄ Waiting → Delivered`
plus `Running → Suspended` (token budget breach, FR-012; resume is explicit governance action)
and `Running → Escalated` (validation failed after 3 generations, FR-005; human resolves →
`Running` or `Cancelled`). Snapshot pinning happens exactly at `Planned` (FR-003).
Every transition writes `event_outbox` + `audit_entry` in the same transaction (FR-007).

## 5. Artifacts & delivery

| Entity | Key fields | Notes |
|---|---|---|
| **Artifact** | `case_instance_id`, `template_definition_id+version`, `artifact_type` | Logical artifact. |
| **ArtifactRevision** | `artifact_id`, `revision_no`, `storage_ref` (S3 key), `content_hash` (SHA-256), `produced_by_operation_execution_id` | Content in object storage; hash chain starts here. Corrections = new revision (regenerate), never in-place edit (Principle II). |
| **ExecutionPackage** | `case_instance_id`, `manifest` JSONB (member artifact revisions + hashes), `manifest_hash`, `status` | `manifest_hash` = SHA-256 over ordered member hashes (SC-005). |
| **HandoverRecord** | `execution_package_id`, `contents_index` JSONB, `submission_ref`, `definition_snapshot_ref`, `artifact_hashes` JSONB, `decision_log_ref`, `owner_name`, `next_action` | Full-provenance set per clarification Q4 (FR-008). All six fields NOT NULL. |

## 6. Governance & audit

| Entity | Key fields | Notes |
|---|---|---|
| **Decision** | `case_instance_id`, `decision_type` (D1–D4), `decided_by`, `rationale`, `inputs_ref` | Human/rule decisions (classification confirm, escalation resolution). |
| **AuditEntry** | `workspace_id`, `subject_type/subject_id`, `action`, `actor`, `tx_time`, `details` JSONB | Same-tx with the change it describes; INSERT-only grants (T6-a). |
| **EventOutbox** | `aggregate_type/id`, `event_type`, `payload` JSONB, `published_at` | Transactional outbox → event store (AD-6); relay marks published. |

## 7. Traceability edges (AD-7)

| Entity | Key fields | Notes |
|---|---|---|
| **trace_link** | `from_type/from_id`, `to_type/to_id`, `link_type` (e.g. `PRODUCED_BY`, `DERIVES_FROM`, `SATISFIES`) | Generalized polymorphic edge; future graph projection reads these verbatim. `PRODUCED_BY` from ActionItem resolves to an ActionDefinition `(id, version)` (R4). |
| **dependency** | same shape, `dep_type` | Cross-artifact dependencies. |

## 8. Observability

| Entity | Key fields | Notes |
|---|---|---|
| **KpiSample** | `workspace_id`, `metric` (`rubric_first_pass_rate` \| `package_completeness` \| `case_cost_tokens`), `case_instance_id`, `value`, `at` | Feeds minimal dashboard (FR-015); `case_cost_tokens` doubles as Q10 billing telemetry later. |

## Validation rules (from spec FRs)

1. Definition UPDATE where `status='Published'` → rejected (trigger + grant) [FR-003, P-I].
2. Case may not leave `Planned` without a complete CaseDefinitionSnapshot [FR-003].
3. OperationExecution insert requires all snapshot fields non-null (prompt ver, model id+ver, inputs) [FR-006].
4. `attempt_no` ≤ 3 enforced; 3rd failure transitions Case → `Escalated`, never a 4th generation [FR-005].
5. Rubric evaluation: `weighted_score ≥ 0.80 AND critical_failures = 0` ⇒ pass [FR-005].
6. `tokens_spent + next_estimate > token_budget` ⇒ transition to `Suspended` before the call [FR-012].
7. HandoverRecord: all six provenance fields NOT NULL [FR-008].
8. Package delivery requires `manifest_hash` verification pass [FR-008/SC-005].
9. RLS: every query path carries `workspace_id`; leakage suite asserts zero cross-tenant reads [FR-010].
10. Persona code path has no API to invoke another persona, approve output, or write knowledge — enforced by module boundaries (ArchUnit) + absence of such endpoints [FR-017].
