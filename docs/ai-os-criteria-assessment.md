# Applying the "AI Operating System — Unified Project Intelligence Architecture" criteria to D2OS

**Date**: 2026-07-12 · **Source**: uploaded vision doc *"AI Operating System – Unified Project
Intelligence Architecture"* · **Subject**: this repository (D2OS).

This measures the current repo against every criterion in that document — **Met / Partial / Gap**, with
evidence — and charts how to adopt the gaps. It is a conformance map, not a rewrite: D2OS already
realizes a large share of the vision with its own stack, and one criterion **directly conflicts** with
D2OS's constitution and must be reconciled before it's "applied."

## Headline verdict

D2OS is **~55–60% aligned** with the vision *as a documentation-&-delivery operating system*. The
vision's **conceptual pillars are nearly all present** — a canonical Single-Source-of-Truth data model,
reusable versioned libraries, a rules/decision engine, bounded AI participation, reproducible memory, a
graph read-model, and a UI. What's **materially missing** is (1) **source-code understanding** (the
graph models delivery artifacts, not classes/functions/APIs), (2) **Git-commit-as-transaction** linkage
into the graph, (3) a rich **Project Intelligence Dashboard / graph explorer**, and (4) explicit
**cross-project "the OS learns from every project"** mechanics. The vision's specific tool list
(Obsidian, GoRules ZEN, Ponytail, Claude-Mem, Graphify, LinkML) maps onto capabilities D2OS **already
has via different tech** — treat those as capability requirements, not mandatory tool swaps.

---

## ⚠️ The one hard conflict — resolve this first

> **Vision**: *"The Project Graph Database becomes the Single Source of Truth for both humans and AI
> agents."*
>
> **D2OS Constitution, Principle III**: *"The relational database is the single permanent system of
> record. Any graph representation is a derived, rebuildable CQRS projection — it MUST NOT be treated as
> a source of truth."*

These are **mutually exclusive as literally stated.** D2OS deliberately makes the graph a *rebuildable
projection* to avoid dual-write inconsistency — a sound, defensible choice. Recommendation: **satisfy
the vision at the *intelligence/navigation* layer, not the *authority* layer.** The graph becomes the
unified *query and reasoning surface* ("single source of truth" for *navigation and insight*), while the
relational store remains the *system of record*. This preserves auditability and reproducibility while
delivering everything the vision actually wants the graph to *do* (traceability, gap detection, impact
analysis, semantic navigation). Adopt the vision's intent; keep D2OS's integrity guarantee.

---

## Conformance scorecard

### Vision & Operating Modes
| Criterion | Verdict | Evidence / Gap |
|---|---|---|
| Living, interconnected Project Graph | **Partial** | `projection` module builds `graph_node`/`graph_edge` across CASE, SUBMISSION, ARTIFACT_REVISION, PACKAGE, DEFINITION_VERSION, KNOWLEDGE_ITEM_VERSION, OPERATION_EXECUTION, GATE, FEATURE, PROJECT, REQUIREMENT, TEMPLATE. Missing: code, Git, AI-session, and memory node types. |
| Graph = Single Source of Truth | **Conflict** | See above — D2OS makes it a projection by design. Reframe as the *navigation* SoT. |
| **Generation Mode** (vision → software) | **Partial** | D2OS drives vision → **documentation/delivery artifacts** (cases, packages), not arbitrary source code. Scope is narrower than the vision. |
| **Analysis Mode** (discover gaps in existing projects) | **Partial** | Has gap detection **inside D2OS's own domain**: dependency-cycle detection (`CycleDetector`), payload-sufficiency `projection_gap`, rebuild-equivalence, influence analytics. Missing: analyzing **external/arbitrary** codebases for missing tests, orphaned docs, tech debt. |

