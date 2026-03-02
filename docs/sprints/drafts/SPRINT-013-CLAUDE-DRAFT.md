# Sprint 013: In-App Documentation Window

## Overview

Twelve sprints of iterative development have given Attractor a rich feature surface: a live-monitoring dashboard, AI-powered pipeline generation, a 35-endpoint REST API, a Kotlin CLI client, and configurable execution modes. Despite all of this capability, there is no centralized, discoverable documentation inside the application itself. Users who want to learn about the web UI workflow, the REST API contract, or the CLI grammar must leave the app and navigate to raw Markdown files or GitHub.

This sprint adds a **Documentation** link to the main navigation bar. Clicking it opens a new browser tab with a standalone, self-contained HTML documentation page. The page is styled to match the existing dark-mode aesthetic of the Attractor UI and is organized into three tabs: **Web App**, **REST API**, and **CLI**. The content covers every major feature of the system — workflow concepts, all 35 REST endpoints with curl examples, and the complete CLI command grammar.

The implementation is entirely additive: one new `createContext("/docs")` call in `WebMonitorServer`, one nav button, and one new large Kotlin string function producing the documentation HTML. Zero changes to the SPA, the REST API, or the test suites beyond the new docs-specific integration tests.

## Use Cases

1. **New user orientation**: A developer opens Attractor for the first time, sees the "Docs" link in the nav, and reads the Web App tab to understand pipeline states, the Generate workflow, and how to import/export.

2. **API integration reference**: A CI/CD engineer building a script against the REST API opens the REST API tab for the complete endpoint listing, request/response shapes, and ready-to-copy curl commands — without leaving the browser.

3. **CLI quickstart**: A developer has just downloaded `attractor-cli.jar` and wants to see all available commands. They open the Docs window, navigate to the CLI tab, and find the full command grammar with examples.

4. **Contextual help**: A user in the middle of a pipeline investigation uses Alt+Tab to the docs window for a quick reminder of what a pipeline status means or what `artifact failure-report` returns, without losing their place in the monitoring view.

5. **Offline documentation**: Because the docs page is entirely served from the Attractor server with no external dependencies (no CDN, no external fonts), it works in air-gapped environments.

## Architecture

```
WebMonitorServer (existing; one addition only)
├── createContext("/") → dashboardHtml               [UNCHANGED]
├── createContext("/api/*") → browser endpoints       [UNCHANGED]
├── createContext("/api/v1/") → RestApiRouter          [UNCHANGED]
└── createContext("/docs") → docsHtml                 ← NEW

Navigation bar (existing SPA HTML; one addition)
  <nav>
    [Monitor] [Create] [Archived] [Import] [Settings] [📖 Docs]←NEW
  </nav>
  onClick: window.open('/docs', '_blank')

Docs page (standalone HTML, served from /docs)
  ┌────────────────────────────────────────┐
  │  Attractor Documentation  ← Back      │
  │  [Web App] [REST API] [CLI]            │  ← tab bar
  ├────────────────────────────────────────┤
  │  Tab content (scrollable)              │
  │                                        │
  └────────────────────────────────────────┘

Tab content sources:
  Web App  → hand-authored documentation of the SPA features
  REST API → adapted from docs/api/rest-v1.md
  CLI      → adapted from README.md CLI section + Sprint 012 grammar
```

The docs page is a **single self-contained HTML document** — inline CSS, inline JS for tab switching, no external resources. The content is authored as Kotlin string templates. This follows the same pattern as the existing SPA.

The `/docs` endpoint is registered **after** all existing contexts in `WebMonitorServer.init{}`. The path `/docs` does not conflict with any existing route.

## Implementation Plan

### Phase 1: Navigation button and route skeleton (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (2 additions)

**Tasks:**
- [ ] Add "Docs" nav button to the navigation HTML:
  ```html
  <button class="nav-btn" onclick="window.open('/docs','_blank')">&#128218; Docs</button>
  ```
  Insert after the Settings button. Note: no `id` needed since clicking it does not call `showView()`.
- [ ] Register docs route in `WebMonitorServer.init{}`:
  ```kotlin
  httpServer.createContext("/docs") { ex ->
      val html = docsHtml().toByteArray(Charsets.UTF_8)
      ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      ex.sendResponseHeaders(200, html.size.toLong())
      ex.responseBody.use { it.write(html) }
  }
  ```
- [ ] Add private `fun docsHtml(): String` stub returning a minimal placeholder (fleshed out in Phase 2)

### Phase 2: Docs page shell and tab infrastructure (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`docsHtml()`)

