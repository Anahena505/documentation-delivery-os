# D2OS — Documentation & Delivery Operating System

D2OS compiles auditable, reproducible execution plans for documentation and delivery work. Every
executable concept (Workflow, Persona, Playbook, Template, Operation, Rule) exists as an immutable,
semantic-versioned **Definition**, separate from its runtime **Instance**; a running Case pins to the
exact Definition versions frozen into its snapshot and is fully reconstructable from recorded data,
never from "whatever the Definition currently says." AI participates as a bounded, reproducible drafter
gated by an accountable human decision — it never crosses a stage boundary unvalidated. It is a
modular monolith: one Spring Boot application composed of one Gradle module per bounded context.

## Constitutional principles

The design is governed by five principles (see [`.specify/memory/constitution.md`](.specify/memory/constitution.md)):

1. **Definition/Instance Immutability** — Definitions are versioned and never mutated in place; running
   Cases pin their Definition versions and do not auto-upgrade.
2. **Reproducible, Bounded AI Participation** *(non-negotiable)* — every AI execution snapshots its
   prompt, model identity, and injected Knowledge; AI is bounded by the D1–D4 decision-authority ladder;
   problem text is always data, never instructions.
3. **System of Record Integrity** — the relational database is the single source of truth; any graph is
   a derived, rebuildable CQRS projection reconstructable from relational data alone.
4. **Workspace Isolation & Provenance-Preserving Evolution** — a workspace is a hard tenant boundary;
   library content arrives by copy-on-subscribe carrying provenance.
5. **Default-Deny Security & Auditable Governance Gates** — cross-boundary movement is default-deny;
   every gate decision produces a tamper-evident (hash-chained) audit record of who/when/under what/why.

## Module map (15 Gradle modules)

Defined in [`settings.gradle`](settings.gradle):

| Module | Role |
|---|---|
| `app` | Spring Boot entrypoint; depends on all bounded contexts; owns app-wide config, migrations runner, scheduling |
| `catalog` | Definition catalog — immutable, semver Definitions (Operations, Templates, Playbooks) and seeding |
| `tenancy` | Workspace tenant isolation, security (JWT/OIDC workspace scoping), RLS role wiring |
| `intake` | Problem submission and Case creation |
| `casecore` | Case lifecycle, progress, and the tamper-evident audit hash-chain (seal/verify) |
| `orchestration` | Workflow execution over the embedded engine; reconciliation; delivered-knowledge triggers |
| `persona` | Stateless AI personas + the provider-agnostic AI Gateway (scope guard, token budget) |
| `artifacts` | Artifact content + revisions, rendered from Template Definitions with version provenance |
| `observability` | Metrics (Micrometer), KPI emission, operational instrumentation |
| `replay` | Byte-identical replay/reproducibility harness for delivered Cases |
| `knowledge` | Governed KnowledgeItem lifecycle + retrieval (OHS) |
| `governance` | Review/approval gates, reopen policy, escalation, retention/access governance |
| `studio` | Catalog Studio — presentation-only server-rendered UI (Thymeleaf + htmx); holds no catalog semantics |
| `projection` | Derived, rebuildable graph read model (`graph_node`/`graph_edge`); sole writer via the `d2os_projector` DB role |
| `test-support` | Shared Testcontainers fixtures and ArchUnit boundary rules |

Module boundaries are mechanically enforced by ArchUnit rules in `test-support`.

## Quickstart

Prerequisites: JDK 21, Docker (for the Postgres/MinIO backing services and for the Testcontainers
integration tests).

1. **Configure environment** — copy the example env file and set real local values:

   ```bash
   cp .env.example .env
   # edit .env: set D2OS_DB_*_PASSWORD, D2OS_STORAGE_SECRET_KEY, D2OS_JWT_SECRET, etc.
   ```

   The full env-var surface is documented in [`.env.example`](.env.example). Secrets are fail-loud
   (no silent defaults) — the app refuses to start when a required secret is unset.

2. **Start the backing services** — Postgres (pgvector, with WAL archiving) and MinIO:

   ```bash
   docker compose up -d          # reads .env automatically
   ```

3. **Run the app** — export the `.env` values into the shell (or use direnv), then:

   ```bash
   ./gradlew :app:bootRun
   ```

   Flyway applies the schema on startup. Health probes are at `/actuator/health/liveness` and
   `/actuator/health/readiness`.

   The **application container image** is produced by Spring Boot's buildpack task, not a hand-written
   Dockerfile:

   ```bash
   ./gradlew :app:bootBuildImage      # builds d2os/app:<version>
   ```

## Test tiers

D2OS has a three-tier test pyramid:

- **Fast JUnit unit tests** (`*Test`) — plain JUnit 5, no Spring, no Docker. Pure domain logic (cycle
  detection, audit canonicalization, escalation resolution, token budgets, scope guards). Run in seconds:

  ```bash
  ./gradlew test        # unit tests + IT; the unit classes need no infra
  ```

- **Testcontainers integration suites** (`*IT`) — real Postgres + MinIO started per run; cover RLS
  isolation, gate escalation, audit hash-chaining, graph equivalence, copy-on-subscribe. **These
  require a running Docker daemon** and run in CI on every push/PR (`.github/workflows/ci.yml`). They
  fail closed: without Docker they error, they do not skip.

- **`@Tag("slow")` benchmarks** — long-running performance ITs (traceability query p95, pin
  resolution) excluded from the ordinary `test` task and run nightly
  (`.github/workflows/nightly.yml`):

  ```bash
  ./gradlew :app:slowTest
  ```

> Note: in some environments the `./gradlew` wrapper cannot download its distribution; use the system
> Gradle at `/opt/gradle/bin/gradle` instead (see [`CLAUDE.md`](CLAUDE.md)).

## Operations

- Disaster recovery: [`ops/dr-drill.md`](ops/dr-drill.md) (runbook), [`ops/dr-rehearsal.sh`](ops/dr-rehearsal.sh)
  (full-shape rehearsal against the real schema).
- Backup verification: [`ops/backup-verification.md`](ops/backup-verification.md) +
  [`ops/backup-verify.sh`](ops/backup-verify.sh) (scheduled restore + audit-chain integrity check).

## Where to look next

- Architecture & phased delivery: [`docs/d2os-implementation-plan.md`](docs/d2os-implementation-plan.md).
- Forward-looking hardening roadmap: [`docs/enhancement-plan.md`](docs/enhancement-plan.md).
- Feature specs, plans, and contracts: [`specs/`](specs/).
- Contributor/agent conventions: [`CLAUDE.md`](CLAUDE.md).
