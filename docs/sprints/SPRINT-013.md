# Sprint 013: In-App Documentation Window

## Overview

Twelve sprints of iterative development have given Attractor a rich product surface: a live-monitoring dashboard, AI-powered pipeline generation, a 35-endpoint REST API, a Kotlin CLI client, configurable execution modes, and a purpose-built DOT pipeline language. Despite this depth, there is no centralized, discoverable documentation inside the application itself. Users who want to understand the web UI workflow, the REST API contract, the CLI grammar, or the DOT graph format must leave the app and navigate to raw Markdown files on GitHub.

This sprint adds a **Docs** link to the main navigation bar. Clicking it opens a new browser tab/window with a standalone, self-contained HTML documentation page served by `WebMonitorServer`. The page is styled to match the existing dark-mode aesthetic and is organized into four tabs: **Web App**, **REST API**, **CLI**, and **DOT Format**. Content is comprehensive: the Web App tab walks through every dashboard view and action; the REST API tab covers all 35 `/api/v1/` endpoints with request/response shapes and curl examples; the CLI tab documents the full command grammar, global flags, and examples; the DOT Format tab explains graph syntax, node and edge attributes, and annotated pipeline patterns.

The implementation is purely additive: one new `createContext("/docs")` call in `WebMonitorServer`, one nav button, and a suite of private helper functions generating the standalone docs HTML. Zero changes to the SPA, the REST API, or any existing test. Port references throughout use the project default of `7070`.

## Use Cases

1. **New user orientation**: A developer opens Attractor for the first time, sees "Docs" in the nav, and reads the Web App tab to understand pipeline states, the Generate workflow, and how to import/export — without opening GitHub.

2. **API integration reference**: A CI/CD engineer building a script opens the REST API tab for the complete endpoint listing, request/response shapes, and ready-to-copy curl commands, without leaving the browser or searching documentation.

3. **CLI quickstart**: A developer with a freshly downloaded `attractor-cli.jar` opens the Docs window, navigates to the CLI tab, and finds the full command grammar with examples and exit codes.

4. **DOT authoring help**: A user building a custom pipeline references the DOT Format tab for node types, edge attributes, conditional branching syntax, and annotated multi-stage examples.

5. **Offline documentation**: Because the docs page is served entirely by the Attractor server with no external dependencies (no CDN, no external fonts), it works in air-gapped environments — the same server that runs the pipelines also serves the documentation.

## Architecture

```
WebMonitorServer (existing; two additions)
├── createContext("/")          → dashboardHtml           [UNCHANGED]
├── createContext("/api/*")     → browser endpoints       [UNCHANGED]
├── createContext("/api/v1/")   → RestApiRouter            [UNCHANGED]
└── createContext("/docs")      → docsHtml()              ← NEW

Navigation bar (existing SPA HTML; one button added)
  <nav>
    [Monitor] [✨ Create] [📁 Archived] [📥 Import] [⚙ Settings] [📖 Docs] ← NEW
  </nav>
  Docs onclick: window.open('/docs', '_blank')

/docs — standalone HTML document (no SPA dependency)
  ┌─────────────────────────────────────────┐
  │  Attractor Docs        ← Back to App   │
  │  [Web App] [REST API] [CLI] [DOT Format]│  ← tab bar
  ├─────────────────────────────────────────┤
  │  Active tab panel (independently scrollable) │
  └─────────────────────────────────────────┘

Route policy for /docs paths:
  GET /docs     → 200 docs HTML
  GET /docs/    → 200 docs HTML (trailing slash tolerated)
  GET /docs/*   → 404 (deeper paths not supported)

Helper functions (all private in WebMonitorServer):
  docsHtml()               → top-level page assembly
  docsPageShell(body)      → HTML skeleton, CSS, JS tab-switcher
  webAppTabContent()       → Web App tab HTML
  restApiTabContent()      → REST API tab HTML (35 endpoints)
  cliTabContent()          → CLI tab HTML
  dotFormatTabContent()    → DOT Format tab HTML

Content sources:
  Web App      → authored from current SPA behavior + README
  REST API     → adapted from docs/api/rest-v1.md (all 35 endpoints, curl examples)
  CLI          → adapted from README.md CLI section + Sprint 012 command grammar
  DOT Format   → authored from README.md pipeline format + annotated examples
```

## Implementation Plan

### Phase 1: Navigation button and route skeleton (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add Docs nav button after the Settings button in the navigation HTML:
  ```html
  <button class="nav-btn" onclick="window.open('/docs','_blank')">&#128218; Docs</button>
  ```
  No `id` needed — it does not call `showView()` and never gets an `active` class.
