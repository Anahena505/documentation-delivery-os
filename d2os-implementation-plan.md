# D2OS — Phased Implementation Plan
## Generated per §18 Output Contract of `d2os-implementation-prompt.md` v1.2 · Plan v1.1 FINAL · 2026-07-06

> **Status: ready to start.** All 12 §19 open questions (Q1–Q12) are resolved via best-practice research standing in for stakeholder ruling (FM.3a–FM.3k) — nothing left blocking Phase 0. Phase 0 is manual and can begin immediately; Phase 1 can begin the moment Phase 0's exit criteria are met.

---

## FM. Front Matter

### FM.1 Repository verification (supersedes §0 warning)

The §0 "Dependency status: UNVERIFIED / 404" warning is **stale**. Verified 2026-07-06:

- `https://github.com/Anahena505/documentation-framework` is **publicly accessible via git** (`git ls-remote` succeeds; branch `master`, tags `v0.4.0`, `v0.5.0`). The GitHub REST API returns 404 anonymously, which explains the original observation — use git, not the API, for verification.
- Contents: ~205 markdown templates across 19 topic folders, every file carrying frontmatter (`owner_role`, `doc_type`, `review_cadence`, `status`), plus adoption tooling (`tools/adopt.py`, `check_coverage.py`, `check_links.py`, `fill_progress.py`), mkdocs config, and docs CI.
- **The §0 greenfield fallback is NOT needed.** AD-11 applies in its primary form: the fork seeds the v0 Template Catalog.
- The v0 template audit (§0 step 4) has been **pre-completed** — full classification in Appendix B. Headline: of 66 §10 catalog entries, **19 ADOPTED · 32 NEEDS_REVISION · 15 GAP**. Two Phase-1-mandatory templates (Task Breakdown, Handover Record) are GAPs and must be authored greenfield in Phase 1.

### FM.2 Assumptions in force (restating §AS)

| # | Assumption | Plan impact if overridden |
|---|---|---|
| AS-1 | 1–3 devs + architect/owner; no parallel workstreams before Phase 2 | Larger team → Phases 2/3 and 5/6 can run concurrently; re-sequence dependency map |
| AS-2 | Stack not mandated; proposals in FM.4 are non-binding | Different engine (Q6) changes E1.5 and language choice |
| AS-3 | Effort in person-weeks, no calendar dates | — |
| AS-4 | Single-region cloud, logical tenant isolation; on-prem deferred | On-prem requirement forces embedded engine (reinforces Q6 recommendation) |
| AS-5 | LLM access only via AI Gateway; provider-agnostic | — |

### FM.3 Open-question status (§19)

All twelve questions — blocking **Q1–Q9** and non-blocking **Q10–Q12** — are now **RESOLVED — research-backed**, standing in for live stakeholder ruling per the user's direction to source best practice rather than wait on a decision meeting. Evidence for Q1/Q6 is in FM.3a/b, Q2–Q9 in FM.3c–FM.3j, Q10–Q12 in FM.3k. No open questions remain; the plan is unblocked end to end.

| Q | Blocks | Status | Ruling | Plan assumes |
|---|---|---|---|---|
| Q1 | Phase 1 | **RESOLVED** | Action is **definition-time only**; ActionItem carries a direct `(action_definition_id, version)` reference rather than a persisted ActionExecution row — see FM.3a | Yes — E1.4 table set reflects this |
| Q6 | Phase 1 | **RESOLVED** | **Embedded Flowable** for BPMN; DMN routing handled by the same engine for decision tables, with a noted version caveat — see FM.3b | Yes — stack proposal FM.4 |
| Q7 | Phase 2 | **RESOLVED** | Prose artifacts in v1, **except** API Specification which is native OpenAPI YAML — contract-first is confirmed standard practice. ERD/other models stay prose in v1 (evidence inconclusive either way) — see FM.3g | Yes |
| Q5 | Phase 3 | **RESOLVED** | Default-deny: project-confidential knowledge promotable only through automated sensitivity pre-filter + human redaction step by Knowledge Curator + workspace-owner D4 gate — see FM.3f | Yes — E3.3 |
| Q2 | Phase 4 | **RESOLVED** | One active mutating Case per Feature in v1, enforced via **optimistic concurrency** (version check on Feature aggregate), not a pessimistic row lock; Assessment cases exempt (read-only) — see FM.3c | Yes |
| Q3 | Phase 5 | **RESOLVED** | Post-approval upstream revision re-opens gates of **direct** `DERIVES_FROM`/`SATISFIES` dependents via DMN over edge tables, gated on a formal impact assessment step; transitive re-open manual — see FM.3d | Yes — E5.2 |
| Q4 | Phase 5 | **RESOLVED** | Gate-comment-and-regenerate only in v1 — this is now explicit 2026 regulatory-grade practice for AI content, not just an AD-9 inference — see FM.3e | Yes |
| Q9 | Phase 5 | **RESOLVED** | Advisory SLA in v1; timers fire notifications + escalation events, no auto-routing — staged advisory-before-enforced is a recognized trust-building pattern — see FM.3i | Yes |
| Q8 | Phase 6 | **RESOLVED** | Catalog Owner owns DMN authoring in v1 as a single accountable "model governance owner"; distinct Rules Steward when rule count > ~50 — see FM.3h | Yes |
| Q10 | non-blocking | **RESOLVED** | **Hybrid pricing**: fixed workspace/seat base + usage component metered per Case/Package, reusing the Case-cost telemetry already required by NFR-7 — see FM.3k | Yes |
| Q11 | non-blocking | **RESOLVED** | Single-language **content** in v1 (unchanged), but `DefinitionAsset` gains a `locale` field from Phase 1 so language becomes a schema dimension now, not a retrofit later — see FM.3k | Yes — E1.1 |
| Q12 | non-blocking | **RESOLVED** | Outbound-only ActionItem sync to Jira/ADO, no status sync-back, confirmed post-v1 (§16 extension point) — no in-system PM tool becomes a second source of truth — see FM.3k | Yes |

### FM.3a Q1 ruling — Activity/Action runtime granularity

**Ruling: Action is definition-time only.** ActionItem stores a direct reference to `(ActionDefinition.id, ActionDefinition.version)`; no separate `ActionExecution` runtime row is persisted.

Evidence:
- **Risk-based audit-granularity principle:** general audit-trail practice calls for "a risk-based logging policy [that] uses full-fidelity trails for crown-jewel systems, sampled or aggregated logs elsewhere," with completeness (who/what/when/why) captured "at appropriate granularity" rather than uniformly at the finest possible level. In D2OS, the crown-jewel audit unit is **OperationExecution** — that is where §9's validation pipeline runs and where AD-9's prompt/model/knowledge snapshot is recorded. Activity and Action are internal decomposition *within* an already-audited operation; persisting a full runtime row per Action adds trace volume without adding independent audit content.
- **Event-sourcing completeness principle:** the literature draws a sharp distinction between event-sourced systems where gaps are structurally possible versus designs where "every intent produces an immutable event" and "the event store is complete by construction." Applied here: the meaningful write is "ActionItem emitted" (already an immutable insert-only row per §4.3/§13). Interposing an ActionExecution row between ActivityExecution and ActionItem doesn't close a completeness gap — ActionItem already carries full provenance via its ActionDefinition reference — it only adds a table that must independently satisfy AD-1's "(id, version) not a mutable pointer" rule for no incremental traceability benefit.
- **BPMN/process-modeling literature:** there is no universal rule for task/subprocess decomposition depth — "granularity in process modeling depends on the model (modeling) purpose." D2OS's modeling purpose (§20: "hierarchy is internal to playbook authoring — hidden from end users") favors the coarser option.