**Tasks:**
- [ ] Implement the full `docsHtml()` function returning a standalone HTML document with:
  - `<!DOCTYPE html>` declaration, `<meta charset="utf-8">`, `<meta name="viewport">`, `<title>Attractor Docs</title>`
  - Inline CSS matching the existing SPA dark-mode theme (copy CSS variables and base styles):
    - CSS vars: `--bg`, `--surface`, `--border`, `--text`, `--text-muted`, `--text-strong`, etc.
    - Light/dark automatic via `@media (prefers-color-scheme: dark)` (same as SPA)
    - Tab bar styles (`.doc-tab`, `.doc-tab.active`)
    - Content panel styles (`.doc-panel`, `.doc-panel.active`)
    - Code block styles (`.code-block`, `pre`, `code`)
    - Responsive: `max-width: 900px; margin: 0 auto; padding: 0 24px`
  - Header: `<h1>Attractor Docs</h1>` with `<a href="/">← Back to app</a>` in top-right
  - Three tab buttons: "Web App", "REST API", "CLI"
  - Three `<div class="doc-panel">` sections (one per tab), hidden/shown via inline JS
  - Tab switching JS (no external deps):
    ```javascript
    function showTab(name) {
      document.querySelectorAll('.doc-panel').forEach(p => p.style.display='none');
      document.querySelectorAll('.doc-tab').forEach(b => b.classList.remove('active'));
      document.getElementById('panel-'+name).style.display='';
      document.getElementById('tab-'+name).classList.add('active');
    }
    window.onload = function() { showTab('webapp'); };
    ```
  - Each tab panel is scrollable independently via `overflow-y: auto; height: calc(100vh - 120px)`

### Phase 3: Web App tab content (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`docsHtml()`, Web App tab section)

**Tasks:**
- [ ] Write Web App tab covering these sections (hand-authored, accurate):

  **Getting Started**
  - What Attractor is: a pipeline orchestration system where you define stages as a DOT graph and LLMs execute each stage
  - How to start the server: `java -jar coreys-attractor-*.jar`
  - How to access the UI: `http://localhost:8080`

  **Creating a Pipeline**
  - Using the Generate view: type a natural language prompt → LLM generates a DOT graph
  - Editing the DOT source directly in the text editor
  - Preview pane: switch between DOT source and SVG graph view
  - Run button: submits the pipeline; navigates to Monitor view

  **Pipeline States**
  - Table: `idle`, `running`, `paused`, `completed`, `failed`, `cancelled`
  - Visual indicators on the pipeline tab bar

  **Monitoring a Pipeline**
  - Stage list: shows each stage with status, duration, log icon
  - Log panel: live-streaming log output
  - Graph panel: rendered SVG of the pipeline with stage states overlaid
  - Action buttons: Pause, Resume, Cancel, Re-run, Iterate, Archive, Export, Delete
  - Failure report: appears when a stage fails; shows AI-generated diagnosis

  **Pipeline Versions (Iterate)**
  - "Iterate" creates a new version of a pipeline with a modified DOT graph
  - Version history: `<<` / `>>` arrows navigate between family members

  **Archived Pipelines**
  - Archived view shows completed/failed/cancelled pipelines moved out of the main tab bar

  **Import / Export**
  - Export: downloads a ZIP containing `pipeline-meta.json`
  - Import: upload a previously exported ZIP; `onConflict=skip|overwrite` controls

  **Settings**
  - Execution mode (API / CLI)
  - Per-provider enable/disable (Anthropic, OpenAI, Gemini)
  - CLI command template per provider
  - Fireworks display toggle

### Phase 4: REST API tab content (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`docsHtml()`, REST API tab section)

**Tasks:**
- [ ] Write REST API tab, adapted from `docs/api/rest-v1.md`:

  **Overview section**
  - Base URL, request/response format, error envelope, CORS policy
  - Pipeline JSON shape with annotated field table
  - Status values table

  **All 35 endpoints** organized in groups:
  - Pipeline CRUD (5 endpoints)
  - Pipeline Lifecycle (9 endpoints: rerun, pause, resume, cancel, archive, unarchive, iterations, family, stages)
  - Artifacts & Logs (5 endpoints)
  - Import / Export (2 endpoints)
  - DOT Operations (8 endpoints: render, validate, generate, generate/stream, fix, fix/stream, iterate, iterate/stream)
  - Settings (3 endpoints)
  - Models (1 endpoint)
  - Events / SSE (2 endpoints)

  For each endpoint, provide:
  - Method + path in a styled code block
  - Brief description (1-2 sentences)
  - Path / query params table where applicable
  - Request body schema (JSON, where applicable)
  - Response shape snippet
  - One `curl` example

- [ ] Format each endpoint group as a collapsible `<details><summary>` section to keep the page navigable

### Phase 5: CLI tab content (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`docsHtml()`, CLI tab section)

**Tasks:**
- [ ] Write CLI tab covering:

  **Installation**
  - Build: `make cli-jar` → `build/libs/coreys-attractor-cli-devel.jar`
  - Run: `java -jar build/libs/coreys-attractor-cli-devel.jar [command]`
  - Via bin wrapper: `bin/attractor [command]`
  - Global flags: `--host <url>` (default: `http://localhost:8080`), `--output text|json`, `--help`, `--version`

  **Command Grammar**
  - `attractor <resource> <verb> [flags] [args]`

  **Pipeline commands** — table with verb, flags, description, and example
  - `list`, `get`, `create`, `update`, `delete`
  - `rerun`, `pause`, `resume`, `cancel`, `archive`, `unarchive`
  - `stages`, `family`, `watch`, `iterate`

  **Artifact commands** — table
  - `list`, `get`, `download-zip`, `stage-log`, `failure-report`, `export`, `import`

  **DOT commands** — table
  - `generate`, `generate-stream`, `validate`, `render`, `fix`, `fix-stream`, `iterate`, `iterate-stream`

  **Settings commands** — table
  - `list`, `get`, `set`

  **Models command** — `models list`

  **Events command** — `events [id]`

  **Exit codes** — 0=success, 1=runtime error, 2=usage error

  **Examples** — 4-5 representative multi-step workflows (create pipeline, watch, get failure report, etc.)