- [ ] Register the `/docs` context in `WebMonitorServer.init{}` after all existing contexts:
  ```kotlin
  httpServer.createContext("/docs") { ex ->
      val path = ex.requestURI.path
      if (path != "/docs" && path != "/docs/") {
          ex.sendResponseHeaders(404, 0); ex.responseBody.close(); return@createContext
      }
      val html = docsHtml().toByteArray(Charsets.UTF_8)
      ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      ex.sendResponseHeaders(200, html.size.toLong())
      ex.responseBody.use { it.write(html) }
  }
  ```
- [ ] Add `private fun docsHtml(): String` stub (empty body returning placeholder) to confirm compilation

---

### Phase 2: Docs page shell and tab infrastructure (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Implement `private fun docsPageShell(body: String): String` returning a complete standalone HTML document:
  - `<!DOCTYPE html>`, `<meta charset="utf-8">`, `<meta name="viewport">`, `<title>Attractor Docs</title>`
  - Inline CSS matching the existing dark-mode palette (copy CSS variable definitions and base type styles)
  - `@media (prefers-color-scheme: light)` override for light mode consistency
  - Tab bar: `.doc-tab` + `.doc-tab.active` styles
  - Content panel: `.doc-panel` with `overflow-y: auto; height: calc(100vh - 100px)`; sticky tab bar
  - Code block styles: `pre`, `code`, `.code-block`
  - Table styles for parameter reference tables
  - No external resources — all CSS inline, system font stack only
- [ ] Implement tab-switching JS (no external deps):
  ```javascript
  function showTab(name) {
    document.querySelectorAll('.doc-panel').forEach(p => p.style.display = 'none');
    document.querySelectorAll('.doc-tab').forEach(b => b.classList.remove('active'));
    document.getElementById('panel-' + name).style.display = '';
    document.getElementById('tab-' + name).classList.add('active');
    localStorage.setItem('attractor-docs-tab', name);
  }
  window.onload = function() {
    showTab(localStorage.getItem('attractor-docs-tab') || 'webapp');
  };
  ```
- [ ] Implement `private fun docsHtml(): String` assembling:
  ```kotlin
  private fun docsHtml(): String = docsPageShell("""
      <div class="doc-tab-bar">
        <button class="doc-tab" id="tab-webapp" onclick="showTab('webapp')">Web App</button>
        <button class="doc-tab" id="tab-restapi" onclick="showTab('restapi')">REST API</button>
        <button class="doc-tab" id="tab-cli" onclick="showTab('cli')">CLI</button>
        <button class="doc-tab" id="tab-dotformat" onclick="showTab('dotformat')">DOT Format</button>
      </div>
      <div id="panel-webapp"  class="doc-panel">${webAppTabContent()}</div>
      <div id="panel-restapi" class="doc-panel">${restApiTabContent()}</div>
      <div id="panel-cli"     class="doc-panel">${cliTabContent()}</div>
      <div id="panel-dotformat" class="doc-panel">${dotFormatTabContent()}</div>
  """)
  ```
- [ ] Verify compilation and that `GET /docs` returns 200 with four tab labels present

---

### Phase 3: Web App tab content (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`webAppTabContent()`)

**Tasks:**
- [ ] Write `private fun webAppTabContent(): String` covering the following sections:

  **Getting Started**
  - What Attractor is: an AI pipeline orchestration system where stages are defined as a DOT graph
  - Starting the server: `java -jar coreys-attractor-*.jar` or `make run`
  - Opening the UI: `http://localhost:7070`

  **Views (navigation)**
  - **Monitor**: real-time pipeline status; pipeline tabs with stage list and log/graph panels
  - **Create**: DOT textarea + natural language generate; preview pane (Source / Graph tabs); Run Pipeline button
  - **Archived**: table of archived completed/failed/cancelled pipelines
  - **Settings**: execution mode, provider toggles, CLI command templates, fireworks toggle

  **Creating a Pipeline**
  - Option A: paste DOT source directly into the editor
  - Option B: type a prompt and click Generate (LLM generates DOT from description)
  - Fix/Iterate buttons for refining the generated graph
  - Import: upload a previously exported pipeline ZIP

  **Pipeline States**

  | Status | Meaning |
  |--------|---------|
  | `idle` | Created but not yet started |
  | `running` | Executing stages |
  | `paused` | Execution suspended, awaiting resume |
  | `completed` | All stages finished successfully |
  | `failed` | A stage encountered an unrecoverable error |
  | `cancelled` | Manually stopped by user |

  **Monitoring a Pipeline**
  - Stage list with status badges, duration, and log link
  - Log panel: scrollable live log of pipeline events
  - Graph panel: rendered SVG with stage status color overlays
  - Action buttons: Cancel, Pause, Resume, Re-run, Iterate, Download Artifacts, Export, Archive, Delete

  **Pipeline Iterations (Versioning)**
  - Iterate creates a new version of the pipeline in the same family
  - Version navigation: `<<` / `>>` arrows in the pipeline panel header
  - Family history: all versions share the same `familyId`

  **Failure Diagnosis**
  - When a stage fails, Attractor generates a structured failure report via the LLM
  - View via the "View Failure Report" button; download as `failure_report.json`

  **Import / Export**
  - Export: downloads a ZIP archive containing `pipeline-meta.json`
  - Import: upload a ZIP; `onConflict` controls skip or overwrite behavior

