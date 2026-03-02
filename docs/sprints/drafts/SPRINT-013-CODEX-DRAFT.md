# Sprint 013: In-App Documentation Window

## Overview

Attractor now has four meaningful product surfaces: the web application, the REST API v1, the Kotlin CLI, and the DOT pipeline language. Those capabilities are documented across multiple files (`README.md`, `docs/api/rest-v1.md`, examples, and code), but users discover them only after leaving the product and searching manually. That creates friction during onboarding and active use.

This sprint adds first-class, in-app documentation entry points by introducing a **Docs** item in the main navigation and a standalone `/docs` page that opens in a new tab/window. The docs page is a self-contained HTML document served by `WebMonitorServer`, with tabbed content for **Web App**, **REST API**, **CLI**, and **DOT Format**.

The implementation is additive and low-risk: no changes to existing run lifecycle behavior, no external assets/CDNs, and no new Gradle dependencies. The docs page will share the existing dark visual language and remain independent from SPA runtime state (no SSE subscription, no dependency on monitor page JavaScript state).

## Use Cases

1. **New user onboarding**: A first-time user clicks `Docs` from the nav and gets a complete product guide without leaving the app.
2. **Operator API lookup**: While monitoring runs, a user opens REST endpoint details and curl examples in a second tab.
3. **CLI command reference**: A user running terminal commands keeps the CLI tab open for flags/examples without browsing GitHub.
4. **DOT authoring help**: A user building pipelines references syntax, node attributes, and examples from the DOT tab.
5. **Zero-context handoff**: A teammate can run Attractor locally and access complete docs at `/docs` with no external internet dependency.

## Architecture

### High-Level Flow

```text
Main app nav (/) 
  -> Docs button click
      -> window.open('/docs', '_blank')
          -> GET /docs
              -> WebMonitorServer returns standalone HTML page
                  -> Client-side tab switcher (Web App / REST API / CLI / DOT Format)
```

### Route + UI Design

- Existing route `/` remains the SPA dashboard.
- New route `/docs` serves a standalone HTML document.
- Main nav gains a sixth item: `Docs`.
- `Docs` action uses `window.open('/docs', '_blank')`.
- `/docs` includes a compact header with:
  - title (`Attractor Documentation`)
  - optional `Back to App` link to `/`
- Tab switching is local JS only (no network requests after initial load).

### Content Source Strategy

| Docs Tab | Primary Source | Coverage Requirement |
|---|---|---|
| Web App | `README.md` + current SPA behavior in `WebMonitorServer.kt` | Views, lifecycle actions, create/generate flow, archive/import, settings |
| REST API | `docs/api/rest-v1.md` | Full v1 coverage (all 35 endpoints), request/response shape, curl examples |
| CLI | `README.md` CLI section + `src/main/kotlin/attractor/cli/commands/*` | Global flags, resource commands, examples, output modes |
| DOT Format | `README.md` pipeline format + `examples/*.dot` | Graph syntax, node/edge attributes, interpolation, branching/human/parallel patterns |

### Implementation Shape

- Keep implementation in `WebMonitorServer.kt` to match current project pattern of inline HTML/CSS/JS.
- Extract long docs-page markup into dedicated helper function(s) for maintainability.
- Prefer deterministic static content generated from repo-owned canonical docs during request handling (string templates only).

## Implementation Plan

### Phase 1: Docs Route + Navigation Entry (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify

**Tasks:**
- [ ] Add `Docs` button/link in main nav next to existing Monitor/Create/Archived/Import/Settings.
- [ ] Add JS handler that opens `/docs` via `window.open('/docs', '_blank')`.
- [ ] Register new HTTP route `GET /docs` in `WebMonitorServer`.
- [ ] Return `200` with `Content-Type: text/html; charset=utf-8`.

### Phase 2: Standalone Docs Page Shell (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify

**Tasks:**
- [ ] Implement standalone HTML document renderer for `/docs`.
- [ ] Add dark-theme CSS consistent with dashboard palette/typography.
- [ ] Add tab bar and four panels: Web App, REST API, CLI, DOT Format.
- [ ] Implement client-side tab switching (`active` tab + `display` toggles).
- [ ] Ensure long sections are scrollable and usable on desktop/mobile widths.
- [ ] Add `Back to App` link (`/`).

### Phase 3: Documentation Content Assembly (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify
- `README.md` - Modify (if accuracy fixes are required)
- `docs/api/rest-v1.md` - Modify (only if inconsistencies discovered)

