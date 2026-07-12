# Feature Specification: Unified Project Intelligence

**Feature Branch**: `009-project-intelligence`

**Created**: 2026-07-12

**Status**: Draft

**Input**: Adopt the "AI Operating System — Unified Project Intelligence" criteria within D2OS's
documentation-&-delivery remit (see [`docs/ai-os-criteria-assessment.md`](../../docs/ai-os-criteria-assessment.md), phases A–G).

## Overview

D2OS already turns a business vision into governed, auditable delivery work. This feature makes the
project's **derived graph the single place to *navigate and reason about* everything** — documents,
rules, decisions, workflows, AI sessions, source code, and version-control history — so a person or an
AI agent can move from any project entity to every related one, discover what's missing, and see the
impact of a change.

**Ratified constraint (non-negotiable):** the graph remains a **rebuildable projection**. It becomes
the unified *navigation and intelligence* source of truth; the relational database remains the
permanent *system of record* (Constitution Principle III). Nothing here makes the graph authoritative,
introduces a second source of truth, or is anything other than reconstructable from recorded data.

All work is **additive within existing module boundaries** and consistent with the five constitutional
principles. It extends D2OS within its documentation-&-delivery remit — it does not turn D2OS into a
general vision-to-arbitrary-software platform.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One place to navigate all project intelligence (Priority: P1)

A project lead or an AI agent opens the project's unified view and can traverse from any entity —
a requirement, a decision, a document, a case, an artifact — to every entity related to it, across
what were previously separate concerns (knowledge, rules, decisions, delivery, code, history). The
graph is rebuilt from the system of record and always agrees with it.

**Why this priority**: This is the foundation every other capability navigates over. Without a unified,
type-complete, rebuildable graph, the code, commit, dashboard, and analysis stories have nothing to
stand on.

**Independent Test**: Rebuild the graph from the system of record and confirm it is byte-for-byte
equivalent to the incrementally-maintained one; from a chosen requirement, navigate to its decisions,
documents, tasks, and delivered artifacts in the unified view.

**Acceptance Scenarios**:

1. **Given** recorded project activity, **When** the unified graph is rebuilt from the system of
   record, **Then** it is provably equivalent to the live graph (no element added or lost) and no
   element lacks a link back to its source record.
2. **Given** any project entity, **When** a user or agent navigates from it, **Then** every related
   entity (across knowledge, decisions, delivery, code, and history) is reachable, in both directions.
3. **Given** the graph is unavailable or mid-rebuild, **When** it is queried, **Then** the last
   consistent version is served and the relational system of record remains fully usable — the graph
   is never required to answer an authoritative question.

---

### User Story 2 - Analysis Mode: find what's missing in the code (Priority: P1)

An engineer points D2OS at a project it manages and gets an automatic report of gaps: modules with no
tests, documents that describe code that no longer exists (and code with no describing document),
broken or cyclic dependencies, orphaned artifacts, and requirements with no implementing code. Each
finding names the exact entities involved and is navigable.

**Why this priority**: "Discover missing implementation / missing tests / broken dependencies /
orphaned documents" is the headline promise of Analysis Mode and the single biggest current gap — the
project graph today understands delivery artifacts but not source code.

**Independent Test**: Seed a project with a known planted gap (an untested module, a doc referencing a
deleted function, a dependency cycle); run analysis; confirm exactly those gaps are reported, each
pointing to the real entities, with no false positives on a clean control.

**Acceptance Scenarios**:

1. **Given** a managed codebase, **When** it is analyzed, **Then** its structure (repositories,
   modules, files, and their internal units and dependencies) is represented in the graph and joins to
   the requirements, documents, and decisions it relates to.
2. **Given** a module with no associated tests, **When** analysis runs, **Then** it is reported as a
   test gap, navigable to the module.
3. **Given** a requirement with no implementing code, or code with no describing document, **When**
   analysis runs, **Then** each is reported as a traceability gap.
4. **Given** a clean project, **When** analysis runs, **Then** zero false gaps are reported.

---

### User Story 3 - Commit-linked traceability (Priority: P2)

