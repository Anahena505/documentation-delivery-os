# CLAUDE.md — Conventions for D2OS

Guidance for contributors and coding agents working in this repository. These are conventions the
codebase already follows; keep to them so module boundaries and auditability stay intact. See
[`README.md`](README.md) for what D2OS is and [`.specify/memory/constitution.md`](.specify/memory/constitution.md)
for the five governing principles.

## Build / verify commands

- **Use the system Gradle at `/opt/gradle/bin/gradle`.** The `./gradlew` wrapper cannot download its
  distribution in this environment, so the wrapper works in CI (where there is network) but not for
  local verification here. In CI workflows the wrapper (`./gradlew`) is used deliberately.
- After a code change, verify a module compiles:
  `/opt/gradle/bin/gradle :<module>:compileJava :<module>:compileTestJava -q`.
- After a change that could affect module boundaries, run the ArchUnit rules:
  `/opt/gradle/bin/gradle :test-support:test --tests ArchitectureRulesTest`.

## Flyway: one global V-namespace

All Flyway migrations across **every** module share a **single global version sequence**, regardless
of which module's `src/main/resources/db/migration/` directory the file lives in. The current highest
version is **V29**; the next migration is **V30**, then V31, V32… — always pick the next unused number
across the whole repo, never per-module. Migrations are additive: never edit an applied migration in
place.

## Additive within existing module boundaries

- **Do not move classes between modules.** All work is additive within the module that already owns the
  seam it touches (metrics in `observability`, security in `tenancy/security`, locks alongside each
  `@Scheduled` job, template rendering in `artifacts`, etc.).
- Prefer extending existing structure over introducing parallel/speculative structure ahead of
  demonstrated need (constitution Principle IV).
- Module boundaries are mechanically enforced by ArchUnit rules in `test-support` — a boundary
  violation fails the build as an ordinary test.

## SPI dependency-inversion pattern (avoiding Gradle cycles)

When module A needs to call into module B but B already depends on A (so B cannot depend back on A
without creating a Gradle module cycle), the codebase inverts the dependency through a **port
interface** placed in a module both already depend on, and wires it with an **optional
`ObjectProvider`**:

- The **port interface** is declared in a shared/upstream module (commonly `casecore`). Example:
  `casecore/.../spi/ArtifactRevisionListener.java` — declared in `casecore`, called by `artifacts`,
  **implemented** by `governance` (`ReopenCandidateService`).
- The caller injects the port as an **optional `ObjectProvider<Port>`**, so a build with no
  implementing bean on the classpath degrades to a harmless no-op instead of failing to wire.
- This is how `artifacts` notifies `governance` without `artifacts` depending on `governance`. Follow
  this same pattern (port in a shared module + `ObjectProvider` injection) for any new cross-module
  call that would otherwise create a cycle — see also `MutatingCaseGuard`'s sibling ports.

## The modular-monolith module list (15)

One Spring Boot app; one Gradle module per bounded context (from `settings.gradle`):

`app` · `catalog` · `tenancy` · `intake` · `casecore` · `orchestration` · `persona` · `artifacts` ·
`observability` · `replay` · `knowledge` · `governance` · `studio` · `projection` · `test-support`

`app` is the bootstrap module depending on all bounded contexts; `test-support` holds Testcontainers
fixtures + the ArchUnit rules. The graph projection (`projection`) is the **sole writer** of the
`graph_node`/`graph_edge` tables via the `d2os_projector` DB role, and its graph is a rebuildable CQRS
projection, never a source of truth (Principle III).

## Dependencies

- Add new dependencies in the relevant module's `build.gradle`. The Spring Boot BOM (applied in the
  root `build.gradle`) manages Spring/Micrometer/Testcontainers versions — **omit the version** for
  BOM-managed artifacts; pin a version only for non-BOM libraries.
- Testcontainers is aligned to `1.20.3` by overriding Boot's managed `testcontainers.version` property
  in the root `build.gradle` (Boot's BOM otherwise pins an older line that cannot negotiate a modern
  Docker API). Do not re-pin Testcontainers artifacts per-module.

## Test tiers (three)

1. **Fast unit** (`*Test`) — plain JUnit 5, no Spring, no Docker; pure domain logic. Sub-second.
   `/opt/gradle/bin/gradle :<module>:test --tests '*Test'`.
2. **Testcontainers integration** (`*IT`) — real Postgres + MinIO; **require a running Docker daemon**
   and run in CI (`.github/workflows/ci.yml`). They fail closed (error, not skip) without Docker. They
   compile locally but only *run* where Docker is available.
3. **`@Tag("slow")` benchmarks** — long-running performance ITs, excluded from `test`, run nightly via
   `:app:slowTest` (`.github/workflows/nightly.yml`). (`@Tag("load")` suites are likewise excluded and
   run via `:app:loadTest`.)

## Security & audit invariants (do not weaken)

- Secrets are **fail-loud**: required env vars (DB app password, `D2OS_JWT_SECRET`, storage secret key)
  have **no default** — startup fails when unset. Never add a silent default.
- Every governance gate decision (approval/rejection/reopen/grant) must produce a tamper-evident,
  hash-chained audit record. When adding fields to an audited decision, include them in
  `AuditChainCanonicalizer` so altering them breaks the seal.
- Personas are stateless: they never call each other, never approve their own output, and never write
  to the Knowledge layer directly (Principle II).
