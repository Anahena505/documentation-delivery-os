<!-- Thanks for contributing to D2OS! Please fill this out so reviewers have the context they need. -->

## What & why

<!-- What does this PR change, and why? Link the relevant spec in specs/ or an issue. -->

Closes #

## Type of change

- [ ] 🐛 Bug fix
- [ ] ✨ New feature
- [ ] 📝 Docs
- [ ] ♻️ Refactor / cleanup
- [ ] 🧪 Tests / CI

## Checklist

- [ ] `./gradlew build` is green locally (unit + integration + ArchUnit)
- [ ] New/changed logic has tests (a fast `*Test` where possible, an `*IT` where it needs real infra)
- [ ] `./gradlew spotlessApply` run (formatting)
- [ ] Docs updated if behavior or config changed
- [ ] Additive within existing module boundaries (no classes moved between modules)
- [ ] If a new Flyway migration: it uses the next unused global `V<n>__` version
- [ ] If it touches audit/versioning/security: those concerns are handled, not deferred

## Notes for reviewers

<!-- Anything reviewers should focus on, trade-offs made, or follow-ups intentionally left out. -->