An auditor or engineer selects any version-control commit and sees it as an immutable transaction
linked to the business goal, requirement, decision, task, AI session, files, tests, review, and
release it belongs to — and can walk that chain in either direction ("why does this line exist?" ↔
"what shipped this requirement?").

**Why this priority**: Connecting version-control history to project meaning is what makes the
"immutable event history + semantic intelligence" promise real, and it completes end-to-end
traceability. It depends on US1's graph and pairs naturally with US2's code entities.

**Independent Test**: For a commit known to implement a specific requirement, confirm the graph links
it to that requirement (and onward to the decision and release), and that navigating from the
requirement reaches the commit and its files/tests.

**Acceptance Scenarios**:

1. **Given** a recorded commit, **When** it is projected, **Then** it appears as an immutable
   transaction node linked to the files it changed and the higher-level work it advances.
2. **Given** a delivered requirement, **When** a user traces it, **Then** the path to the commits,
   files, tests, and release that satisfied it is complete and navigable.
3. **Given** commit history, **When** re-projected, **Then** the result is identical (idempotent) and
   nothing about a past commit's linkage changes retroactively.

---

### User Story 4 - Project Intelligence Dashboard (Priority: P2)

A stakeholder opens a real-time dashboard over the unified graph: an executive health/progress view,
an interactive graph explorer, semantic search across all entities, requirement traceability,
document-to-code navigation, and impact/gap analysis — and can jump from any entity to every related
entity without leaving the view.

**Why this priority**: This is where the intelligence becomes usable by humans. High value, but it
presents what US1–US3 produce, so it follows them.

**Independent Test**: From the dashboard, search for a term, open a matching entity, and navigate to a
related requirement, its documents, and its implementing code; trigger an impact view for a proposed
change and confirm it lists the genuinely affected entities.

**Acceptance Scenarios**:

1. **Given** the dashboard, **When** a user searches semantically, **Then** relevant entities across
   all types are returned and each opens into the graph.
2. **Given** any entity in the dashboard, **When** the user requests its impact, **Then** the
   downstream entities a change would affect are shown.
3. **Given** the health view, **When** it loads, **Then** current gap counts, progress, and traceability
   coverage reflect the live projection.

---

### User Story 5 - Reusable reasoning & memory patterns (Priority: P3)

When AI agents do work, the system captures the chain from knowledge → understanding → reasoning →
decision → task → session → implementation → code as a related, queryable memory, and distills
**reusable reasoning patterns** (not just history) that future work can retrieve and apply.

**Why this priority**: Preserves and compounds implementation intelligence, but the platform is
valuable and navigable without it; it builds on the entities US1–US3 establish.

**Independent Test**: Complete a piece of work; confirm the reasoning chain is captured and linked to
its output; confirm a distilled reusable pattern is retrievable and can be applied to a new,
similar task.

**Acceptance Scenarios**:

1. **Given** completed AI work, **When** its record is inspected, **Then** the knowledge→…→code chain
   is captured and each step links to the next.
2. **Given** repeated similar work, **When** a reusable reasoning pattern is distilled, **Then** it is
   stored as a retrievable, versioned asset — reproducible from its recorded inputs.

---

### User Story 6 - Engineering-standards library (Priority: P3)

Coding standards, review rubrics, and architecture principles exist as **versioned, governed
Definitions** that AI personas consume when they produce and review work — so engineering guidance is
reusable, inheritable per project, and evolves through catalog governance rather than living in prose.

**Why this priority**: Closes the "reusable engineering guidance" gap and improves output quality, but
it is an enhancement to how existing personas already work.

**Independent Test**: Publish an engineering-standard Definition; run a persona operation; confirm the
pinned standard was applied and recorded; publish a new version and confirm running work still pins the
version it started with.

**Acceptance Scenarios**:

1. **Given** an engineering-standard Definition, **When** a persona produces or reviews work, **Then**
   the exact pinned standard version is applied and recorded per execution.
2. **Given** a workspace, **When** it inherits the default standards, **Then** it can customize/extend
   them through catalog governance without altering the global originals.

---

### User Story 7 - Cross-project self-improvement loop (Priority: P3)