**Net effect vs. original plan:** unchanged conclusion, with one addition — `E1.4` must ensure `ActionItem` carries `action_definition_id` + `action_definition_version` directly (not inferred transitively through ActivityExecution) so the graph-projection edge `PRODUCED_BY` (§15) resolves to a definition, not a missing runtime node.

### FM.3b Q6 ruling — BPMN/DMN engine selection

**Ruling: embedded Flowable**, confirmed against Camunda 8 on cost, deployment model, and team fit; one caveat on DMN version conformance is noted rather than silently absorbed.

Evidence:
- **Licensing/cost:** Camunda 8 requires a commercial license for production use — "no free community edition" — while Flowable's engine is Apache 2.0, free, with optional enterprise add-ons. For a 1–3-developer team (AS-1) this is a material recurring-cost difference, not just a preference.
- **Deployment model:** Camunda 8 defaults to SaaS; self-managed deployment requires Kubernetes and new team skills (gRPC, Kubernetes). Flowable "was designed for on-premises deployment from inception" and embeds directly into a Java/Spring process — no mandatory cloud dependency, keeping the on-prem path (§19 Q6 sub-note, AS-4) open at zero extra cost.
- **Vendor durability:** Camunda 7 (the license-compatible predecessor) is in wind-down — community updates only through ~Oct 2025, extended bug-fix support through ~2027 — with new customers steered to the commercial Camunda 8. Flowable remains independently maintained (active GitHub community, 5k+ stars) with no forced migration path. This reinforces rejecting the Camunda line for a small team that cannot absorb a licensing pivot mid-build.
- **Caveat surfaced by research (not in the original plan):** Flowable's own documentation states DMN conformance against **DMN 1.1/1.2**, not the DMN 1.4 named in **AD-2**. This is functionally immaterial for D2OS's actual DMN usage — §7 restricts DMN to decision tables for classification/approach-selection/escalation routing, and the 1.3/1.4 spec deltas are additive features (boxed-expression enhancements, first-class decision services) that D2OS's decision-table-only usage never invokes. **Recommendation:** treat AD-2's "DMN 1.4" as an intent statement satisfied by Flowable's DMN engine for the decision-table subset in use; if a stakeholder later needs literal 1.4-conformance certification (e.g. a customer contract requires it), pair Flowable's BPMN runtime with a separate DMN 1.3/1.4-conformant evaluator (Drools DMN, confirmed conformance level 3 for DMN 1.1–1.4) behind the same DMN business-rule-task interface — a swap that costs one adapter, not a re-architecture.

