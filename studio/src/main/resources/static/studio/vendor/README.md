# Vendored JS islands (tasks.md T002)

Research R1 (specs/006-catalog-studio/research.md): the studio is server-rendered (Thymeleaf +
htmx), with **no Node/npm build chain**. The two rich-client "islands" and the htmx runtime are
vendored as plain, version-pinned static files checked into this directory — never installed via
a package manager, never bundled.

**This sandboxed environment cannot reach the public internet to fetch these files.** The
directory structure below and this README are the T002 deliverable; the actual third-party
JS/CSS files still need to be vendored by a human (or a later step with network access) before
any Thymeleaf template can reference them. Do not fabricate placeholder/empty files pretending to
be the real libraries — an empty `htmx.min.js` would silently no-op every htmx attribute in the
templates rather than failing loudly.

## Files to vendor, exact expected paths

| Library | Version (pin) | Expected file(s) under this directory | Source |
|---|---|---|---|
| htmx | 1.9.12 | `htmx/htmx.min.js` | `https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js` |
| diff2html | 3.4.51 | `diff2html/diff2html.min.js`, `diff2html/diff2html.min.css` | `https://cdn.jsdelivr.net/npm/diff2html@3.4.51/bundles/js/diff2html.min.js`, `https://cdn.jsdelivr.net/npm/diff2html@3.4.51/bundles/css/diff2html.min.css` |
| dmn-js | 16.4.0 | `dmn-js/dmn-modeler.development.js`, `dmn-js/assets/dmn-js-shared.css`, `dmn-js/assets/dmn-js-drd.css`, `dmn-js/assets/dmn-js-decision-table.css`, `dmn-js/assets/dmn-js-decision-table-controls.css`, `dmn-js/assets/dmn-js-literal-expression.css`, `dmn-js/assets/diagram-js.css` | `https://unpkg.com/dmn-js@16.4.0/dist/dmn-modeler.development.js` (+ the paired `assets/` CSS files from the same npm package `dist/assets/`) |

Pin versions exactly as listed above when vendoring (research R1: "static assets are vendored and
version-pinned"). If a newer version is deliberately adopted later, update this table in the same
commit as the file swap so the pin documented here never drifts from what is actually checked in.

## Why these exact filenames

The later Thymeleaf pages (T011 draft/editor pages, T015 `review.html`) will reference these
libraries by exactly the relative paths above under `/studio/vendor/...` (Spring Boot serves
`src/main/resources/static/**` at the app root, so a template reference would look like
`/studio/vendor/htmx/htmx.min.js`). Keeping the *names* fixed now — even though the *content*
isn't vendored yet — means T011/T015 can be written against a stable contract and only need the
files dropped in to become live, no template changes required.

## What "done" means for this task

- [x] Directory structure exists (`vendor/htmx/`, `vendor/diff2html/`, `vendor/dmn-js/`,
      `vendor/dmn-js/assets/`) — created as `.gitkeep`-equivalent placeholders alongside this file.
- [ ] **NOT done — flagged for a human/later step with network access**: the actual minified
      JS/CSS files themselves are not present. `gradle compileJava` does not depend on these
      (they are static assets, not compiled), so their absence does not block Phase 1-2 or any
      later Java compilation — but any Thymeleaf template that `<script src="...">`s them will
      404 in a browser (and any MockMvc/HtmlUnit test in T012/T019 that actually loads the JS,
      as opposed to just asserting the route renders, will need these files present) until they
      are vendored.