Proven patterns, reasoning, and standards from completed work are harvested and **promoted into the
Global Library** through the existing default-deny promotion gates, so every new project starts with
the accumulated intelligence of previous ones — while each workspace's snapshot only changes by
explicit, provenance-carrying subscription.

**Why this priority**: This is the "OS continuously improves itself" capstone; it depends on the
reusable assets US5/US6 create and on the existing promotion-gate machinery.

**Independent Test**: Mark an asset as proven in one workspace; run it through promotion; confirm it
requires the default-deny gate approval, lands in the Global Library with provenance, and becomes
available to a second workspace only after that workspace subscribes.

**Acceptance Scenarios**:

1. **Given** a proven reusable asset, **When** it is proposed for promotion, **Then** it is blocked
   until it clears the automated pre-filter and an explicit governance approval.
2. **Given** a promoted asset, **When** a new project starts, **Then** it can subscribe to it and
   receives a provenance-carrying copy; its snapshot never changes silently underneath it.

### Edge Cases

- What happens when source code changes faster than analysis runs — does the graph clearly mark
  entities as stale rather than present a confidently wrong picture?
- How are deleted files/modules handled — are their nodes retired (with history preserved) rather than
  silently dropped, so commit-linked history stays intact?
- What happens when a document references code that cannot be found (or vice versa) — is that surfaced
  as an orphan finding, not an error?
- How large can the unified graph get before navigation/search must paginate, and is that limit
  explicit rather than a silent truncation?
- What happens to a distilled reasoning pattern or standard if its underlying inputs are later
  retired — is the pattern versioned so old work stays reproducible?
- Can the graph always be fully rebuilt from the system of record even after code/commit/session types
  are added — i.e., does the equivalence guarantee still hold for every new entity type?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST maintain a single unified graph that represents project entities across
  knowledge, rules, decisions, delivery, source code, and version-control history, and that is
  **fully rebuildable from the relational system of record** and provably equivalent to the
  incrementally-maintained graph.
- **FR-002**: The graph MUST NOT be treated as a source of truth; every node and edge MUST carry a
  reference back to the source record it was derived from, and the relational store MUST remain able to
  answer any authoritative question without the graph.
- **FR-003**: Users and AI agents MUST be able to navigate from any entity to every related entity, in
  both directions, across all represented concerns.
- **FR-004**: The system MUST represent the structure of a managed codebase — repositories, modules,
  files, their internal units, and the dependencies among them — as graph entities linked to the
  requirements, documents, and decisions they relate to.
- **FR-005**: The system MUST run an analysis that detects and reports gaps: missing tests, missing
  implementation for a requirement, documents with no corresponding code (and code with no document),
  broken or cyclic dependencies, and orphaned entities — each naming the specific entities involved.
- **FR-006**: Analysis MUST produce zero false gaps on a clean project (no fabricated findings).
- **FR-007**: The system MUST represent each version-control commit as an immutable transaction linked
  to the files it changed and to the higher-level work (goal, requirement, decision, task, session,
  tests, review, release) it advances; re-projection MUST be idempotent and MUST NOT alter past
  linkages.
- **FR-008**: The system MUST provide end-to-end traceability navigable in both directions between a
  requirement and the commits/files/tests/release that satisfied it.
- **FR-009**: The system MUST provide a real-time dashboard over the unified graph offering at least: an
  executive health/progress view, an interactive graph explorer, semantic search across all entities,
  requirement traceability, document-to-code navigation, and impact/gap analysis.
- **FR-010**: The dashboard MUST let a user navigate from any displayed entity to every related entity.
- **FR-011**: The system MUST capture the knowledge→understanding→reasoning→decision→task→session→
  implementation→code chain for AI work as linked, queryable memory, reproducible from recorded inputs.
- **FR-012**: The system MUST distill reusable reasoning patterns as retrievable, versioned assets that
  future work can apply.
- **FR-013**: Engineering standards (coding standards, review rubrics, architecture principles) MUST
  exist as versioned, governed Definitions that AI personas consume, with the pinned version applied
  and recorded per execution.
- **FR-014**: Proven reusable assets MUST be promotable into the Global Library only through the
  existing default-deny gate (automated pre-filter + explicit governance approval), landing with
  provenance; a workspace MUST receive promoted assets only by explicit subscription that never changes
  its snapshot silently.
