# Sprint 013 Intent: In-App Documentation Window

## Seed

We should have a documentation link in the main navigation that opens a comprehensive documentation section in a new window that has tabs for the web application, rest api, and cli application

## Context

Attractor is a pipeline orchestration system with a browser UI, a REST API v1 (35 endpoints), and a Kotlin CLI client. After 12 sprints, the product surface is rich but there is no centralized, discoverable in-app documentation. Users must navigate to GitHub or read raw Markdown files to understand the web UI, REST API, or CLI. A documentation section accessible from the main navigation would close this gap.

The existing navigation bar (`<nav>` in `WebMonitorServer.kt`) contains five items: Monitor, Create, Archived, Import, Settings. Adding a sixth "Docs" link that opens a new browser window with comprehensive tabbed documentation is a purely additive, zero-regression change.

## Recent Sprint Context

- **Sprint 010** — REST API v1: Added `RestApiRouter.kt` with 35 endpoints under `/api/v1/`. Delivered `docs/api/rest-v1.md` (1143-line spec with curl examples). Architecture is additive.
- **Sprint 011** — AI Provider Execution Mode: Settings page extended with execution mode and per-provider toggles. Showed pattern for adding new UI sections within the single-page app.
- **Sprint 012** — Kotlin CLI Client: Built `attractor-cli-*.jar` with `attractor <resource> <verb>` grammar, `--help` at every level, `--output json`, `bin/attractor` shell wrapper. Added CLI quickstart to `README.md`.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Contains all HTML, CSS, and JS for the single-page app. Navigation lives at lines ~1658–1664. `showView()` at ~3020. All UI is Kotlin string templates.
- `docs/api/rest-v1.md` — Canonical REST API spec (1143 lines). Content to adapt for the API tab.
- `README.md` — Has CLI quickstart section added in Sprint 012.
- Sprint 012 command grammar: `pipeline`, `artifact`, `dot`, `settings`, `models`, `events` resources.

## Constraints

- Must follow project conventions (Kotest FunSpec tests, no new Gradle dependencies)
- No new Gradle dependencies — the docs page is served as inline HTML from `WebMonitorServer`
- The nav link opens `window.open('/docs', '_blank')` — a separate browser window/tab
- A new `/docs` route in `WebMonitorServer` serves a standalone HTML page (not embedded in the SPA)
- Content quality: documentation must be complete and accurate, not placeholder filler
- The docs page must be standalone (no dependency on the SPA's JS state, no SSE connection needed)
- Design must match the existing dark-mode aesthetic of the main app

## Success Criteria

1. A "Docs" button (or link) appears in the main navigation of the web application
2. Clicking it opens a new browser window/tab with a standalone documentation page
3. The documentation page has three tabs:
   - **Web App** — How to use the dashboard, pipeline lifecycle, create/generate, settings, etc.
   - **REST API** — All 35 endpoints with method, path, parameters, request/response shapes, curl examples
   - **CLI** — All commands, flags, examples, and installation instructions
4. Documentation content is accurate and comprehensive (not placeholder)
5. The docs page matches the visual style of the main app (dark mode theme, same fonts/colors)
6. The docs page has no external dependencies (no CDN, no fonts loaded from external sources)

## Verification Strategy

- Spec: The content must match `docs/api/rest-v1.md` for REST API section; CLI section must match Sprint 012 command grammar
- Testing: Add Kotest tests that the `/docs` endpoint returns 200 with `Content-Type: text/html` and contains expected section markers for each tab
- Edge cases: `/docs` must work without authentication; long content must be scrollable within each tab panel; tab switching must not require a page reload
- All existing routes (`/`, `/api/*`, `/api/v1/*`) must continue to work (zero-regression)

## Uncertainty Assessment

- Correctness uncertainty: **Low** — navigation pattern is simple; content is already written in existing docs
- Scope uncertainty: **Low** — three tabs, one new route, one nav button; well-bounded
- Architecture uncertainty: **Low** — new `/docs` route follows existing `createContext` pattern; standalone HTML follows same inline-string approach as the main SPA

## Open Questions

1. Should the "Docs" button open in `_blank` (new tab, browser decides window vs tab) or `_blank` + `window.open` with explicit dimensions for a true popup window? Proposed: `window.open('/docs', '_blank')` — lets the browser handle it (typically a new tab), which is the standard web pattern.
2. Should the docs page include a "back to app" link? Proposed: yes, a small "← Back to Attractor" link in the header.
3. Should the REST API tab render the full `rest-v1.md` content (all 35 endpoints with curl examples), or a curated subset? **Confirmed: full content** — all 35 endpoints with curl examples, adapted from `rest-v1.md`.
4. How much CLI content? Proposed: all resource groups with their verbs, flag descriptions, and examples. Match the inline `--help` output + Sprint 012 documentation.
5. Should a fourth "DOT Format" tab be added? **Confirmed: yes** — add a fourth tab covering DOT graph syntax, node attributes, stage configuration, and annotated pipeline examples.

## Interview Results

- **Q1: REST API depth** → Full spec — all 35 endpoints with curl examples
- **Q2: DOT Format tab** → Yes, add a fourth tab ("DOT Format" covering graph syntax, attributes, examples)
