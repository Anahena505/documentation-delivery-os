# Phase 1 Data Model: Production Readiness & Verification

This feature is mostly operational; it adds **little persistent domain state**. The changes are (a) one
new operational table (ShedLock), (b) additive columns on existing audit records for actor identity,
(c) additive provenance columns on artifact revisions, and (d) several non-persistent runtime entities
(metrics, principals, verification results) that live in memory or CI, not the domain database.

Migrations remain in the single global Flyway V-namespace; new migrations are appended after V29.

---

## Persistent changes

### 1. `shedlock` (new table — operational, not domain)
Coordinates once-per-cycle execution of the 9 scheduled jobs across instances (R5).

| Column | Type | Notes |
|---|---|---|
| `name` | text PK | lock name = job identifier (e.g. `projector-sweep`, `audit-chain-sealer`) |
| `lock_until` | timestamptz | lock held until this instant (bounds a dead holder) |
| `locked_at` | timestamptz | when acquired |
| `locked_by` | text | instance identifier (hostname/pod) for diagnostics |

- **Not** RLS-scoped and **not** a source of truth — it is infrastructure coordination (Principle III:
  operational, rebuildable/irrelevant to domain truth). Owned by the app role; never read by domain
  logic.
- `ProgressHeartbeat` uses per-case lock names (`heartbeat-<caseId>`) so different cases still beat
  concurrently; the other 8 jobs use a single global name each.

### 2. Audit-actor columns (additive, on existing audit/decision records)
Adds authenticated accountability to trust-sensitive decisions (US5, FR-013, Principle V).

Affected tables (existing): the audit-entry / gate-decision / reopen / package-grant records that
today record workspace + action but no person.

| New column | Type | Notes |
|---|---|---|
| `actor_user_id` | text (IdP `sub`) | the authenticated individual who made the decision |
| `actor_role` | text | the role under which they were authorized (must be one they hold) |

- **Additive only** — existing rows keep NULL actor for pre-migration history; new decisions MUST
  populate both (enforced in the service layer, not a NOT NULL constraint that would break history).
- Included in the **audit hash-chain canonicalization** (`AuditChainCanonicalizer`) so actor identity
  is tamper-evident alongside the rest of the decision — a decision's "who" cannot be altered without
  breaking the seal.

### 3. Artifact provenance columns (additive, on artifact revisions)
Records which template version produced an artifact's content (US6, FR-014, Principle I/IV).

| New column | Type | Notes |
|---|---|---|
| `source_template_id` | uuid | the `TemplateDefinition` (definition_asset) rendered from |
| `template_version` | text (semver) | the exact pinned version — provenance, never "latest" |

- Backfilled NULL for pre-existing placeholder artifacts; the new rendering path populates both.
- Feeds the graph projection's `TEMPLATE`/`DEFINITION_VERSION` node + `PRODUCED_FROM` edge types that
  `EquivalenceVerifier` currently skips (now wired).

---

## Runtime (non-persistent) entities

### Authenticated Principal (per request, from OIDC token — not stored)
| Field | Source | Notes |
|---|---|---|
| `userId` | JWT `sub` | authenticated individual |
| `roles` | JWT `roles`/`groups` claim | authorities for `@PreAuthorize` |
| `workspaceId` | JWT `workspace_id` claim | unchanged tenant scoping → RLS binding |

- Established by the OIDC resource-server filter; the existing `WorkspaceContextFilter` continues to
  bind `workspaceId` to `WorkspaceContext`. Cleared at request end. Never persisted except as
  `actor_user_id`/`actor_role` on a decision it authorizes.

### Operational Signal (Micrometer meter — in memory, scraped)
Meter families exposed at `/actuator/prometheus`:
- HTTP RED: request count / errors / latency histogram per route (from Spring MVC observation).
- Job USE (per job): `d2os.job.executions`, `d2os.job.duration`, `d2os.job.failures`,
  `d2os.job.lag.seconds`, `d2os.job.last.success.timestamp`.
- Domain-threshold gauges: projection lag, open `projection_gap` count, gate-SLA-breach count,
  rebuild-equivalence divergence flag.
- Not domain state; no RLS; reset on restart (Prometheus retains the series).

### Change Verification Result (CI, not in the app DB)
The build+test+ArchUnit+coverage+contract outcome attached to a PR/commit in GitHub. Fails closed if
the container runtime is unavailable. Referenced by SC-001/SC-002/SC-010.

### Contract-Conformance Result (CI, not in the app DB)
openapi-diff output comparing the springdoc-generated live spec to the checked-in contracts; breaking
drift fails the build (SC-010).

---

## State transitions

No new domain state machine. Two behavioral rules:

1. **Scheduled-job run claim**: `unclaimed → claimed(lock_until=now+lockAtMostFor) → released` (on
   completion) or `→ expired` (holder died; re-eligible next cycle). Enforced by ShedLock, invisible to
   domain logic.
2. **Artifact content**: `placeholder (legacy) → rendered(from pinned template version)` — a one-way
   transition per revision; a rendered artifact revision is immutable (Principle I) and carries its
   provenance forever.

## Validation rules
- `actor_role` MUST be one of the principal's held roles (a caller cannot record a decision under a
  role they don't have).
- Provenance columns MUST reference a **published, pinned** template version — never a mutable/latest
  pointer (Principle I).
- ShedLock `lockAtMostFor` per job MUST exceed that job's worst-case runtime, or a slow run could be
  double-claimed after expiry — sized per job in tasks.