- **FR-015**: Every new entity type added by this feature MUST preserve the rebuild-equivalence
  guarantee (FR-001) and MUST be workspace-isolated exactly like existing graph entities.
- **FR-016**: Stale or retired entities (e.g., deleted files) MUST be marked/retired with history
  preserved rather than silently dropped, so commit-linked history and reproducibility remain intact.
- **FR-017**: Where navigation, search, or analysis output is bounded (pagination, top-N), the bound
  MUST be explicit, never a silent truncation.

### Key Entities *(include if data involved)*

- **Unified Project Graph**: the rebuildable navigation/intelligence read-model spanning all concerns;
  derived, never authoritative.
- **Code Entity**: a repository, module, file, internal code unit, API surface, or test, with
  structural and dependency relationships, linked to the delivery/knowledge entities it relates to.
- **Commit Transaction**: an immutable version-control event linked to changed files and the work it
  advances; the immutable spine of history.
- **Gap Finding**: a reported deficiency (missing test/implementation/document, broken dependency,
  orphan) naming the specific entities involved.
- **Reasoning Record / Reusable Reasoning Pattern**: the captured knowledge→…→code chain for AI work,
  and the versioned reusable pattern distilled from it.
- **Engineering-Standard Definition**: a versioned, governed standard (coding/review/architecture) a
  persona consumes and pins per execution.
- **Promotable Asset**: a proven reusable pattern/standard/reasoning eligible for gated promotion into
  the Global Library with provenance.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A full rebuild of the unified graph from the system of record is 100% equivalent to the
  live graph (zero added or lost elements) across every entity type, including the new code, commit,
  session, and reasoning types.
- **SC-002**: 100% of graph elements reference a source record; the platform answers every
  authoritative question with the graph offline.
- **SC-003**: From any entity, a user or agent reaches any related entity in no more than a small,
  documented number of navigation steps, in both directions.
- **SC-004**: On a project with planted gaps, analysis reports 100% of the planted gaps and 0 false
  gaps on the clean control.
- **SC-005**: 100% of managed-codebase modules/files are represented and joined to the requirements or
  documents they relate to (or explicitly reported as orphaned).
- **SC-006**: 100% of commits are represented as immutable transactions; requirement-to-release
  traceability is complete for delivered requirements; re-projection is byte-identical.
- **SC-007**: A stakeholder can, from the dashboard and within a small number of interactions, go from
  a search term to a requirement to its documents to its implementing code, and view a change's impact.
- **SC-008**: 100% of AI work has its reasoning chain captured and reproducible; at least the intended
  set of reusable reasoning patterns is retrievable and applicable to new work.
- **SC-009**: 100% of persona executions record the pinned engineering-standard version applied; a new
  standard version never alters work already in progress.
- **SC-010**: 100% of promotions pass through the default-deny gate; no promoted asset reaches a
  workspace except by explicit, provenance-carrying subscription.

## Assumptions

- **Graph stays a projection** (ratified). "Single source of truth" is realized as the *navigation and
  intelligence* surface only; the relational database remains the system of record.
- **Scope is D2OS's delivery remit**: "managed codebase" means the repositories D2OS delivers/governs
  for a workspace (including its own), not arbitrary third-party projects. Source-code analysis begins
  with the project's primary language(s)/repositories and is extensible to more later; multi-language
  breadth beyond the initial set is out of scope for the first delivery.
- **Additive within existing module boundaries**: new entity types, analysis, dashboard, and libraries
  extend the existing catalog, projection, knowledge, persona, governance, and studio contexts; no
  bounded context is split or replaced.
- **Existing machinery is reused**: the graph projection + rebuild-equivalence guarantee, the immutable
  Definition catalog + copy-on-subscribe + default-deny promotion gates, workspace isolation, and the
  bounded-AI gateway are the substrate this feature builds on rather than reinventing.
- **AI participation stays bounded and reproducible** (Principle II): reasoning capture and pattern
  distillation record their inputs and never let AI output cross a stage boundary unvalidated.
- Version-control history is available to the system for the managed repositories (read access to
  commit metadata and file changes).
