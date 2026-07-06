# D2OS — v0 Template Library Audit

**Phase:** 0 (Repository & Foundation) · **Step:** §0.1.4 · **Completed:** 2026-07-06
**Source:** `Anahena505/documentation-framework@master` (commit `0baea26`)

> This file is the Phase-0 exit-criteria deliverable specified in `docs/d2os-implementation-plan.md` §Phase 0 ("`docs/catalog-audit.md` committed with all 66 classifications"). It classifies every template in the forked v0 repository against the D2OS §10 Documentation Catalog.

## Classification legend

- **ADOPTED** — content structure fits a D2OS artifact type as-is (still receives `DefinitionAsset` wrapping per PLAN-DECISIONS PD-5: `key`/`semver`/`checksum`/`library_scope` added on import, content untouched).
- **NEEDS_REVISION** — right substance, wrong shape. The v0 repo is a *product-lifetime knowledge-base* framework (owner + review-cadence frontmatter, one file per topic); D2OS needs *per-case generation* templates with typed slots (`{persona charter} {operation contract} {template skeleton} {injected knowledge} {upstream artifact digests} {problem context} {rubric}` per §8).
- **GAP** — no usable v0 source; net-new authoring required.

**Summary: 19 ADOPTED · 32 NEEDS_REVISION · 15 GAP** (66 total §10 catalog entries).

## Full classification

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

## Bonus assets (not in the §10 catalog)

Retained in the fork, out of catalog scope for now: AI docs suite (`docs/ai/*` — model cards, prompt standards, evaluation framework: useful *internal* references for building D2OS's own AI Gateway/rubrics), compliance suite beyond DPIA (AI-Act conformity, ToS, privacy policy — candidates for the Compliance case type in a later phase), onboarding/partner/user-guide folders, `tools/*` scripts (retained per PLAN-DECISIONS PD-3), sample instantiation under `examples/`.

## Next step

The GAP list and Phase-1-mandatory NEEDS_REVISION items above are broken out into a working backlog in [`catalog-authoring-backlog.md`](catalog-authoring-backlog.md).
