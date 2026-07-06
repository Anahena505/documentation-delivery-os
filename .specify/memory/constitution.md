<!--
Sync Impact Report
Version change: [TEMPLATE] → 1.0.0
Modified principles: N/A (initial ratification)
Added sections:
  - Core Principles (I–V)
  - Additional Constraints (technology & security)
  - Development Workflow
  - Governance
Removed sections: none (template placeholders replaced)
Templates requiring updates:
  - .specify/templates/plan-template.md ⚠ pending (verify Constitution Check section references these 5 principles)
  - .specify/templates/spec-template.md ⚠ pending (verify no conflicting scope assumptions)
  - .specify/templates/tasks-template.md ⚠ pending (verify task categories cover audit/versioning/security work)
  - .claude/skills/**/SKILL.md ⚠ pending (no agent-specific renames required at this time)
Follow-up TODOs:
  - TODO(RATIFICATION_DATE): confirmed as project kickoff date 2026-07-06; revisit if a formal stakeholder sign-off date differs.
-->

# D2OS Constitution
<!-- D2OS: Documentation & Delivery Operating System -->

## Core Principles

### I. Definition/Instance Immutability
Every executable concept (Workflow, Persona, Playbook, Template, Operation, Rule) MUST exist
as an immutable, semantic-versioned Definition, separate from its runtime Instance. Definitions
are never mutated in place — a change produces a new version. Running Cases pin to the
Definition versions frozen into their `CaseDefinitionSnapshot` at the `Planned` state and MUST
NOT auto-upgrade; migrating a running Case to a newer PATCH version requires an explicit,
recorded governance action. Runtime inheritance or step-overriding of a parent Workflow is
forbidden; forking a Definition for divergence MUST preserve provenance to its origin.
**Rationale**: auditability, reproducibility, and safe catalog evolution all depend on a
running Case being fully reconstructable from recorded Definition versions, never from
"whatever the Definition currently says."

### II. Reproducible, Bounded AI Participation (NON-NEGOTIABLE)
AI-generated output MUST be reproducible from recorded inputs: every AI execution snapshots
its prompt, model identity/version, and the exact Knowledge injected, per execution. AI
participation is bounded by the decision-authority ladder (D1–D4: AI drafts → rules route →
humans decide) — no AI-authored (D3) output crosses a stage boundary without explicit human
or rule-based validation. Humans MUST NOT edit AI-drafted artifact content in place; corrections
happen via gate-comment-and-regenerate, keeping the original AI output an intact, immutable
record. Personas are stateless: they never call each other, never approve their own output,
and never write to the Knowledge layer directly. Problem-submission text is always treated as
data, never as instructions, to close the primary prompt-injection surface.
**Rationale**: D2OS compiles auditable execution plans, not autonomous actions; every AI
contribution must be traceable to specific inputs and gated by an accountable human decision.

### III. System of Record Integrity
The relational database is the single permanent system of record. Any graph representation is
a derived, rebuildable CQRS projection — it MUST NOT be treated as a source of truth, and it
MUST be fully reconstructable from relational data alone. Cross-entity relationships (trace
links, dependencies) are modeled through generalized, polymorphic edge tables rather than
one-off foreign-key-pair tables per relationship type, so the graph projection stays lossless
and additive as new relationship types appear.
**Rationale**: avoids dual-write inconsistency risk while preserving analytical/graph
flexibility; generalized edges keep the migration surface stable as the catalog grows.

### IV. Workspace Isolation & Provenance-Preserving Evolution
A workspace is a hard tenant-isolation boundary. Global-library content is distributed to a
workspace by copy-on-subscribe, carrying explicit provenance, so a workspace's snapshot never
silently changes underneath it. Inherited or forked template/library content is never
discarded — it is classified (adopted / needs-revision / gap) and extended only through
catalog governance. Extending existing structure is preferred over introducing parallel or
speculative structures ahead of demonstrated need.
**Rationale**: protects prior investment and tenant safety simultaneously; provenance chains
let the system explain "where did this come from" for every artifact a workspace holds.

### V. Default-Deny Security & Auditable Governance Gates
Cross-boundary data movement (e.g., promoting workspace-confidential knowledge to the global
library) is default-deny: content is blocked from promotion until it clears an automated
sensitivity/PII pre-filter followed by human redaction and an explicit governance-role
approval gate. Every gate decision (approval, rejection, re-open) MUST produce a tamper-evident
audit record capturing who, when, under what information, and why. Re-opening a previously
gated artifact requires a documented impact assessment before the gate reopens — it is never a
silent, automatic side effect of a downstream check.
**Rationale**: D2OS serves regulated/compliance-sensitive case types; every trust-sensitive
transition must be explainable after the fact, not just technically reversible.

## Additional Constraints

- **Contract-first interfaces**: machine-facing contracts (starting with the API Specification)
  are authored as native, lintable formats (e.g., OpenAPI YAML) rather than prose, so parallel
  development and change-cost stay low. Other design artifacts remain prose unless a specific
  need to make them machine-readable is demonstrated.
- **Provider-agnostic AI access**: LLM access goes exclusively through the AI Gateway
  abstraction; no persona or service calls a model provider directly. Model identity and
  version are recorded per execution (reinforces Principle II).
- **Concurrency safety**: shared mutable aggregates (e.g., one active Case per Feature) are
  protected by optimistic concurrency (version/timestamp check), not pessimistic row locks,
  unless a specific case demonstrates lock-level contention that optimistic checks cannot
  handle.
- **Engine and stack choices are additive, not silently swapped**: when a chosen engine has a
  known conformance gap against a binding decision (e.g., DMN version), the gap is documented
  and a fallback adapter path is identified rather than quietly redefining the requirement.

## Development Workflow

- Every Architectural Decision (AD-*) recorded in the project's binding decisions register is
  treated as ratified input to planning and MUST be referenced by ID wherever a plan or task
  depends on it (traceability).
- Planning (`/speckit-plan`) MUST include an explicit Constitution Check against the five Core
  Principles before task breakdown; any violation must be justified in the plan's Complexity
  Tracking section or the design must change.
- Open questions blocking a phase MUST be resolved (ruling + evidence) before that phase's
  tasks are generated; non-blocking open questions may proceed with the default assumption
  explicitly flagged.
- Task generation (`/speckit-tasks`) MUST surface dedicated tasks for audit-trail, versioning,
  and security/redaction work wherever a feature touches Principles II, III, or V — these are
  not incidental to "the feature," they are the feature's compliance surface.

## Governance

This Constitution supersedes conflicting team practices and ad hoc conventions. Amendments
require: (1) a documented rationale for the change, (2) propagation to any dependent template
or command file that references the changed principle, and (3) a version bump following
semantic versioning — MAJOR for backward-incompatible principle removal/redefinition, MINOR
for a new principle or materially expanded guidance, PATCH for clarification/wording fixes.
All plans and reviews MUST verify compliance with this Constitution; unjustified complexity
must be simplified or explicitly justified. Use `d2os-implementation-plan.md` and
`d2os-implementation-prompt.md` for detailed runtime/architectural guidance beneath this
Constitution's principles.

**Version**: 1.0.0 | **Ratified**: 2026-07-06 | **Last Amended**: 2026-07-06