### Phase 6: Tests (~5%)

**Files:**
- `src/test/kotlin/attractor/web/DocsEndpointTest.kt` — Create

**Tasks:**
- [ ] Kotest `FunSpec` with a real `WebMonitorServer` on an ephemeral port:
  - [ ] `GET /docs` returns `200 OK`
  - [ ] `Content-Type` header is `text/html; charset=utf-8`
  - [ ] Response body contains `<title>Attractor Docs</title>`
  - [ ] Response body contains `Web App` tab label
  - [ ] Response body contains `REST API` tab label
  - [ ] Response body contains `CLI` tab label
  - [ ] Response body contains `/api/v1/pipelines` (REST endpoint reference)
  - [ ] Response body contains `attractor pipeline list` (CLI command reference)
  - [ ] `GET /docs/` (with trailing slash) also returns 200 (or redirect)
  - [ ] All existing routes (`/`, `/api/pipelines`, `/api/v1/pipelines`) still return expected status codes (regression guard)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `Docs` nav button; register `/docs` context; implement `docsHtml()` |
| `src/test/kotlin/attractor/web/DocsEndpointTest.kt` | Create | Integration tests for `/docs` endpoint |

## Definition of Done

- [ ] A "Docs" button appears in the main navigation bar (between Settings and right edge)
- [ ] Clicking "Docs" opens a new browser tab/window with the documentation page
- [ ] Documentation page has three tabs: "Web App", "REST API", "CLI"
- [ ] Tab switching works without page reload
- [ ] "← Back to app" link returns to the Attractor dashboard
- [ ] **Web App tab** covers: Getting Started, Creating a Pipeline, Pipeline States, Monitoring, Iterate/Versions, Archive, Import/Export, Settings
- [ ] **REST API tab** covers: all 35 endpoints with method, path, description, params, response shape, and one curl example each
- [ ] **CLI tab** covers: installation, global flags, all 6 resource groups with command tables and examples
- [ ] Docs page has no external dependencies (no CDN, no external fonts, no external scripts)
- [ ] Docs page visual style matches the main app (dark mode by default, same color scheme)
- [ ] `GET /docs` returns HTTP 200 with `Content-Type: text/html`
- [ ] `DocsEndpointTest` passes (10 test cases)
- [ ] All existing routes continue to return their expected status codes
- [ ] Build passes with no new Gradle dependencies
- [ ] No compiler warnings

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `docsHtml()` function becomes very long (thousands of lines) | High | Low | Long Kotlin string functions are acceptable in this project (SPA HTML is already 2500+ lines); organize into private helper functions per tab (`webAppTabHtml()`, `restApiTabHtml()`, `cliTabHtml()`) |
| REST API tab content becomes stale if endpoints change | Medium | Medium | Source REST API content from `docs/api/rest-v1.md` (canonical spec); note that both must be updated together in future sprints; add a comment in code pointing to the spec file |
| `/docs` path conflicts with a future route | Low | Low | The route `/docs` is generic but unambiguous; it does not match `/docs/api/*` due to JDK HttpServer prefix matching rules; document in code |
| Tab switching JS broken in older browsers | Low | Low | Use only `querySelectorAll`, `classList`, `style.display` — supported in all modern browsers; no ES6+ features |
| Window popup blocked by browser pop-up blockers | Low | Low | Using `window.open()` on user click is not blocked by pop-up blockers; this is the standard pattern |

## Security Considerations

- The `/docs` endpoint serves only static, pre-rendered HTML — no user input is reflected in the response, so no XSS risk.
- No authentication is required for the docs page. This is appropriate since the main app also has no authentication.
- The docs page does not make any API calls back to the server (purely static).

## Dependencies

- Sprint 010 (completed) — REST API v1 spec and endpoint inventory (sourced for REST API tab)
- Sprint 012 (completed) — CLI grammar and examples (sourced for CLI tab)
- No external dependencies

## Open Questions

1. Should the REST API tab include interactive "Try it out" capability (forms that make API calls)? Proposed: **no** — out of scope for this sprint; a future sprint could add this if desired.
2. Should the docs page remember the last-visited tab across page refreshes (via `localStorage`)? Proposed: **yes** — store selected tab in `localStorage` using the same pattern as the SPA tab memory.
3. Should `GET /docs/` (with trailing slash) be handled? JDK `HttpServer` with prefix `/docs` will match both `/docs` and `/docs/anything`. Handle the trailing slash case by serving the same content.