### Core Objectives (15 bullets)
| Objective | Verdict | Where |
|---|---|---|
| Build reusable knowledge / rules / workflows / task & decision templates / reasoning / coding standards | **Mostly Met (Partial on 2)** | `catalog` = immutable, semver **Definitions** (Workflow, Persona, Playbook, Template, Operation, Rule) distributed by copy-on-subscribe. Reasoning-pattern & coding-standard libraries are the weak spots. |
| Share knowledge across AI agents | **Met** | `knowledge` module + governed retrieval; the AI Gateway injects scoped knowledge per execution. |
| Preserve implementation intelligence | **Partial** | `replay` (byte-identical) + injection snapshots preserve *what happened*; reusable *reasoning patterns* aren't extracted. |
| Continuously synchronize every artifact | **Partial** | Event outbox → projector keeps the graph in sync; Git artifacts aren't in scope. |
| Detect gaps automatically / recommend next best action | **Partial** | Gap detection yes; a "next best action" recommender is **not** present. |
| Continuously improve itself via accumulated project knowledge | **Gap** | No cross-project learning loop (see Final Objective). |

### AI OS Core Library (the reusable foundation)
**Verdict: strong Partial.** D2OS's `catalog` + Global Library + copy-on-subscribe **is** exactly the
"reusable library every project inherits, customizes, extends, overrides" concept — with real
provenance and versioning. Present: Knowledge, Document (Template), Metadata, Relationship, Decision,
Rule, Workflow, Task, Validation, Testing (as test tiers), Agent (Personas), Graph Schema libraries.
**Gaps**: an explicit **Behavior/Reasoning/Review** library (engineering-standards-as-data), and
**Dashboard Components** as reusable assets.

### The 7 Workflow Milestones (tool → capability mapping)
| # | Vision milestone (tool) | D2OS capability | Verdict |
|---|---|---|---|
| 1 | Knowledge Foundation (**Obsidian**) | `knowledge` module: governed KnowledgeItem lifecycle + pgvector retrieval; Template Definitions | **Partial** — has a knowledge base + doc templates; no Obsidian-style semantic doc graph / metadata authoring UX |
| 2 | Rule & Decision Architecture (**GoRules ZEN**) | Flowable **DMN** decision tables (`case-type-classification`, `conditional-artifacts`, `reopen-direct-dependents`, `submission-classification`) + BPMN workflows | **Strong** — a real reusable rules/decision engine already governs planning, gaps, approvals, routing |
| 3 | AI Behavior (**Ponytail**) | `persona` (stateless personas + AI Gateway, scope guard, budget) | **Partial** — bounded AI execution exists; **coding-standards / engineering-guidance-as-data** library is missing |
| 4 | AI Reasoning & Memory (**Claude-Mem**) | `replay` harness + knowledge-injection snapshots (reproducible memory of inputs) | **Partial** — memory of *what was injected/produced* yes; the Knowledge→Reasoning→Decision→Task→Session→Code relationship chain and reusable reasoning patterns are **not** modeled |
| 5 | Source Code Understanding (**Graphify**) | — | **Gap** — the graph has no repo/module/class/function/API/test nodes; this is the biggest missing pillar |
| 6 | Unified Project Graph (**LinkML**) | `projection` canonical `graph_node`/`graph_edge` schema with node/edge types + provenance | **Partial** — one canonical, versioned, traceable graph schema exists; it doesn't yet *merge in* code + Git graphs |
| 7 | Project Intelligence Dashboard (**HTML**) | `studio` (Thymeleaf + htmx) + traceability/influence/cycle APIs | **Partial** — a UI + traceability/impact/influence endpoints exist; the rich executive dashboard, interactive graph explorer, semantic search, and doc-to-code navigation are **not** built |

### Git Integration
**Verdict: Gap.** The vision wants each commit as an immutable transaction linking Goal → Requirement →
Decision → Task → Session → Files → Tests → Review → Release, in the graph. D2OS has an **event outbox
+ tamper-evident audit hash-chain** (a strong analog for "immutable event history") but **no Git graph**
and no commit-to-entity linkage. This pairs with Milestone 5 (code understanding).

