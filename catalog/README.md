# /catalog

All `DefinitionAsset`-typed catalog content for D2OS (§4.2, §13): `TemplateDefinition`, `PersonaDefinition`, `PlaybookDefinition`, `WorkflowDefinition`, `RuleDefinition`, `RubricDefinition`, `PromptDefinition`.

Populated starting Phase 1 (`feat/catalog-v1-initiation-case`) per the Phase 1 catalog-authoring tasks in `docs/d2os-implementation-plan.md` (epic E1.8). See `docs/catalog-audit.md` for the source classification and `docs/catalog-authoring-backlog.md` for the authoring order.

- `templates/` — revised + new `TemplateDefinition`s (supersedes the raw v0 files under `docs/`)
- `personas/` — `PersonaDefinition`s
- `playbooks/` — `PlaybookDefinition`s
- `workflows/` — `WorkflowDefinition`s (BPMN) and `CaseTypeDefinition`s
- `rules/` — `RuleDefinition`s (DMN)
