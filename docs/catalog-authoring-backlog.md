# D2OS — Catalog Authoring Backlog

**Phase:** 0 (Repository & Foundation) · **Step:** §0.1.4, item 5 ("gap list becomes the first catalog authoring backlog")
**Derived from:** [`catalog-audit.md`](catalog-audit.md)

> Substitutes for GitHub Issues in this pass — no GitHub write access was available when this backlog was generated (no `gh` CLI / credentials in the environment). Each row below is issue-shaped (title, class, source, note) and can be pasted into GitHub Issues, Jira, or wherever the project tracks work, once the repo is live on GitHub. See the handoff steps in the Phase 0 completion note for how to finish the GitHub-side setup.

## Priority 1 — Phase 1 blocking (must exist before the Initiation case type can run, §18 Phase 1)

| Template | Class | Action | v0 source |
|---|---|---|---|
| Task Breakdown (WBS→ActionItems) | **GAP** | Author greenfield — no v0 analog exists | — |
| Handover Record | **GAP** | Author greenfield — no v0 analog exists | — |
| BRD | NEEDS_REVISION | Revise into D2OS typed-slot format | requirements/prd.md + business/business-overview.md |
| SRS | NEEDS_REVISION | Revise into D2OS typed-slot format | requirements/functional-requirements.md |
| Solution Architecture Document | NEEDS_REVISION | Revise into D2OS typed-slot format | architecture/system-overview.md + domain-map.md |
| ADRs | ADOPTED | Wrap only (DefinitionAsset metadata; content as-is) | architecture/adr/_template.md |
| RACI | ADOPTED | Wrap only (DefinitionAsset metadata; content as-is) | processes/raci-matrix.md |

## Priority 2 — full GAP list (13 remaining; no v0 source, greenfield authoring, scheduled per the phase each artifact domain first becomes active)

| Template | Domain | Likely phase |
|---|---|---|
| Scope Statement | Business | Phase 2 (Business Analyst persona) |
| Acceptance Criteria | Requirements | Phase 2 |
| Sequence Diagrams | Architecture | Phase 2 (Solution Architect persona) |
| Class Diagrams | Architecture | Phase 2 |
| API Governance Checklist | API | Phase 2 (API Designer persona) |
| Contract Test Specification | API | Phase 2 |
| Content / Copy Guidelines | UI/UX | Phase 2 (UX Architect persona) |
| Security Assessment | Security | Phase 2 (Security Architect persona) |
| IaC Specification | Infrastructure | Phase 2 (Infrastructure Engineer persona) |
| Test Strategy | Testing | Phase 2 (QA/Test Strategist persona) |
| Implementation Checklist | Governance/Delivery | Phase 1 (Delivery Planner persona — check against E1.8 scope before Phase 1 close) |
| Dependency Register | Governance/Delivery | Phase 1 (Delivery Planner persona — check against E1.8 scope before Phase 1 close) |
| Maintenance Calendar | Operations | Phase 5+ (Operations personas not yet in §8 baseline catalog — flag for persona-catalog review) |

## Priority 3 — NEEDS_REVISION backlog (25 remaining beyond the Priority 1 set, grouped by domain)

| Domain | Templates |
|---|---|
| Business | Stakeholder Register · Business Case/Cost-Benefit · Business Process Models |
| Requirements | Functional Specification · Use Case/User Story Catalog |
| Architecture | Integration/ICD · Capacity & Performance Model |
| Data | Database Design · ERD · Data Governance & Classification · Migration/Seeding Plan |
| API | API Specification (→ native OpenAPI per Q7 ruling, not prose revision) |
| UI/UX | Information Architecture · Wireframe/Flow Specification · Interaction & State Spec |
| Security | Security Req & Controls Matrix · Secrets & Access Design |
| Infrastructure | Infrastructure Guide · Deployment Guide · Rollback & Cutover Plan · Observability Spec |
| Testing | Test Plan · Test Case Catalog |
| Governance/Delivery | Decision Log · Milestone Plan · Compliance Evidence Index |
| Knowledge/Closure | Knowledge Base contributions · Lessons Learned |

## Notes

- Counts reconcile with `catalog-audit.md`: 7 items in Priority 1 (2 GAP + 3 NEEDS_REVISION + 2 ADOPTED) + 13 GAP in Priority 2 + 25 NEEDS_REVISION in Priority 3 = 15 GAP total, 28 of 32 NEEDS_REVISION accounted for outside Priority 1 (3 NEEDS_REVISION and 2 ADOPTED consumed by Priority 1), 19 ADOPTED total (2 consumed by Priority 1, 17 remain as simple wrap-only tasks not tracked here as backlog since no authoring work is required).
- When GitHub access is available, convert each row into one issue labeled `catalog-authoring`, with a `phase-1`/`phase-2`/etc. label from the "Likely phase" column, linked back to this file and to the relevant §10 catalog line in the plan.