---

### Phase 4: REST API tab content (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`restApiTabContent()`)

**Tasks:**
- [ ] Write `private fun restApiTabContent(): String` covering all 35 endpoints, adapted from `docs/api/rest-v1.md`:

  **Overview** — base URL (`http://localhost:7070/api/v1`), JSON request/response format, error envelope shape, CORS policy

  **Pipeline JSON shape** — annotated field table (id, displayName, fileName, status, archived, hasFailureReport, simulate, autoApprove, familyId, originalPrompt, startedAt, finishedAt, currentNode, stages, logs, dotSource)

  **Endpoint groups** — each group wrapped in `<details><summary>Group Name (N endpoints)</summary>`:

  - **Pipeline CRUD** (5): `GET /pipelines`, `POST /pipelines`, `GET /pipelines/{id}`, `PATCH /pipelines/{id}`, `DELETE /pipelines/{id}`
  - **Pipeline Lifecycle** (6): `POST /pipelines/{id}/rerun`, `/pause`, `/resume`, `/cancel`, `/archive`, `/unarchive`
  - **Pipeline Versioning** (3): `POST /pipelines/{id}/iterations`, `GET /pipelines/{id}/family`, `GET /pipelines/{id}/stages`
  - **Artifacts & Logs** (5): `GET /pipelines/{id}/artifacts`, `GET /pipelines/{id}/artifacts/{path}`, `GET /pipelines/{id}/artifacts.zip`, `GET /pipelines/{id}/stages/{nodeId}/log`, `GET /pipelines/{id}/failure-report`
  - **Import / Export** (2): `GET /pipelines/{id}/export`, `POST /pipelines/import`
  - **DOT Operations** (8): `POST /dot/render`, `POST /dot/validate`, `POST /dot/generate`, `GET /dot/generate/stream`, `POST /dot/fix`, `GET /dot/fix/stream`, `POST /dot/iterate`, `GET /dot/iterate/stream`
  - **Settings** (3): `GET /settings`, `GET /settings/{key}`, `PUT /settings/{key}`
  - **Models** (1): `GET /models`
  - **Events / SSE** (2): `GET /events`, `GET /events/{id}`

  For each endpoint: HTTP method + path in a `<code>` block, 1-sentence description, params/body table where applicable, response shape snippet, one `curl` example using `http://localhost:7070`

---

### Phase 5: CLI tab content (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`cliTabContent()`)

**Tasks:**
- [ ] Write `private fun cliTabContent(): String` covering:

  **Build & Installation**
  - `make cli-jar` → `build/libs/coreys-attractor-cli-devel.jar`
  - `java -jar build/libs/coreys-attractor-cli-devel.jar [command]`
  - Shell wrapper: `bin/attractor [command]`

  **Command Grammar**
  - `attractor [--host <url>] [--output text|json] [--help] [--version] <resource> <verb> [flags] [args]`

  **Global Flags**

  | Flag | Default | Description |
  |------|---------|-------------|
  | `--host <url>` | `http://localhost:7070` | Target Attractor server |
  | `--output text\|json` | `text` | Output format |
  | `--help` | — | Show help |
  | `--version` | — | Show version |

  **Resources** — one collapsible `<details>` section per resource:

  - **pipeline** (14 verbs): list, get, create, update, delete, rerun, pause, resume, cancel, archive, unarchive, stages, family, watch, iterate — with flags and example per verb
  - **artifact** (7 verbs): list, get, download-zip, stage-log, failure-report, export, import
  - **dot** (8 verbs): generate, generate-stream, validate, render, fix, fix-stream, iterate, iterate-stream
  - **settings** (3 verbs): list, get, set
  - **models** (1 verb): list
  - **events**: `events` and `events <id>`

  **Exit Codes**

  | Code | Meaning |
  |------|---------|
  | `0` | Success |
  | `1` | API error / connection error / runtime error |
  | `2` | Usage error (missing arg, unknown command, invalid flag) |

  **Workflow Examples** — 3 multi-step examples:
  1. Submit pipeline, watch until completion, download artifacts
  2. Generate DOT from prompt, validate, submit
  3. Get failure report after a failed pipeline

