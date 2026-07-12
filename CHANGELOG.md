# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Production readiness & verification (feature 008).**
  - CI (`.github/workflows/ci.yml`) + nightly benchmark workflow; coverage (JaCoCo), formatting
    (Spotless / google-java-format), and static analysis (SpotBugs).
  - A fast JUnit unit-test layer for core logic (cycle detection, audit canonicalization, escalation
    resolution, token budgets, scope guards).
  - Observability: Micrometer → Prometheus metrics, OpenTelemetry tracing, JSON logs with
    workspace/case/trace correlation, per-job USE metrics, threshold gauges, alert rules, and a
    starter Grafana dashboard.
  - Once-per-cycle scheduled jobs across instances via ShedLock.
  - Deployable image (`bootBuildImage`), graceful shutdown, and a Helm chart with liveness/readiness
    probes.
  - Opt-in OIDC resource-server authentication with per-user identity, role mapping, RBAC, and
    actor-stamped tamper-evident audit records.
  - Real template → artifact content rendering with version provenance.
  - Contract-conformance CI (springdoc + openapi-diff), DR rehearsal + backup-verification scripts.
  - Community health files: LICENSE (MIT), CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, issue/PR
    templates, CODEOWNERS.
- **Fixed the Testcontainers version skew** so the integration suites run in CI (docker-java now
  negotiates the Docker API correctly).

### Notes
- Behavior changes in feature 008 are additive and opt-in; the default posture is unchanged.

<!--
When cutting a release, move Unreleased entries under a new version heading, e.g.:

## [0.1.0] - 2026-XX-XX
-->
