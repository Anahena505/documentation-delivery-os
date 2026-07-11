# Operational Interface Contracts (feature 008)

These are the machine-facing surfaces this feature adds or pins for **operators and orchestrators**,
distinct from the tenant-facing domain API. They are unauthenticated infrastructure surfaces and are
exempt from the OIDC scheme in `auth-and-rbac.yaml` (the `WorkspaceContextFilter` already exempts the
health base path).

## 1. Liveness / readiness probes
| Interface | Contract |
|---|---|
| `GET /actuator/health/liveness` | `200` with `{"status":"UP"}` while the process is alive; failing → orchestrator restarts the pod. Never gated on a workspace token. |
| `GET /actuator/health/readiness` | `200 UP` only when the app can serve (datasource + Flyway validated + migrations applied). `503 OUT_OF_SERVICE` during startup/shutdown → orchestrator stops routing traffic. |

Invariant: an instance MUST report `readiness=UP` before receiving domain traffic; a shutting-down
instance MUST flip to `OUT_OF_SERVICE` before the process exits (graceful drain).

## 2. Metrics scrape
| Interface | Contract |
|---|---|
| `GET /actuator/prometheus` | Prometheus text exposition of all Micrometer meters. Exposed via `management.endpoints.web.exposure.include`. Scraped by the monitoring system. |

Required meter families (names indicative, pinned in tasks):
- `http.server.requests` — RED per route (count, errors, latency histogram).
- `d2os.job.executions{job=…}` · `d2os.job.duration{job=…}` · `d2os.job.failures{job=…}` —
  USE per scheduled job (9 jobs).
- `d2os.projection.lag.seconds` · `d2os.projection.gap.open` · `d2os.gate.sla.breached` ·
  `d2os.rebuild.equivalence.divergent` — domain-threshold gauges backing the alert rules.

Invariant: every threshold the platform computes internally MUST also surface as a scrapeable meter
(FR-005) — no operator-relevant condition is visible *only* as an in-app notification row.

## 3. Alert rules (shipped, not endpoints)
Starter Prometheus alert rules under `deploy/` fire on:
- projection lag > `projection.lag-threshold-seconds` (default 30) sustained;
- open `projection_gap` > `projection.gap-alert-threshold` (default 10);
- any gate past its SLA duration (`GATE_SLA_BREACHED`);
- rebuild-equivalence divergence flagged;
- any `d2os.job.failures` increase.

## 4. CI verification interfaces (not app endpoints)
| Interface | Contract |
|---|---|
| `ci.yml` on PR/push | build + unit + container-capable ITs + ArchUnit + JaCoCo + openapi-diff; **fails closed** (a skipped IT job when the runtime is unavailable is a failure, not a pass). Merge blocked unless green. |
| `nightly.yml` | `@Tag("slow")` benchmarks, DR rehearsal, backup verification; regressions beyond agreed margin flagged. |
| openapi-diff step | compares the springdoc-generated live spec to `specs/**/contracts/*.yaml`; breaking drift fails the build. |

## 5. Deployable-unit contract
| Interface | Contract |
|---|---|
| OCI image | versioned (Gradle `version` → image tag); starts from package + declared config, no build step. |
| Helm values | maps the `.env.example` surface: non-secrets → ConfigMap, credentials → Secret (sourced by the platform's external-secrets mechanism). A missing required secret fails startup loudly. |