### Project Guidance flow & Final Objective
| Criterion | Verdict | Note |
|---|---|---|
| Guided vision → … → operation → continuous improvement | **Partial** | D2OS guides submission → planned → running → gates → delivered with validate/gap/rules at each step — a faithful realization for *delivery*, narrower than *vision → arbitrary software*. |
| Validate relationships / detect gaps / apply reusable rules / reuse knowledge at every step | **Mostly Met** | Gates, DMN rules, RLS, snapshot pinning, knowledge injection do exactly this. |
| **Every completed project enriches the OS; new projects start with accumulated intelligence** | **Gap** | The *mechanism* exists (Global Library + copy-on-subscribe + promotion gates), but there is **no closed loop** that harvests reusable patterns/reasoning from finished work back into the Core Library across projects. |

---

## Adoption roadmap (how to actually "apply this criteria")

Phased, each phase independently valuable and consistent with D2OS's constitution:

- **Phase A — Reconcile the model (design, ~days).** Ratify the "graph = navigation SoT, relational =
  system of record" reconciliation. Extend the LinkML-equivalent canonical schema
  ([`specs/007`](../specs/007-graph-projection-analytics)) with the new node/edge *types* the later
  phases need (code, Git, session, reasoning). Additive to `projection`.
- **Phase B — Source-code understanding (Milestone 5, the biggest gap).** Add a code-graph ingester
  that projects repo → module → package → file → class → function → API → test nodes and their
  dependency/def-use edges into `graph_node`/`graph_edge` (still a rebuildable projection). Unlocks
  Analysis Mode over real code, doc-to-code traceability, and impact analysis.
- **Phase C — Git-as-transactions (Git Integration).** Project commits as immutable transaction nodes
  linking Goal/Requirement/Decision/Task/Session/Files/Tests/Review/Release. Reuse the outbox +
  audit-chain pattern; join to the code graph from Phase B.
- **Phase D — Reasoning & Memory library (Milestone 4).** Model the
  Knowledge→Understanding→Reasoning→Decision→Task→Session→Implementation→Code chain and *extract
  reusable reasoning patterns* (not just history) into a new Definition type in `catalog`.
- **Phase E — Behavior/coding-standards library (Milestone 3).** Add engineering-standards-as-data
  (coding standards, review rubrics, architecture principles) as versioned Definitions the personas
  consume — closing the "Ponytail" gap.
- **Phase F — Project Intelligence Dashboard (Milestone 7).** Build the executive dashboard +
  interactive graph explorer + semantic search + doc↔code navigation on the unified graph (extend
  `studio`, or a dedicated read-only UI over the projection APIs).
- **Phase G — Self-improvement loop (Final Objective).** A cross-project harvester that promotes proven
  patterns/reasoning/standards from completed projects into the Global Library through the existing
  default-deny promotion gates — so each project starts richer.

**Sequencing rationale**: B and C are the highest-value gaps and unblock Analysis Mode + true
end-to-end traceability; D/E/F build the "intelligence" surface; G closes the learning loop. A is the
cheap prerequisite that keeps everything constitution-compliant.

## Recommendation / next step

1. **Decide the reconciliation** (Phase A) — confirm the graph stays a projection (recommended) or
   accept the dual-write risk of graph-as-authority. Everything else depends on this ruling.
2. **Scope decision** — apply the criteria *within* D2OS's documentation-&-delivery remit (extend the
   existing platform, phases A–G), or broaden D2OS into a general vision→software AI-OS (a much larger,
   different program). Recommended: the former.
3. If you want to proceed, the natural path is the same spec-kit flow used for feature 008: turn Phases
   A–C into a new feature spec (`/speckit-specify`) → plan → tasks → implement. I can start that.

*Tool-mapping note:* the vision's named tools (Obsidian/GoRules/Ponytail/Claude-Mem/Graphify/LinkML)
are satisfiable by D2OS's existing stack (knowledge module / Flowable-DMN / personas / replay+snapshots
/ a new code-graph ingester / the canonical projection schema). Swapping to the literal tools would be a
re-platform with no capability gain — not recommended unless a specific tool feature is required.
