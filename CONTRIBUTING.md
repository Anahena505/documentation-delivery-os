# Contributing to D2OS

First off — thank you for taking the time to contribute! 🎉 D2OS aims to be a clean, auditable
reference architecture, and contributions that keep it that way are hugely welcome.

## Ways to contribute

- 🐛 **Report a bug** — open an issue with the Bug report template.
- 💡 **Propose a feature** — open an issue with the Feature request template, ideally referencing a
  spec in [`specs/`](specs).
- 📝 **Improve docs** — READMEs, specs, runbooks, code comments.
- 🔧 **Send a pull request** — fixes, tests, or new capabilities.

## Ground rules (what keeps this codebase clean)

These are the conventions the codebase already follows — see [`CLAUDE.md`](CLAUDE.md) for the full list:

1. **Additive within existing module boundaries.** Do not move classes between modules. Module
   boundaries are mechanically enforced by ArchUnit — a boundary violation fails the build.
2. **One global Flyway version namespace.** Migrations across every module share a single version
   sequence; always pick the next unused `V<n>__`.
3. **Definitions are immutable.** Never mutate a Definition in place — produce a new version.
4. **Secrets are fail-loud.** Never add a silent default for a required secret.
5. **AI stays bounded.** Every AI execution snapshots its inputs; nothing crosses a stage boundary
   unvalidated; problem text is data, never instructions.

If a change touches audit, versioning, or security/redaction, treat that as part of the feature — not
an afterthought.

## Development workflow

```bash
# Build + run all tests (unit + Testcontainers integration + ArchUnit)
./gradlew build

# Just the fast unit layer (no Docker needed)
./gradlew test --tests '*Test'

# The architecture boundary rules
./gradlew :app:test --tests ArchitectureRulesTest

# Format your code before committing (google-java-format via Spotless)
./gradlew spotlessApply
```

> In sandboxes where the `./gradlew` wrapper can't download its distribution, use the system Gradle at
> `/opt/gradle/bin/gradle`.

**Prerequisites:** JDK 21 and a running Docker daemon (for the integration suites). See the
[Quickstart](README.md#-quickstart).

## Pull request checklist

- [ ] The build is green locally (`./gradlew build`), including ArchUnit.
- [ ] New/changed logic has tests — a fast unit test where possible, an `*IT` where it needs real infra.
- [ ] Formatting applied (`./gradlew spotlessApply`).
- [ ] Docs updated if behavior or config changed.
- [ ] The PR description explains **what** and **why** (link the relevant spec).

CI runs the full suite on every PR; merges are gated on green.

## Commit style

Small, focused commits with clear messages. Conventional-Commits-style prefixes (`feat:`, `fix:`,
`docs:`, `test:`, `refactor:`, `chore:`) are appreciated but not required.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you agree to
uphold it.