**Tasks:**
- [ ] Author complete **Web App** documentation covering dashboard workflow, create/generate/fix/iterate actions, run controls, archives/import, and settings.
- [ ] Port **REST API** content from `docs/api/rest-v1.md` into docs-page-friendly sections while preserving endpoint completeness and example fidelity.
- [ ] Author complete **CLI** section matching current command grammar and flags.
- [ ] Author complete **DOT Format** section: grammar essentials, required nodes, attributes, conditions, and annotated examples.
- [ ] Add clear disclaimer for defaults where behavior differs by environment (port, model settings).
- [ ] Validate that docs do not claim unsupported commands/routes.

### Phase 4: Maintainability Refactor for Docs Markup (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify

**Tasks:**
- [ ] Split docs rendering into helper builders (page shell + per-tab content fragments) to avoid a monolithic string block.
- [ ] Centralize repeated HTML helpers (code block renderer, section heading renderer) if needed.
- [ ] Keep zero dependency footprint (no markdown parser, no templating library).

### Phase 5: Tests for Route and Content Contracts (~15%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerDocsTest.kt` - Create
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` - Modify (only if useful for shared helpers)

**Tasks:**
- [ ] Add test: `GET /docs` returns `200`.
- [ ] Add test: `/docs` response has `Content-Type` containing `text/html`.
- [ ] Add test: response contains tab labels (`Web App`, `REST API`, `CLI`, `DOT Format`).
- [ ] Add test: response contains stable anchor markers for key sections (e.g., endpoint summary marker, CLI command marker).
- [ ] Add regression test: root page `/` still renders nav with existing items + `Docs`.

### Phase 6: UX/Regression Validation (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` - Modify (if route interactions need additional regression checks)

**Tasks:**
- [ ] Verify `/`, `/api/*`, `/api/v1/*`, and `/events` behavior unchanged.
- [ ] Validate tab switching without full-page reload.
- [ ] Validate docs page renders with JavaScript disabled fallback (first tab visible) where practical.
- [ ] Validate no external network calls from `/docs` (no CDN scripts/fonts).

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add Docs nav action, `/docs` route, standalone docs HTML/CSS/JS, tabbed content |
| `src/test/kotlin/attractor/web/WebMonitorServerDocsTest.kt` | Create | Validate `/docs` route status/content-type/content markers and root nav presence |
| `README.md` | Modify (optional) | Align canonical web/CLI/DOT docs if mismatches are found during drafting |
| `docs/api/rest-v1.md` | Modify (optional) | Correct API docs if endpoint/content mismatches are discovered |

## Definition of Done

- [ ] Main navigation contains a visible `Docs` entry.
- [ ] Clicking `Docs` opens `/docs` in a new browser tab/window.
- [ ] `GET /docs` returns `200` and HTML content type.
- [ ] `/docs` contains four tabs: `Web App`, `REST API`, `CLI`, `DOT Format`.
- [ ] REST API tab covers all 35 v1 endpoints with accurate method/path/examples.
- [ ] CLI tab documents current grammar, global flags, resource commands, and representative examples.
- [ ] DOT tab documents syntax, node/edge/graph attributes, and working examples.
- [ ] Docs page uses repo-local assets only (no CDN/external script/font dependency).
- [ ] Existing dashboard and API behavior remain unchanged (zero regression).
- [ ] New docs tests pass under `make test`.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Docs drift from canonical API/CLI behavior over time | Medium | High | Treat `docs/api/rest-v1.md` and CLI help output as source-of-truth; add stable content marker tests |
| `WebMonitorServer.kt` becomes harder to maintain due to large inline strings | High | Medium | Split into helper render functions and isolate docs sections |
| Tab content becomes too large for comfortable browsing | Medium | Medium | Use per-panel scroll regions, sticky tab bar, and section index links |
| Regression in existing nav interactions | Low | Medium | Add root nav regression test and manual smoke pass |
| Browser popup blocking for `window.open` | Medium | Low | Trigger from direct click event and gracefully allow `_blank` new-tab behavior |

## Security Considerations

- `/docs` serves static documentation only; no sensitive data and no privileged operations.
- Avoid dynamic HTML injection from request parameters.
- Ensure rendered content is escaped if sourced from mutable strings.
- Keep `Content-Type` explicit and avoid inline user-controlled script content.

## Dependencies

- Prior sprint outputs:
  - Sprint 010 REST API v1 documentation (`docs/api/rest-v1.md`)
  - Sprint 012 CLI command surface (`README.md`, `src/main/kotlin/attractor/cli/commands/*`)
- No new external libraries or services.

## Open Questions

1. Should `/docs` include in-page search (`Ctrl+F` only vs built-in filter input)?
   - Proposed: start with browser-native search only to keep scope bounded.
2. Should docs content be fully hand-authored HTML or partially generated from canonical markdown at build time?
   - Proposed: hand-authored structured HTML in this sprint, with explicit source alignment checks.
3. Should we expose `/api/v1/docs` JSON metadata for machine-readable command/endpoint catalogs?
   - Proposed: defer; this sprint is user-facing documentation UX, not schema publication.