---

### Phase 6: DOT Format tab content (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`dotFormatTabContent()`)

**Tasks:**
- [ ] Write `private fun dotFormatTabContent(): String` covering:

  **Overview** — what a DOT pipeline is; Graphviz DOT syntax extended with Attractor-specific attributes

  **Node Types**

  | Shape / Attribute | Behavior |
  |-------------------|----------|
  | `shape=Mdiamond` | **Start node** — pipeline entry point |
  | `shape=Msquare` | **Exit node** — pipeline terminal |
  | `shape=box` (default) | **LLM stage** — `prompt=` attribute sent to configured model |
  | `shape=diamond` | **Conditional gate** — evaluates edge `condition=` attributes |
  | `shape=hexagon` or `type="wait.human"` | **Human review gate** — pauses pipeline for interactive input |
  | Parallel fan-out | Multiple outgoing edges from a single node execute concurrently |

  **Node Attributes**

  | Attribute | Type | Description |
  |-----------|------|-------------|
  | `prompt` | string | LLM instruction for this stage |
  | `label` | string | Display name in the dashboard |
  | `shape` | string | Determines node behavior (see table above) |
  | `type` | string | Extended type override (e.g., `"wait.human"`) |

  **Edge Attributes**

  | Attribute | Type | Description |
  |-----------|------|-------------|
  | `label` | string | Display label in the graph view |
  | `condition` | string | Boolean expression for conditional gates (e.g., `outcome=success`) |

  **Graph Attributes**

  | Attribute | Description |
  |-----------|-------------|
  | `goal` | Pipeline description shown in the dashboard |
  | `label` | Pipeline display label |

  **Annotated Examples**
  - Simple linear pipeline (3 stages: start → work → exit)
  - Conditional branch (start → gate with `outcome=success`/`outcome!=success` edges)
  - Parallel fan-out (start → two concurrent stages → join → exit)
  - Human review gate (pause for approval before continuing)

  **Tips**
  - The Create view in the web UI can generate a DOT pipeline from a natural language prompt
  - Use `POST /api/v1/dot/validate` to lint a pipeline before running it
  - Graphviz `dot` tool can render pipelines locally: `dot -Tsvg pipeline.dot -o pipeline.svg`

---

### Phase 7: Maintainability (extract helper functions) (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Confirm all content is produced by the five private functions: `docsPageShell()`, `webAppTabContent()`, `restApiTabContent()`, `cliTabContent()`, `dotFormatTabContent()`
- [ ] `docsHtml()` is a thin combinator only — no inline HTML strings
- [ ] No doc-related HTML/CSS leaks into the main `dashboardHtml()` function
- [ ] Each helper function is self-contained (no shared mutable state)

---

### Phase 8: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/web/DocsEndpointTest.kt` — Create

**Tasks:**
- [ ] Kotest `FunSpec` using a real `WebMonitorServer` on an ephemeral port (follow `SettingsEndpointsTest` pattern):

  **Route contract:**
  - [ ] `GET /docs` → 200 OK
  - [ ] `GET /docs/` (trailing slash) → 200 OK
  - [ ] `GET /docs/anything` (sub-path) → 404
  - [ ] `Content-Type` header contains `text/html`

  **Page structure:**
  - [ ] Body contains `<title>Attractor Docs</title>`
  - [ ] Body contains `Web App` tab label
  - [ ] Body contains `REST API` tab label
  - [ ] Body contains `CLI` tab label
  - [ ] Body contains `DOT Format` tab label

  **Content completeness markers:**
  - [ ] Body contains `/api/v1/pipelines` (REST API section present)
  - [ ] Body contains `POST /api/v1/dot/validate` (DOT endpoint present)
  - [ ] Body contains `attractor pipeline list` (CLI commands present)
  - [ ] Body contains `attractor dot generate` (DOT commands present)
  - [ ] Body contains `shape=Mdiamond` (DOT format node types present)
  - [ ] Body contains `window.open` (nav button JS present in root page)

  **Regression guard:**
  - [ ] `GET /` still returns 200 (SPA not broken)
  - [ ] `GET /api/v1/pipelines` still returns 200 (REST API not broken)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add Docs nav button; register `/docs` context; implement `docsHtml()` and four tab content helpers |