**Sources consulted (2026-07-06):**
- [Camunda vs Flowable: A comparison of BPM engines — ONLU AG](https://onlu.ch/en/camunda-vs-flowable-a-comparison-of-bpm-engines/) — licensing, deployment model, Camunda 7 EOL timeline
- [Flowable Engine — GitHub](https://github.com/flowable/flowable-engine) / [Flowable Open Source](https://www.flowable.com/open-source-code) — Apache 2.0 license, active maintenance status
- [Flowable DMN documentation](https://www.flowable.com/open-source/docs/dmn/ch06-DMN-Introduction) — DMN 1.1/1.2 conformance statement
- [Drools DMN documentation](https://docs.drools.org/latest/drools-docs/drools/DMN/index.html) — DMN 1.1–1.4 conformance level 3 (fallback evaluator option)
- [Audit Trail Review: Step-by-Step Guide — AccountableHQ](https://www.accountablehq.com/post/audit-trail-review-a-step-by-step-guide-with-best-practices-and-a-compliance-checklist) — risk-based logging granularity principle
- [Immutable Audit Trails: A Complete Guide — Hubifi](https://www.hubifi.com/blog/immutable-audit-log-basics) — completeness/immutability criteria
- Event-sourcing "complete by construction" distinction — synthesized from general event-sourcing/audit-log pattern literature surfaced in search

### FM.3c Q2 ruling — Multi-case concurrency on one Feature

**Ruling: one active mutating Case per Feature in v1** (unchanged conclusion vs. the original plan), with the enforcement mechanism now specified: **optimistic concurrency**, not a pessimistic lock.

Evidence:
- General workflow-concurrency literature is explicit that pessimistic per-record locking "is prone to deadlocks... as well [as] poor performance when conflicts are encountered," and that instance-level locking "is a major bottleneck when it comes to good performance and especially scalability" (directly relevant to NFR-1/NFR-2's concurrency/throughput targets).
- Optimistic locking — "a timestamp mechanism... to ensure that another user has not modified... the record since you last read" — is preferred where concurrent write conflicts are the exception rather than the norm, which is true here: two mutating Cases on one Feature is a rare user error, not routine contention.

**Net effect vs. original plan:** the invariant is unchanged (single active mutating Case per Feature; Assessment cases exempt as read-only), but the implementation is now specified — a version/timestamp column on the Feature aggregate, checked at Case-creation time, rather than a DB row lock held across the Case lifecycle. Locking/merge semantics for a future multi-case-per-Feature mode remain deferred, as originally planned.

### FM.3d Q3 ruling — Package regeneration policy

**Ruling: unchanged** — post-approval upstream revision re-opens gates of direct `DERIVES_FROM`/`SATISFIES` dependents only, resolved via DMN over the edge tables; transitive re-open stays a manual action. One addition: reopening must be gated on a **formal impact assessment step**, not triggered automatically from the DMN check alone.

Evidence:
- Document-control best practice treats impact assessment as a precondition to reopening, not a byproduct of it: change requests should carry "the reason for the change, proposed revisions, impact assessment, and associated risks" before any downstream approval is reopened.
- The same literature names **"reopen rate"** — "the percentage of Document Change Requests reopened due to missing impact assessment" — as a document-control quality metric. This maps directly onto D2OS's existing **Regeneration rate** KPI (§KP) and confirms it as the right signal, rather than something to add.
- "End-to-end traceability" (i.e. the generalized edge tables of AD-7) is cited as what lets teams "immediately see what is impacted" — validating AD-7 as the correct mechanism for this policy, not just a data-modeling convenience.

**Net effect vs. original plan:** E5.2 gains one clarifying step — the DMN check identifies *candidate* direct dependents; a lightweight impact-assessment record (reason, scope, risk) is attached before the gate actually reopens, so the reopen is auditable as a Decision, not a silent side effect.

### FM.3e Q4 ruling — Human editing of AI artifacts

**Ruling: unchanged and strengthened** — gate-comment-and-regenerate only in v1; no in-system editing of AI-drafted artifact content.

Evidence:
- This is now more than an AD-9 inference — it is stated as explicit 2026 practice: "when an annotator or reviewer modifies AI output, best practice is to automatically create a new 'completion' rather than editing in place, keeping the original AI-generated submission intact as an immutable completion... for regulatory purposes."
- The associated audit-trail bar is specific and matches §17/AD-9 exactly: a "tamper-evident record of who reviewed what, when, under what information, and what decision they made," with organizations expected to "prove a specific artifact was reviewed by a specific person on a specific date for specific stated reasons" — this is a restatement of D2OS's OperationExecution + Decision + AuditEntry design, not a new requirement, which is reassuring corroboration rather than new scope.
- Named as a procurement-blocking concern in regulated sectors (finance, healthcare) — relevant since Compliance is a first-class D2OS case type (§5).

**Net effect vs. original plan:** none — the plan already matched emerging regulatory-grade practice; the research raises confidence rather than changing the design.

### FM.3f Q5 ruling — Cross-project knowledge leakage

**Ruling: unchanged, with one addition** — default-deny promotion, human redaction + D4 gate, now explicitly preceded by an **automated sensitivity/PII pre-filter** before the human step (rather than redaction being purely manual).

Evidence:
- Enterprise knowledge-governance practice assigns distinct roles — "Knowledge Owners responsible for specific knowledge domains" and "Knowledge Stewards who maintain the quality and accuracy of knowledge" — which maps cleanly onto D2OS's existing Knowledge Curator persona (owner-analog) plus the workspace-owner D4 gate (steward-analog); no restructuring needed.
- Tooling practice recommends automatic classification — "tools automatically classify data, including sensitive or personally identifiable information (PII), helping organizations... avoid data leaks" — as a first pass before human review, rather than relying on manual redaction alone.
- "Redaction and version control" is named as core document-management discipline alongside access/permissions — confirms redaction as a required step, not optional hardening.

**Net effect vs. original plan:** E3.3's redaction step gains an automated PII/sensitivity classifier as a pre-filter feeding the human redaction + D4 gate, rather than the human step starting from a blank slate.

### FM.3g Q7 ruling — Machine-readable artifact ambition

**Ruling: unchanged** — prose artifacts in v1 except API Specification, which is native OpenAPI YAML.

Evidence:
- Contract-first/API-first is confirmed as standing industry practice specifically because it "creates clear and well-defined APIs... allowing parallel development," lets "frontend and backend teams work in parallel with confidence" once the spec is agreed, and is cheaper to change "than changing a partially implemented system" — all of which apply directly to D2OS's own API Designer persona output.
- Search did **not** turn up an equally strong case for treating ERD or other models as lintable/machine-readable in v1 — results were generic ("machine-readable metadata enables interoperability") rather than a specific best-practice argument for ERD-as-code at this stage. Absent a clear signal, the plan keeps the pragmatic default (prose) rather than manufacturing a rationale.

**Net effect vs. original plan:** none for API Specification (confirmed); the ERD/other-models deferral is retained as a judgment call, explicitly flagged here as evidence-light rather than evidence-backed, should a stakeholder want to revisit it.

### FM.3h Q8 ruling — DMN authoring ownership

**Ruling: unchanged** — Catalog Owner owns DMN authoring in v1; a distinct Rules Steward role is introduced once rule volume warrants it.

Evidence:
- Decision-model/DMN governance literature consistently names a single accountable role — a "model governance owner... responsible for ensuring all decision models meet internal and regulatory requirements" — distinct from the people who actually build rules, which is exactly the Catalog Owner / rule-author split D2OS already has via §17's Catalog governance (Draft → InReview → Published by Catalog Owner).
- Common maturity pattern found across governance sources is to start with one unified owner and split into a specialized steward or Center-of-Excellence role only as scale demands — consistent with the plan's existing rule-count threshold trigger (~50 rules) rather than standing up a dedicated role from day one.

**Net effect vs. original plan:** none — confirmed as-is.

### FM.3i Q9 ruling — Gate SLA model

**Ruling: unchanged** — advisory SLA in v1 (notifications + escalation events only, no automatic routing).

Evidence:
- The general escalation-policy pattern found in the literature — an escalation coordinator activates a policy, "each policy's status noted in the user interface so individuals can see which policies were activated versus held," and a resolver "report[s] back... who then cancels all triggered escalation policies" — describes a system built for **visibility and human resolution**, not silent auto-routing, matching the plan's advisory posture.
- A distinct "advisory mode" pattern for building trust before automating actions — "systems can run in 'advisory mode' where possible actions are constructed into a workflow... made visible... for customers to inspect or modify" before being trusted to execute — directly supports staging advisory-first in v1 as the standard way to earn confidence before Q9's "contractual/auto-route" branch is ever turned on.

**Net effect vs. original plan:** none — confirmed as-is; the auto-route branch remains available as a documented future toggle, not built in v1.

### FM.3j Sources consulted for Q2–Q9 (2026-07-06)

- [Change impact analysis / document change control — MasterControl](https://www.mastercontrol.com/quality/change-control-software/document/), [ComplianceQuest](https://www.compliancequest.com/document-change-control/), [Jama Software](https://www.jamasoftware.com/blog/change-impact-analysis-2/) — Q3 evidence: impact-assessment-gated reopening, "reopen rate" metric
- [Human-in-the-Loop: A 2026 Guide to AI Oversight — Strata](https://www.strata.io/blog/agentic-identity/practicing-the-human-in-the-loop/), [Human-in-the-Loop SDLC — tblocks](https://tblocks.com/articles/human-in-the-loop-sdlc-governance/), [Human in the Loop and AI Compliance — Kiteworks](https://www.kiteworks.com/regulatory-compliance/human-in-the-loop-ai-compliance/) — Q4 evidence: immutable-completion practice, audit-trail bar
- [Framework for Managing Knowledge, Content and Documents — TDAN.com](https://tdan.com/framework-for-managing-knowledge-content-and-documents/21065), [What Is Data Governance — Varonis](https://www.varonis.com/blog/data-governance) — Q5 evidence: knowledge-role structure, automated classification
- Contract-first API design results (general web search, no single canonical source surfaced) — Q7 evidence: parallel-development and change-cost rationale for API-first
- General DMN/decision-model governance role literature (search results generic, no single canonical source) — Q8 evidence: governance-owner role pattern
- Workflow concurrency/locking literature (patent-derived search results, general BPM concepts) — Q2, Q9 evidence: optimistic-vs-pessimistic locking tradeoffs, escalation-policy visibility patterns

### FM.3k Q10–Q12 rulings — non-blocking questions

**Q10 — Pricing/metering unit. Ruling: hybrid pricing** — fixed base (per workspace, optionally seat-adjusted) + usage component metered per Case or delivered Package, not a single flat unit.

Evidence:
- 2026 market data shows hybrid (base + usage/outcome) pricing is now the dominant model (43% of SaaS vendors, trending to 61% by end of 2026) and correlates with faster growth (~8pp revenue growth advantage for consumption-inclusive models); pure per-seat is in structural decline specifically **because AI automation reduces seat as a meaningful value proxy** — directly relevant since D2OS's unit of value is a delivered Execution Package, not a logged-in human.
- Best practice is to "align pricing with a value metric" and "start with the simplest model that captures" it. D2OS already instruments the right value metric: the **Case cost KPI** (§KP: "Tokens + human-review hours per delivered package") required for NFR-7 token-budget enforcement. No new telemetry is needed — Q10 is a billing-layer decision on top of data the plan already collects, not a new epic.
- **Net effect vs. original plan:** the earlier one-line assumption ("meter per delivered package") is refined to base-plus-usage; no schema or phase change — this is a commercial/billing decision layered on existing Case-cost instrumentation whenever a Billing Svc is built (outside the scope of Phases 0–7, which are the product build, not GTM packaging).

**Q11 — Localization. Ruling: single-language content in v1, but language becomes a schema dimension starting Phase 1.**

Evidence:
- i18n literature is consistent that retrofitting language support after content and business logic are already coupled is expensive and error-prone: "if a localized value changes, that can cause features and functionality to break... difficult to identify what caused the break," and "planning for internationalization upfront... is significantly more cost-effective than retrofitting later."
- This is a real, evidence-backed change from the original plan's flat "single-language v1, revisit later" stance: the risk isn't building multi-language *content* now (correctly still deferred — no stakeholder ask, no team bandwidth per AS-1), it's leaving the *schema* language-naive, which is what causes expensive retrofits.
- **Net effect vs. original plan:** `E1.1`'s `DefinitionAsset` supertype (§4.3) gains a `locale` field (default single value, e.g. `en`) at Phase 1 — a ~0.5 person-day addition now versus a cross-cutting migration later touching every Template/Prompt/KnowledgeItem row. `E1.1`'s task list and total move from 12 pd to **12.5 pd**; Phase 1 total effort is effectively unchanged at this precision (still reported as 18 pw).

**Q12 — External PM sync. Ruling: outbound-only, no status sync-back — unchanged, now backed by an explicit source-of-truth argument.**

Evidence:
- Integration best practice frames the one-way-vs-two-way choice around a single question: is there one authoritative system, or do both tools need live bidirectional collaboration? Two-way sync is recommended only when "both teams actively collaborate and need real-time updates in either direction"; where one tool is authoritative, "a one-way sync... helps keep things efficient... without double entry," and even mature two-way integrations recommend field-level directional scoping specifically **to avoid dual source of truth**.
- D2OS has an explicit authoritative system by constitutional design — AD-6 (relational DB is system of record) and the "not extensible" constraint on the audit stream (§16). A status sync-back from Jira/ADO would make an external tool a second write path into ActionItem/Decision state, which is exactly the dual-source-of-truth failure mode the integration literature warns against, and would also soften the product's core boundary ("D2OS does not execute implementation work," §1) — which Q12 itself flags as the risk.
- **Net effect vs. original plan:** none — outbound-only push of ActionItems, no status sync-back, remains deferred post-v1 as a §16 extension point (outbound connectors only). The research supplies the "why," not a design change.

**Sources consulted for Q10–Q12 (2026-07-06):**
- [SaaS Pricing Strategy Guide 2026 — NxCode](https://www.nxcode.io/resources/news/saas-pricing-strategy-guide-2026), [Hybrid Pricing in SaaS 2026 — SaaSMag](https://www.saasmag.com/hybrid-pricing-saas-growth-2026/), [From seats to consumption — Flexera](https://www.flexera.com/blog/saas-management/from-seats-to-consumption-why-saas-pricing-has-entered-its-hybrid-era/) — Q10 evidence
- i18n/internationalization patent and practice literature (general search results on retrofit cost) — Q11 evidence
- [Azure DevOps Jira Integration: Complete Guide — Atlassian Community](https://community.atlassian.com/forums/App-Central-articles/Jira-Azure-DevOps-Integration-The-Complete-Guide/ba-p/3060546), [Two-Way Jira & Azure DevOps Sync — ProductPlan](https://support.productplan.com/two-way-jira-azure-devops-sync) — Q12 evidence: one-way vs two-way tradeoffs, source-of-truth framing

### FM.4 Stack proposal (per §AS — proposals, NOT binding decisions)

| Tier | Proposal | One-line justification |
|---|---|---|
| Language/framework | Java 21 + Spring Boot (modular monolith, one module per bounded context) | Flowable embeds natively; one deployable fits AS-1 team size |
| Relational DB | PostgreSQL 16 | Row-level security for T2/AD-10, JSONB for definition bodies, schema-per-BC (§13) |
| BPMN/DMN engine | Flowable 7 (embedded) | BPMN 2.0 + DMN 1.4 in-process; on-prem path open (Q6) |
| Object storage | S3 API (AWS S3 prod / MinIO dev) | Artifact content + hash per §13; ubiquitous |
| Embedding index | pgvector, partitioned by `workspace_id` | One fewer system; T2 partitioning inherits RLS |
| AI Gateway | Thin internal Spring service over provider SDKs | AS-5 provider-agnostic; logs prompt/model/params (AD-9, T5) |

---

## Phase 0 — Repository & Foundation `[MANUAL]`

**Goal.** Establish the forked, tagged, restructured repository with the v0 template audit committed, so Phase 1 starts from a governed catalog baseline with a known authoring backlog (AD-11, §0).

**Entry criteria:** GitHub account with fork rights; this plan approved.
**Exit criteria:** fork renamed and tagged `v0.0.0-template-library-baseline`; §0.5 directory structure present; `docs/catalog-audit.md` committed with all 66 classifications; branch `feat/catalog-v1-initiation-case` exists.

**Work breakdown** (epic E0 — cites §0, AD-0, AD-11):

| Task | Est (pd) |
|---|---|
| E0.1 Fork repo, rename (`documentation-delivery-os`), push tag `v0.0.0-template-library-baseline` | 0.5 |
| E0.2 Overlay §0.5 structure (`/catalog/**`, `/docs`, `/src`); move prompt doc + this plan into `/docs`; keep repo `/tools` scripts | 1 |
| E0.3 Verify + commit the pre-completed audit (Appendix B) as `docs/catalog-audit.md`; spot-check 10 templates against classification | 1 |
| E0.4 Convert 15 GAP entries + Phase-1 NEEDS_REVISION set into catalog authoring backlog issues | 0.5 |
| E0.5 Cut `feat/catalog-v1-initiation-case` | 0.5 |

Catalog assets: none authored (audit only). DB entities: none. Services: none.
Tests: `tools/check_links.py` + docs CI green post-restructure.
KPIs: none (instrumentation starts Phase 1 per §KP).
**Effort: 0.7 person-weeks · confidence HIGH.**

---

## Phase 1 — Catalog Spine + Initiation Case Type

**Goal.** End-to-end thin slice: a ProblemSubmission is classified, a CaseInstance pins a CaseDefinitionSnapshot, the Initiation workflow executes sequentially through three AI personas, validated artifacts accumulate, and a hash-stamped ExecutionPackage with HandoverRecord is delivered — every transition audited and replayable (AD-1..5, AD-8, AD-9, AD-12).

**Entry criteria:** Phase 0 exit; Q1 and Q6 ruled (or plan assumptions accepted).
**Exit criteria (testable):** (1) demo case Submitted→Delivered with zero manual DB edits; (2) replay test reconstructs every AI output from stored snapshots (NFR-6); (3) RLS leakage suite green (T2); (4) injection-symptom check blocks a seeded malicious submission (T1); (5) package manifest hash verifiable; (6) three §KP KPIs emitting.

**Work breakdown:**

| Epic | Traces | Tasks (0.5–3 pd each) | Σ pd |
|---|---|---|---|
| E1.1 Definition core | AD-1, AD-3, AD-4, §13 | DefinitionAsset supertype table + repository, incl. `locale` field [Q11] (2) · publish immutability + semver enforcement (2) · 8 Definition-type tables (3) · checksum-on-publish [T4-a] (1) · `(key,version)` resolution service (2) · CaseDefinitionSnapshot pinning at `Planned` (2) | 12.5 |
| E1.2 Org & tenancy | AD-10, T2 | Workspace/Project/Version/Feature tables + `workspace_id` everywhere + RLS policies (3) · workspace-scoped authN/Z (2) · cross-tenant leakage test suite in CI [T2-a] (2) · encryption at rest for submissions/artifacts [T3-b] (1) | 8 |
| E1.3 Intake | BC-3, AD-12 | ProblemSubmission schema + API (2) · Problem Form UI (3) · DMN classify + human confirm step (2) · delimited-untrusted-data prompt framing in every PromptDefinition [T1-a] (1) · field-level sensitivity tagging [T3-a] (1) | 9 |
| E1.4 Case & audit spine | §6, AD-6, T6 | Case state machine, Event + AuditEntry in same tx (3) · transactional outbox → event store (2) · append-only DB grants on audit stream [T6-a] (1) · runtime tables per Q1 (PersonaInvocation, OperationExecution, ActivityExecution, ActionItem) (2) | 8 |
| E1.5 Engine | AD-2, Q6, NFR-4 | Embed Flowable + correlation by CaseInstance id (2) · Initiation BPMN (sequential, no parallel) (3) · DMN approach-selection table (2) · wait-state mirroring to Case `Waiting` (2) · queue-and-resume job config [NFR-4] (1) | 10 |
| E1.6 Persona execution | AD-5, AD-8, AD-9, T5, NFR-7 | Execution envelope builder (2) · AI Gateway v1: provider abstraction + full call logging [T5-a] (3) · per-case token budget → `Suspended` on breach [NFR-7] (1) · template renderer (2) · validation pipeline: structural + rubric scoring (3) · injection-symptom output check [T1-b] (2) · bounded revise loop + escalation (2) · OperationExecution snapshot persistence (prompt ver, model ver, inputs) (2) | 17 |
| E1.7 Artifacts & package | BC-5, AD-7 | ArtifactRevision + object storage w/ hash (2) · generalized `trace_link`/`dependency` edge tables [AD-7] (2) · package manifest assembly + hash-stamp (2) · HandoverRecord entity (1) | 7 |
| E1.8 Catalog content | AD-11, §18 | Initiation CaseTypeDefinition + WorkflowDefinition (2) · 3 PersonaDefinitions + 3 PlaybookDefinitions (3) · revise BRD/SRS/SAD templates from v0 sources (3) · **author GAP templates: Task Breakdown, Handover Record** (2) · 3 RubricDefinitions + PromptDefinitions (3) · 1 RuleDefinition (1) | 14 |
| E1.9 KPI instrumentation | §KP | Emit rubric first-pass rate, package completeness, case cost (tokens) + minimal dashboard (2) | 2 |
| E1.10 Replay audit | NFR-6, AD-9 | Replay harness: reconstruct case from snapshots, diff outputs (3) | 3 |

Catalog assets authored: 1 CaseType, 1 Workflow, 3 Persona, 3 Playbook, 9 Template (7 revised + 2 new), 1 Rule, 3 Rubric, ~6 Prompt Definitions.
DB entities: all §13 definition tables; Workspace/Project/Version/Feature; ProblemSubmission; CaseInstance; WorkflowInstance; PersonaInvocation; OperationExecution; ActivityExecution; ActionItem; Artifact(+Revision); ExecutionPackage; HandoverRecord; Decision; AuditEntry; Event outbox; trace_link; dependency.
Services: Case Svc · Catalog Svc · Intake Svc · Orchestration Svc · Persona Execution Svc (single-threaded) · Artifact Svc · basic Package Svc · AI Gateway · Audit log.
Tests: unit per epic · integration (submit→deliver) · replay-audit (NFR-6) · security: T1 injection seed suite, T2 leakage suite, T6 grant test.
KPIs: rubric first-pass rate · package completeness · case cost.
**Effort: 90.5 pd ≈ 18.1 person-weeks (≈ 9–12 calendar weeks at AS-1 staffing) · confidence MEDIUM.**

---

## Phase 2 — Full Persona Suite + Parallel Execution

**Goal.** All 14 §8 personas operational on Initiation; BPMN parallel gateway (Security ∥ UX ∥ Data ∥ Infra); cross-artifact Consistency-Check subprocess; NFR load posture established.

**Entry criteria:** Phase 1 exit. **Exit criteria:** Initiation runs the §7 canonical shape incl. parallel block and consistency join; 14 personas pass rubric suites; NFR-1/2/3 load test green; attachment sandbox operational.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E2.1 Persona catalog ×11 | §8, AD-11 | 11 PersonaDefinitions + playbooks + rubrics + prompts, batched 3–4 per task (3+3+3+3) · revise ~14 supporting v0 templates (3) | 15 |
| E2.2 Parallel execution | §7, NFR-1 | Parallel gateway + join in workflow (2) · Persona Execution Svc concurrency (job pool) (3) · dual-state reconciliation job (§20) (2) | 7 |
| E2.3 Consistency subprocess | §10 | Deterministic cross-checks (2) · semantic consistency D3 op + rubric (3) | 5 |
| E2.4 NFR verification | NFR-1/2/3 | Load test: 50 concurrent cases (2) · progress-event stream ≤5 s (2) · p95 op latency report (1) | 5 |
| E2.5 Attachment sandbox | T1-d | Sandboxed extraction/summarization pass for uploads before persona prompts (3) | 3 |
| E2.6 Gateway limits | T5-b | Per-workspace rate limits + budget rollup (1) | 1 |

Catalog: 11 Persona, 11 Playbook, 11 Rubric, ~14 Template, ~11 Prompt, 1 Subprocess Definition. DB: none new (SubprocessInstance rows). Services: Persona Execution Svc (parallel), Consistency subprocess, Notification stub.
Tests: unit · integration (parallel join) · replay-audit rerun · security: T1-d sandbox suite. KPIs: + gate cycle time (from waits), regeneration rate.
**Effort: 36 pd ≈ 7 pw · confidence MEDIUM.**

---

## Phase 3 — Knowledge Layer

**Goal.** Governed KnowledgeItem lifecycle (§11): scoped retrieval, per-execution injection snapshots, post-case lessons-learned capture via Knowledge Curator persona.

**Entry:** Phase 2 exit; Q5 ruled. **Exit:** injection snapshot recorded on every OperationExecution; capture→curation→publish flow demo; deprecation flags past executions; knowledge-influence KPI emitting.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E3.1 Knowledge core | §11, AD-9 | KnowledgeItem schema + scope lattice (2) · retrieval API (scope+tags+persona) as Open Host Service (3) · pgvector index partitioned per workspace [T2-b] (2) · gateway workspace-scope assertion on injection [T2-c] (1) | 8 |
| E3.2 Injection snapshots | AD-9, NFR-6 | Snapshot exact item versions on OperationExecution (2) · replay harness extension (1) · deprecation flagging of past executions (1) | 4 |
| E3.3 Capture & curation | §11, Q5, T3-c | Case-end capture subprocess (2) · Knowledge Curator persona + rubric (2) · automated sensitivity/PII pre-filter feeding the redaction step (2) · D4 curation gate + promotion w/ human redaction, sensitive fields excluded by default [T3-c] (2) | 8 |
| E3.4 KPI | §KP | Knowledge-influence metric (rubric delta with/without item) (2) | 2 |

Catalog: 1 Persona (Curator), 1 Playbook, 1 Rubric, KnowledgeItem seed set. DB: KnowledgeItem, injection-snapshot table. Services: Knowledge Retrieval Svc.
Tests: unit · integration · replay w/ knowledge · security: T2-b/c partition tests. KPIs: + knowledge influence.
**Effort: 22 pd ≈ 4.4 pw · confidence MEDIUM.**

---

## Phase 4 — Assessment + Enhancement Case Types

**Goal.** Second and third case types (§5 build order); DMN case-type routing live from intake; catalog proves zero-schema-change extension (§16).

**Entry:** Phase 3 exit; Q2 ruled. **Exit:** all three case types executable; DMN routes intake to correct type with human confirm; one-active-case-per-Feature rule enforced (Q2); Assessment produces findings+recommendation package only.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E4.1 Assessment type | §5, §16 | CaseType + Workflow defs (2) · findings/recommendation templates + rubrics (3) · read-only enforcement (1) | 6 |
| E4.2 Enhancement type | §5 | CaseType + Workflow defs (2) · delta-doc + impact-analysis templates (3) · baseline referencing of prior artifacts via trace_link (2) | 7 |
| E4.3 Intake routing | §9-D2 | DMN case-type classification table over problem attributes (2) · conditional-artifact DMN (e.g., DPIA iff personal data) (2) · Q2 single-active-case guard via optimistic version check on Feature aggregate, not a row lock (1) | 5 |

Catalog: 2 CaseType, 2 Workflow, ~8 Template, 2 Rule, ~4 Rubric Definitions. DB: none new. Services: Intake Svc (routing), Case Svc (Q2 guard).
Tests: unit · integration per type · replay rerun. KPIs: existing, segmented by case type.
**Effort: 18 pd ≈ 3.6 pw · confidence MEDIUM-HIGH.**

---

## Phase 5 — Governance & Review Gates

**Goal.** First-class Review-Gate / Approval-Gate subprocesses with versioned EscalationPolicy, SLA timers (advisory, Q9), regeneration policy (Q3/Q4), full gate audit trail (§17).

**Entry:** Phase 4 exit; Q3/Q4/Q9 ruled. **Exit:** all D4 decisions flow through gate subprocesses; escalation timer demo; upstream revision re-opens direct dependent gates; audit stream hash-chained; NFR-5 retention + NFR-8 DR verified.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E5.1 Gate subprocesses | §7, §17, D4 | Review-Gate + Approval-Gate BPMN w/ user tasks (3) · EscalationPolicy definition type + role chains (2) · advisory SLA timers + notifications [Q9] (2) · Review UI (3) | 10 |
| E5.2 Regeneration policy | Q3, Q4, AD-7 | DMN over `DERIVES_FROM`/`SATISFIES` edges → re-open direct dependents (2) · impact-assessment record (reason/scope/risk) gating the reopen (1) · gate-comment-and-regenerate flow (2) · delta report on regeneration (§12) (2) | 7 |
| E5.3 Audit & retention hardening | T6-b, NFR-5, T3-d | Periodic hash-chaining of AuditEntry stream [T6-b] (2) · 7-yr retention + workspace policy config [NFR-5] (1) · package access scoped by role assignment [T3-d] (1) | 4 |
| E5.4 DR verification | NFR-8 | Backup/restore drill: RPO ≤ 5 min, RTO ≤ 4 h documented (2) | 2 |

Catalog: 2 gate SubprocessDefinitions, EscalationPolicy defs. DB: Review, Approval, gate-decision tables (extend BC-7). Services: Governance Svc, Notification Svc (real).
Tests: unit · integration (escalation, re-open) · replay · security: T6-b tamper test. KPIs: gate cycle time (now first-class), handover acknowledgement rate.
**Effort: 23 pd ≈ 4.6 pw · confidence MEDIUM.**

---

## Phase 6 — Catalog Studio (Admin UI)

**Goal.** Definition authoring UI with semver publish workflow, deprecation impact reports, compatibility matrix, prompt-diff review, and global-library copy-on-subscribe (§17, AD-3, AD-10, T4).

**Entry:** Phase 5 exit; Q8 ruled. **Exit:** all 8 definition types authorable in UI; publish requires D4 review with prompt diffs rendered; deprecation produces impact report; NFR-9 resolution benchmark green; copy-on-subscribe demo across workspaces.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E6.1 Authoring UI | §17, Q8 | Definition CRUD-as-draft per type (3+3) · DMN table editor embed (2) · rubric/prompt editors w/ typed slots (2) | 10 |
| E6.2 Publish governance | T4-b/c, §17 | Draft→InReview→Published D4 flow in UI [T4-b] (2) · prompt diffs as first-class review content [T4-c] (2) · MAJOR-version architecture-board gate (1) | 5 |
| E6.3 Lifecycle tooling | AD-3, §12 | Fork-with-provenance (`derived_from_id`) authoring (2) · deprecation impact report (2) · compatibility matrix view (2) | 6 |
| E6.4 Library distribution | AD-10, T4-d | Copy-on-subscribe from Global library w/ provenance [T4-d] (3) | 3 |
| E6.5 Scale check | NFR-9 | Seed 500 definition versions; pin-resolution ≤ 2 s benchmark (1) | 1 |

Catalog: none (tooling). DB: subscription/provenance tables. Services: Catalog Studio UI, Catalog Svc extensions.
Tests: unit · integration (publish/deprecate) · security: T4 supply-chain suite · NFR-9 perf. KPIs: catalog authoring throughput (internal).
**Effort: 25 pd ≈ 5 pw · confidence LOW-MEDIUM (UI scope volatile).**

---

## Phase 7 — Graph Projection + Analytics

**Goal.** Derived, rebuildable graph read model projected from event outbox + edge tables (AD-6, AD-7, §15); traceability queries; knowledge-influence analytics; dependency-cycle detection.

**Entry:** Phase 5 exit (gates emit full event payloads); Phase 6 not required. **Exit:** graph rebuildable from scratch (drop + replay); TRACES_TO/DEPENDS_ON queries in UI; cycle detection alerting; knowledge-influence dashboard on graph.

| Epic | Traces | Tasks | Σ pd |
|---|---|---|---|
| E7.1 Projection | AD-6, §15 | Projector: outbox + edge tables → graph store (3) · rebuild-from-scratch job + verification (2) · projection-sufficient payload audit of event stream (1) | 6 |
| E7.2 Queries & analytics | §15, §KP | Traceability query API + UI panel (3) · dependency-cycle detection (2) · knowledge-influence analytics on graph (2) | 7 |

Catalog: none. DB: graph store (proposal: PostgreSQL recursive CTEs first; dedicated graph DB only if query perf fails — PLAN-DECISIONS PD-4). Services: Projection worker, Analytics API.
Tests: rebuild-equivalence test (graph == relational truth) · cycle-detection unit. KPIs: knowledge influence (graph-era, §KP).
**Effort: 13 pd ≈ 2.6 pw · confidence MEDIUM.**

---

## DEP. Cross-Phase Dependency Map

- **Phase 0 blocks Phase 1** because the fork/tag/audit provide the v0 templates E1.8 revises (AD-11).
- **Phase 1 blocks Phase 2** because parallel execution extends the single-threaded Persona Execution Svc and the audited case spine (E1.4–E1.6).
- **Phase 1 blocks Phase 3** because injection snapshots attach to OperationExecution (E1.6) and retrieval needs RLS tenancy (E1.2).
- **Phase 2 blocks Phase 3** because the Knowledge Curator persona and case-end capture subprocess require the full persona execution + subprocess machinery (E2.1–E2.3).
- **Phase 3 blocks Phase 4** because Assessment/Enhancement rubrics assume knowledge injection is available to personas (§18 order).
- **Phase 4 blocks Phase 5** because regeneration policy (Q3) must be exercised across ≥2 case types to validate DMN gate re-opening.
- **Phase 5 blocks Phase 6** because publish governance UI (E6.2) encodes the gate machinery built in E5.1.
- **Phase 5 blocks Phase 7** because gate events complete the projection-sufficient payload set (E7.1); **Phase 6 does not block Phase 7** (may run in parallel if staffing allows, relaxing AS-1).

---

## TR. Traceability Appendix (TR-2 two-way matrix)

Every task inherits its epic's citations (epic Traces column); matrix below maps every AD/NFR/T to implementing tasks. No unmapped rows ⇒ TR-2 satisfied. Every §PS control appears in exactly one phase ⇒ TR-3 satisfied (control ids T1-a…T6-b marked in epic tables).

| ID | Implemented by |
|---|---|
| AD-0 | E0.1 (rename) |
| AD-1 | E1.1 (definition core), E1.4 (instance tables) |
| AD-2 | E1.5, E2.2, E5.1 |
| AD-3 | E6.3 (fork-with-provenance; runtime override nowhere implemented — enforced by absence + E1.1 immutability) |
| AD-4 | E1.1 (pinning), E5.2 (delta reports) |
| AD-5 | E1.6 (validation pipeline), E4.3 (DMN routing), E5.1 (D4 gates) |
| AD-6 | E1.4 (outbox), E7.1 (rebuildable projection) |
| AD-7 | E1.7 (edge tables), E5.2, E7.1 |
| AD-8 | E1.6 (envelope: no persona→persona path, no routing/approval authority) |
| AD-9 | E1.6 (prompt/model snapshots), E3.1–E3.2 (governed injection), E1.10 (replay) |
| AD-10 | E1.2 (workspace isolation), E6.4 (copy-on-subscribe) |
| AD-11 | E0.3–E0.4 (audit), E1.8/E2.1 (revision over discard) |
| AD-12 | E1.3 (T1-a framing), E1.6 (T1-b check), E2.5 (T1-d sandbox) |
| NFR-1, NFR-2 | E2.4 (load tests), E2.2 (concurrency) |
| NFR-3 | E2.4 (latency + progress events) |
| NFR-4 | E1.5 (queue-and-resume) |
| NFR-5 | E5.3 (retention config) |
| NFR-6 | E1.10, E3.2 (replay harness) |
| NFR-7 | E1.6 (token budget → Suspended) |
| NFR-8 | E5.4 (DR drill) |
| NFR-9 | E6.5 (resolution benchmark) |
| T1 (a,b,c,d) | a: E1.3 · b: E1.6 · c: structural via AD-8/E1.6 · d: E2.5 |
| T2 (a,b,c) | a: E1.2 (RLS + CI leakage) · b: E3.1 (partitioned index) · c: E3.1 (gateway assertion) |
| T3 (a,b,c,d) | a: E1.3 (tagging) · b: E1.2 (encryption) · c: E3.3 (promotion exclusion) · d: E5.3 (role-scoped package access) |
| T4 (a,b,c,d) | a: E1.1 (checksums) · b: E6.2 (D4 publish) · c: E6.2 (prompt diffs) · d: E6.4 (copy-on-subscribe) |
| T5 (a,b) | a: E1.6 (logging + per-case limits) · b: E2.6 (per-workspace limits) |
| T6 (a,b) | a: E1.4 (append-only grants) · b: E5.3 (hash-chaining) |

Acceptance self-check: all phases have exit criteria ✓ · no task > 3 pd ✓ · TR-2 gap-free ✓ · security controls distributed across Phases 1/2/3/5/6, no terminal hardening phase ✓ · KPI instrumentation present in Phase 1 (E1.9) ✓ · nothing constitutional (§16) modified ✓.

---

## PLAN-DECISIONS (ambiguity protocol, §18.0)

| # | Decision | Alternative | Reversal cost |
|---|---|---|---|
| PD-1 | Modular monolith (one deployable, one Spring module per bounded context); §14 "services" are logical modules | Microservices per BC | Medium — BC boundaries + outbox make later extraction mechanical |
| PD-2 | pgvector for embedding index | Dedicated vector DB | Low — retrieval behind Open Host Service API |
| PD-3 | Repo tooling (`adopt.py`, `check_links.py`, docs CI) retained in `/tools` and reused for catalog hygiene | Discard tooling | Trivial |
| PD-4 | Graph projection starts on PostgreSQL recursive CTEs; dedicated graph DB only if traceability query p95 exceeds targets | Neo4j from day one | Low — projection is rebuildable by definition (AD-6) |
| PD-5 | Even ADOPTED v0 templates get DefinitionAsset wrapping (frontmatter → `key/semver/checksum/library_scope`) on catalog import; content untouched | Import raw files | Trivial |
| PD-6 | GitHub API 404 treated as anonymous-API artifact, not repo unavailability; git protocol is the verification method of record | Invoke §0 fallback | N/A — fallback path documented if repo access is ever lost |

---

## Appendix A — Cumulative effort

| Phase | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | **Total** |
|---|---|---|---|---|---|---|---|---|---|
| Person-weeks | 0.7 | 18.1 | 7 | 4.4 | 3.6 | 4.6 | 5 | 2.6 | **≈ 46 pw** |

---

## Appendix B — v0 Template Audit (§0 step 4, completed 2026-07-06)

Source: `Anahena505/documentation-framework@master` (0baea26). Classification against the 66 §10 catalog entries.
**ADOPTED** = content structure fits a D2OS artifact type (still gets DefinitionAsset wrapping, PD-5). **NEEDS_REVISION** = right substance, wrong shape (product-lifetime knowledge-base doc → per-case generation template with typed slots). **GAP** = no usable source.

**Summary: 19 ADOPTED · 32 NEEDS_REVISION · 15 GAP.**

| §10 artifact | Class | v0 source (repo path under `docs/` unless noted) |
|---|---|---|
| **Business** | | |
| BRD | NEEDS_REVISION | requirements/prd.md + business/business-overview.md |
| Stakeholder Register | NEEDS_REVISION | stakeholder-evaluation.md |
| Business Case / Cost-Benefit | NEEDS_REVISION | business/financial-model.md |
| Scope Statement | **GAP** | — |
| Business Process Models | NEEDS_REVISION | product/business-processes.md |
| Glossary / Ubiquitous Language | ADOPTED | architecture/glossary.md |
| **Requirements** | | |
| SRS | NEEDS_REVISION | requirements/functional-requirements.md |
| Functional Specification | NEEDS_REVISION | product/feature-specs/_template.md |
| Acceptance Criteria | **GAP** | — (fragments in feature-specs only) |
| Use Case / User Story Catalog | NEEDS_REVISION | product/user-journeys.md |
| Requirements Traceability Matrix | ADOPTED | requirements/traceability-matrix.md |
| NFR Specification | ADOPTED | requirements/nfr-catalog.md |
| **Architecture** | | |
| ADRs | ADOPTED | architecture/adr/_template.md |
| Solution Architecture Document | NEEDS_REVISION | architecture/system-overview.md + domain-map.md |
| Sequence Diagrams | **GAP** | — |
| Class Diagrams | **GAP** | — |
| Integration / ICD | NEEDS_REVISION | backend/integration-policy.md + partners/public-api-reference.md |
| Capacity & Performance Model | NEEDS_REVISION | devops/capacity-planning.md |
| **Data** | | |
| Database Design | NEEDS_REVISION | database/schema-strategy.md |
| ERD | NEEDS_REVISION | database/erd-overview.md |
| Data Dictionary | ADOPTED | database/data-dictionary.md |
| Data Governance & Classification | NEEDS_REVISION | security/data-classification.md |
| Migration / Seeding Plan | NEEDS_REVISION | database/migration-policy.md + seeding-strategy.md |
| Retention & Disposal Policy | ADOPTED | database/retention-policy.md |
| **API** | | |
| API Specification | NEEDS_REVISION | backend/api-contract.md (→ native OpenAPI per Q7) |
| API Governance Checklist | **GAP** | — |
| Contract Test Specification | **GAP** | — (testing/integration-tests.md is env doc, not contract spec) |
| **UI/UX** | | |
| Information Architecture | NEEDS_REVISION | frontend/ui-routing.md |
| Wireframe / Flow Specification | NEEDS_REVISION | design/wireframe-registry.md |
| Interaction & State Spec | NEEDS_REVISION | frontend/state-model.md + error-states.md |
| Accessibility Conformance Checklist | ADOPTED | frontend/accessibility.md |
| Content / Copy Guidelines | **GAP** | — |
| **Security** | | |
| Security Assessment | **GAP** | — (vulnerability-management.md is ops policy) |
| Threat Model (STRIDE) | ADOPTED | security/threat-model.md |
| Security Req & Controls Matrix | NEEDS_REVISION | security/compliance-matrix.md + rbac-matrix.md |
| DPIA | ADOPTED | compliance/dpia.md (+ dpia-register.md) |
| Secrets & Access Design | NEEDS_REVISION | security/secret-policy.md + auth-policy.md |
| **Infrastructure** | | |
| Infrastructure Guide | NEEDS_REVISION | devops/infra-topology.md + container-architecture.md |
| Deployment Guide | NEEDS_REVISION | devops/deployment-strategy.md |
| Environment Matrix | ADOPTED | environment/environment-matrix.md |
| IaC Specification | **GAP** | — (infrastructure/* are placeholder READMEs) |
| Rollback & Cutover Plan | NEEDS_REVISION | devops/rollback-policy.md |
| Observability Spec | NEEDS_REVISION | devops/monitoring.md |
| **Testing** | | |
| Test Plan | NEEDS_REVISION | testing/test-matrix.md |
| Test Strategy | **GAP** | — |
| Test Case Catalog | NEEDS_REVISION | testing/core-domain-tests.md (pattern) |
| Performance Test Plan | ADOPTED | testing/performance-tests.md |
| UAT Plan & Sign-off | ADOPTED | testing/uat-process.md |
| **Operations** | | |
| Operational Runbook | ADOPTED | devops/runbooks/_template.md |
| SLO/SLA Definition | ADOPTED | devops/slo-sla.md |
| Incident Response Playbook | ADOPTED | security/incident-response.md |
| Maintenance Calendar | **GAP** | — |
| Support Handbook | NEEDS_REVISION | processes/support-operations.md |
| **Governance / Delivery** | | |
| Risk Register | ADOPTED | processes/risk-register.md |
| Decision Log | NEEDS_REVISION | architecture/adr/index.md |
| RACI | ADOPTED | processes/raci-matrix.md |
| Implementation Checklist | **GAP** | — |
| Task Breakdown (WBS→ActionItems) | **GAP** | — ⚠ Phase-1 mandatory |
| Dependency Register | **GAP** | — (devops/dependency-policy.md is package-dependency policy) |
| Milestone Plan | NEEDS_REVISION | architecture/version-roadmap.md |
| Communication Plan | ADOPTED | processes/communication-plan.md |
| Release Notes | ADOPTED | product/release-notes.md |
| Compliance Evidence Index | NEEDS_REVISION | compliance/legal-artifact-register.md |
| **Knowledge / Closure** | | |
| Knowledge Base contributions | NEEDS_REVISION | knowledge-base-structure.md |
| Lessons Learned | NEEDS_REVISION | devops/postmortems/_template.md |
| Handover Record | **GAP** | — ⚠ Phase-1 mandatory |

**Bonus assets not in the §10 catalog** (retain in fork, out of catalog scope for now): AI docs suite (`docs/ai/*` — model cards, prompt standards, evaluation framework: useful *internal* references for building D2OS's own AI Gateway/rubrics), compliance suite beyond DPIA (AI-Act conformity, ToS, privacy policy — candidates for the Compliance case type in a later phase), onboarding/partner/user-guide folders, `tools/*` scripts (PD-3), sample instantiation under `examples/`.