| `src/test/kotlin/attractor/web/DocsEndpointTest.kt` | Create | Route status, content-type, tab label, content completeness, and regression tests |

## Definition of Done

### Navigation
- [ ] "Docs" button appears in the main navigation bar (after Settings)
- [ ] Clicking "Docs" opens `/docs` in a new browser tab/window
- [ ] "← Back to App" link on the docs page navigates to the dashboard

### Route
- [ ] `GET /docs` returns HTTP 200 with `Content-Type: text/html; charset=utf-8`
- [ ] `GET /docs/` (trailing slash) returns HTTP 200
- [ ] `GET /docs/anything` returns HTTP 404

### Tabs
- [ ] Docs page has exactly four tabs: Web App, REST API, CLI, DOT Format
- [ ] Tab switching works without page reload
- [ ] Last selected tab is remembered via `localStorage` across page refreshes

### Web App Tab
- [ ] Covers: Getting Started, all navigation views, Creating a Pipeline (textarea and generate), Pipeline States table, Monitoring actions, Iterate/versioning, Failure Diagnosis, Import/Export, Settings

### REST API Tab
- [ ] Covers all 35 `/api/v1/` endpoints organized in 9 endpoint groups
- [ ] Each endpoint has: method, path, description, params/body, response shape, and one curl example
- [ ] All port references use `7070`
- [ ] Endpoint groups are collapsible via `<details>/<summary>`

### CLI Tab
- [ ] Covers: build/install, global flags table, all 6 resource groups with per-command tables and flags
- [ ] Includes 3 workflow examples
- [ ] Includes exit codes table
- [ ] All host examples use `http://localhost:7070`

### DOT Format Tab
- [ ] Covers: node types table, node/edge/graph attributes tables, 4 annotated examples
- [ ] Examples include: linear pipeline, conditional branch, parallel fan-out, human review gate

### Quality
- [ ] Docs page has no external dependencies (no CDN, no external fonts, no external scripts)
- [ ] Visual style matches the main app dark-mode aesthetic
- [ ] No compiler warnings
- [ ] All existing routes (`/`, `/api/*`, `/api/v1/*`) continue to return expected status codes
- [ ] `DocsEndpointTest` passes with all 16 test cases
- [ ] Build passes: `export JAVA_HOME=... && ~/.gradle/wrapper/.../gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `docsHtml()` function body becomes unwieldy | High | Low | Five private helper functions (`docsPageShell`, `webAppTabContent`, `restApiTabContent`, `cliTabContent`, `dotFormatTabContent`) keep each section bounded and maintainable |
| Docs drift from canonical API/CLI behavior as features evolve | Medium | Medium | `docs/api/rest-v1.md` remains the REST API source of truth; note in code comments that `restApiTabContent()` must be updated in lockstep with that file |
| REST API tab is too long to navigate comfortably | Low | Medium | `<details>/<summary>` collapsible groups for each of the 9 endpoint sections; sticky tab bar keeps navigation accessible |
| `/docs` path conflicts with a future route | Low | Low | Path `/docs` is not a prefix of any existing route; deeper paths (`/docs/*`) return 404 by explicit check |
| Browser pop-up blockers intercept `window.open` | Low | Low | `window.open` triggered on direct user click is not subject to pop-up blocking; this is standard browser behavior |
| Kotlin string literal size: very long multi-line strings in `WebMonitorServer.kt` | Medium | Low | The main SPA HTML is already ~2500+ lines of inline strings; the docs functions add similar volume; this is an accepted project pattern |

## Security Considerations

- `/docs` serves only static, pre-rendered HTML with no request-parameter reflection — no XSS risk.
- No authentication required (consistent with the rest of the app which has no auth).
- The docs page makes no API calls back to the server — purely static.
- Explicit sub-path rejection (`GET /docs/*` → 404) prevents unexpected route capture.

## Dependencies

- Sprint 010 (completed) — `docs/api/rest-v1.md` REST API spec (content source for REST API tab)
- Sprint 012 (completed) — CLI grammar (`README.md` CLI section, Sprint 012 command surface)
- No external dependencies

## Open Questions

1. Should a future sprint add "Try it out" forms in the REST API tab (sending live API calls from the docs page)? Proposed: **defer** — out of scope; the docs page is intentionally static.
2. Should `/api/v1/docs` be exposed as machine-readable JSON (OpenAPI/schema)? Proposed: **defer** to a future "API discoverability" sprint — this sprint is user-facing documentation only.
3. Should the docs page include a built-in search/filter input? Proposed: **defer** — browser-native `Ctrl+F` is sufficient; in-page search can be added if requested.
