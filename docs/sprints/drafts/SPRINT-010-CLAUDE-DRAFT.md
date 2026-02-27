# Sprint 010: Full-Featured RESTful API

## Overview

Nine sprints of feature work have produced a powerful pipeline orchestration system with a browser
UI and a set of functional HTTP endpoints. Those endpoints were designed for the browser: they use
verb-in-URL patterns (`/api/cancel`, `/api/archive`), inconsistent HTTP methods, and form-encoded
bodies — all optimized for convenient `fetch()` calls from the dashboard JS. They are not suitable
for external programmatic consumption.

This sprint adds a complete **RESTful API layer** under `/api/v1/`. It covers every operation the
application supports: full CRUD for pipelines, lifecycle control (run, pause, resume, cancel,
rerun), management actions (archive, unarchive, delete), pipeline versioning (iterate, family
history), artifacts (list, download, per-file, stage logs, failure reports), DOT operations
(render, generate, fix, iterate, validate), settings, available model catalog, and real-time SSE
streams. All endpoints return JSON with consistent error shapes and use correct HTTP status codes.

The implementation strategy is **additive with zero regression risk**: a new `RestApiRouter.kt`
file implements a minimal in-process router for the `/api/v1/` namespace and is registered as a
single new `HttpServer.createContext("/api/v1/", ...)` in `WebMonitorServer`. No existing routes
are modified. The existing browser UI continues to work exactly as before. The constraint of no new
Gradle dependencies is satisfied — the router uses only JDK's `com.sun.net.httpserver` types
already in scope.

## Use Cases

1. **CI/CD integration**: A CI pipeline submits a DOT pipeline definition via
   `POST /api/v1/pipelines`, polls `GET /api/v1/pipelines/{id}` until status is `completed` or
   `failed`, then downloads artifacts via `GET /api/v1/pipelines/{id}/artifacts.zip`.

2. **CLI tooling**: A developer's shell alias uses `curl -s .../api/v1/pipelines/{id}/pause` to
   pause a long-running pipeline mid-flight without opening the browser.

3. **Pipeline library export/import**: A team exports a known-good pipeline definition
   (`GET /api/v1/pipelines/{id}/export`) and imports it into another instance
   (`POST /api/v1/pipelines/import`).

4. **Failure investigation**: After a pipeline fails, an automated incident reporter hits
   `GET /api/v1/pipelines/{id}/failure-report` to retrieve structured failure JSON for alerting.

5. **Pipeline generation workflow**: An external tool sends a natural language description to
   `POST /api/v1/dot/generate`, receives DOT source, validates it with
   `POST /api/v1/dot/validate`, then submits it with `POST /api/v1/pipelines`.

6. **Monitoring dashboard**: A third-party observability tool subscribes to
   `GET /api/v1/events` (SSE) to receive real-time pipeline state updates and populate its own
   dashboards.

7. **Settings management**: A deployment script reads current settings via
   `GET /api/v1/settings`, then updates `fireworks_enabled` via
   `PUT /api/v1/settings/fireworks_enabled`.

## Architecture

```
WebMonitorServer (existing — UNCHANGED internal logic)
├── createContext("/") → dashboardHtml
├── createContext("/api/pipelines") → existing browser snapshots
├── createContext("/api/run") → existing browser submit
├── ... (all existing /api/* routes UNCHANGED)
└── createContext("/api/v1/") → RestApiRouter.handle(ex)   ← NEW

RestApiRouter (new file: src/main/kotlin/attractor/web/RestApiRouter.kt)
├── Router dispatch via path + method matching
├── Path variable extraction: /api/v1/pipelines/{id}/... → id
├── Request body parsing: JSON or multipart
├── Response helpers: json(200, ...), json(404, error), json(409, error)
└── Delegates to: PipelineRegistry, PipelineRunner, RunStore, Validator, LLM client

API Endpoints — Resource Map
────────────────────────────────────────────────────────────
Pipelines (CRUD + Lifecycle)
  GET    /api/v1/pipelines               → list all
  POST   /api/v1/pipelines               → create + run
  GET    /api/v1/pipelines/{id}          → get single
  PATCH  /api/v1/pipelines/{id}          → update metadata (dotSource, displayName, originalPrompt)
  DELETE /api/v1/pipelines/{id}          → delete (permanent)
  POST   /api/v1/pipelines/{id}/run      → re-run (resubmit)
  POST   /api/v1/pipelines/{id}/pause    → pause
  POST   /api/v1/pipelines/{id}/resume   → resume
  POST   /api/v1/pipelines/{id}/cancel   → cancel
  POST   /api/v1/pipelines/{id}/archive  → archive
  POST   /api/v1/pipelines/{id}/unarchive → unarchive
  POST   /api/v1/pipelines/{id}/iterations → create new iteration (new family member)
  GET    /api/v1/pipelines/{id}/family   → list family versions
  GET    /api/v1/pipelines/{id}/stages   → list stages with status

Artifacts & Logs
  GET    /api/v1/pipelines/{id}/artifacts              → list artifact files
  GET    /api/v1/pipelines/{id}/artifacts/{path}       → get artifact file content
  GET    /api/v1/pipelines/{id}/artifacts.zip          → download all as ZIP
  GET    /api/v1/pipelines/{id}/stages/{nodeId}/log    → stage live.log content
  GET    /api/v1/pipelines/{id}/failure-report         → failure_report.json

Import / Export
  GET    /api/v1/pipelines/{id}/export   → export as ZIP
  POST   /api/v1/pipelines/import        → import from ZIP

DOT Operations
  POST   /api/v1/dot/render              → render DOT to SVG
  POST   /api/v1/dot/validate            → lint/validate DOT
  POST   /api/v1/dot/generate            → generate DOT from prompt (non-streaming)
  GET    /api/v1/dot/generate/stream     → generate DOT from prompt (SSE)
  POST   /api/v1/dot/fix                 → fix broken DOT (non-streaming)
  GET    /api/v1/dot/fix/stream          → fix broken DOT (SSE)
  POST   /api/v1/dot/iterate             → iterate on existing DOT (non-streaming)
  GET    /api/v1/dot/iterate/stream      → iterate on existing DOT (SSE)

Settings
  GET    /api/v1/settings                → get all settings as JSON object
  GET    /api/v1/settings/{key}          → get single setting
  PUT    /api/v1/settings/{key}          → set single setting

Models
  GET    /api/v1/models                  → list available LLM models from catalog

Events (SSE)
  GET    /api/v1/events                  → all-pipeline SSE stream
  GET    /api/v1/events/{id}             → single-pipeline SSE stream (filtered)
────────────────────────────────────────────────────────────

Error Response Shape (ALL endpoints)
  400 Bad Request:     {"error": "message", "code": "BAD_REQUEST"}
  404 Not Found:       {"error": "pipeline not found", "code": "NOT_FOUND"}
  409 Conflict:        {"error": "pipeline is running", "code": "INVALID_STATE"}
  500 Internal Error:  {"error": "...", "code": "INTERNAL_ERROR"}
```

### Router Implementation

`RestApiRouter` is registered on context `/api/v1/`. The JDK's `HttpServer.createContext` matches
by prefix, so `/api/v1/` catches all sub-paths. The router parses `requestURI.path` into segments,
then dispatches via `when` expressions:

```kotlin
// Pseudo-code routing dispatch
val segments = path.removePrefix("/api/v1/").split("/")
when {
    // GET /api/v1/pipelines
    method == "GET" && segments == ["pipelines"] → handleListPipelines(ex)

    // GET /api/v1/pipelines/{id}
    method == "GET" && segments.size == 2 && segments[0] == "pipelines" → handleGetPipeline(ex, id=segments[1])

    // POST /api/v1/pipelines/{id}/pause
    method == "POST" && segments == ["pipelines", id, "pause"] → handlePausePipeline(ex, id)

    // GET /api/v1/pipelines/{id}/artifacts/{path...}
    method == "GET" && segments[0] == "pipelines" && segments[2] == "artifacts" && segments.size > 3
      → handleGetArtifact(ex, id=segments[1], path=segments.drop(3).joinToString("/"))

    else → json(ex, 404, """{"error":"not found","code":"NOT_FOUND"}""")
}
```

### Request Body Parsing

All POST/PUT/PATCH endpoints accept **JSON bodies** with `Content-Type: application/json`.
The router provides a helper `readJsonBody(ex): JsonObject?`.

`POST /api/v1/pipelines/import` and `GET /api/v1/pipelines/{id}/export` continue to use ZIP
(multipart upload and `application/zip` download respectively) — same as the existing endpoints.

### Pipeline Response Shape

`GET /api/v1/pipelines/{id}` and list entries return a normalized JSON object:

```json
{
  "id": "run-1234567890-1",
  "displayName": "My Pipeline",
  "fileName": "pipeline.dot",
  "status": "completed",
  "archived": false,
  "hasFailureReport": false,
  "simulate": false,
  "autoApprove": true,
  "familyId": "run-1234567890-1",
  "originalPrompt": "...",
  "dotSource": "digraph {...}",
  "startedAt": 1700000000000,
  "finishedAt": 1700000001234,
  "currentNode": null,
  "stages": [
    {
      "index": 0,
      "name": "start",
      "nodeId": "start",
      "status": "completed",
      "startedAt": 1700000000100,
      "durationMs": 450,
      "error": null,
      "hasLog": true
    }
  ],
  "logs": ["[2026-02-27T...] Stage started: start", "..."]
}
```

## Implementation Plan

### Phase 1: RestApiRouter skeleton + CORS + error helpers (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Create
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (register new context)

**Tasks:**
- [ ] Create `RestApiRouter.kt`:
  - Constructor: `(registry: PipelineRegistry, runner: PipelineRunner, store: RunStore)`
  - Private helper `fun respond(ex: HttpExchange, status: Int, contentType: String, body: ByteArray)`
  - Private helper `fun jsonResponse(ex: HttpExchange, status: Int, json: String)`:
    - Sets `Content-Type: application/json`, `Access-Control-Allow-Origin: *`
    - Sends response body
  - Private helper `fun errorResponse(ex: HttpExchange, status: Int, message: String, code: String)`:
    - Calls `jsonResponse(ex, status, """{"error":${js(message)},"code":"$code"}""")`
  - Private helper `fun js(s: String): String`: JSON-escape string (use existing `js()` pattern)
  - Private helper `fun readBody(ex: HttpExchange): String`: read request body as UTF-8 string
  - Private helper `fun readJsonBody(ex: HttpExchange): kotlinx.serialization.json.JsonObject?`:
    - Calls `readBody`, parses with `Json.parseToJsonElement(body).jsonObject`, returns null on error
  - Private helper `fun parseQuery(ex: HttpExchange): Map<String, String>`:
    - Parses `ex.requestURI.query` into key-value map (URL-decoded)
  - Public fun `handle(ex: HttpExchange)`:
    - Handles `OPTIONS` method → CORS preflight → 204
    - Parses path segments
    - Dispatches via `when`
    - Catch-all → `errorResponse(ex, 404, "not found", "NOT_FOUND")`
  - Add `import` for `com.sun.net.httpserver.HttpExchange`

- [ ] In `WebMonitorServer.init { }`, add after all existing `createContext()` calls:
  ```kotlin
  val restApi = RestApiRouter(registry, runner, store)
  httpServer.createContext("/api/v1/") { ex ->
      restApi.handle(ex)
  }
  ```
  Note: Need to pass `runner` to WebMonitorServer constructor; currently WebMonitorServer creates
  its own runner internally. Refactor: extract `runner` as a field; pass it to RestApiRouter.

---

### Phase 2: Pipeline CRUD endpoints (~20%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines` — `handleListPipelines(ex: HttpExchange)`:
  - Call `registry.getAll()`
  - For each entry, build a JSON object using `pipelineEntryToJson(entry)`
  - Return JSON array `[{...}, {...}]`
  - Status: 200

- [ ] `POST /api/v1/pipelines` — `handleCreatePipeline(ex: HttpExchange)`:
  - Parse JSON body: `dotSource` (required), `fileName`, `simulate`, `autoApprove`, `originalPrompt`
  - Validate: `dotSource` not blank → 400 if missing
  - Call `runner.submit(dotSource=..., fileName=..., simulate=..., autoApprove=..., originalPrompt=...)`
  - Return: `{"id": "run-...", "status": "running"}`
  - Status: 201

- [ ] `GET /api/v1/pipelines/{id}` — `handleGetPipeline(ex: HttpExchange, id: String)`:
  - Call `registry.getOrHydrate(id, store)` → entry or 404
  - Return `pipelineEntryToJson(entry)` with full state
  - Status: 200

- [ ] `PATCH /api/v1/pipelines/{id}` — `handlePatchPipeline(ex: HttpExchange, id: String)`:
  - Parse JSON body: optional `dotSource`, `displayName`, `originalPrompt`
  - Call `registry.get(id)` → 404 if null
  - If `dotSource` provided: `registry.updateDotAndPrompt(id, dotSource, originalPrompt ?: entry.originalPrompt)`
  - If `displayName` provided: update display name field (add `updateDisplayName()` to registry or update DB directly)
  - Return: updated `pipelineEntryToJson(entry)`
  - Status: 200

- [ ] `DELETE /api/v1/pipelines/{id}` — `handleDeletePipeline(ex: HttpExchange, id: String)`:
  - Call `registry.get(id)` → 404 if null
  - Check status: if `running` or `paused` → 409 INVALID_STATE
  - Call `registry.delete(id)` → get `(deleted, logsRoot)`
  - If `logsRoot` non-blank: `File(logsRoot).deleteRecursively()`
  - Return: `{"deleted": true}`
  - Status: 200

- [ ] Private helper `fun pipelineEntryToJson(entry: PipelineEntry): String`:
  - Builds the normalized pipeline JSON described in Architecture section
  - Uses `entry.state.toJson()` as base, adds `id`, `fileName`, `dotSource`, `originalPrompt`, `familyId`, `simulate`, `autoApprove`

---

### Phase 3: Pipeline lifecycle endpoints (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `POST /api/v1/pipelines/{id}/run` — `handleRerunPipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status is `running` → 409
  - `runner.resubmit(id)`
  - Return: `{"id": id, "status": "running"}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/pause` — `handlePausePipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status is not `running` → 409 with "pipeline is not running"
  - `registry.pause(id)` → boolean
  - Return: `{"paused": true/false}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/resume` — `handleResumePipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status is not `paused` → 409 with "pipeline is not paused"
  - `runner.resumePipeline(id)` → new run ID
  - Return: `{"id": id, "status": "running"}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/cancel` — `handleCancelPipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status is not `running` or `paused` → 409
  - `registry.cancel(id)` → boolean
  - Return: `{"cancelled": true/false}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/archive` — `handleArchivePipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `registry.archive(id)` → boolean
  - Return: `{"archived": true/false}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/unarchive` — `handleUnarchivePipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `registry.unarchive(id)` → boolean
  - Return: `{"unarchived": true/false}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/iterations` — `handleCreateIteration(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Parse JSON body: `dotSource` (required), `originalPrompt`
  - `runner.submit(dotSource=..., familyId=entry.familyId, originalPrompt=..., ...)`
  - Return: `{"id": newId, "status": "running", "familyId": entry.familyId}`
  - Status: 201

---

### Phase 4: Pipeline family, stages, and info endpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines/{id}/family` — `handleGetFamily(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `store.getByFamilyId(entry.familyId)` → list of StoredRun (max 100)
  - For each: build minimal member JSON: `{"id", "displayName", "createdAt", "status", "versionNum", "dotSource", "originalPrompt"}`
  - `versionNum`: position in sorted list (1-indexed)
  - Return: `{"familyId": ..., "members": [...]}`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/stages` — `handleGetStages(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `entry.state.stages.map { stageToJson(it) }`
  - Return: JSON array
  - Status: 200

---

### Phase 5: Artifacts, logs, and failure report endpoints (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines/{id}/artifacts` — `handleListArtifacts(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - If `logsRoot` blank → return `{"files": [], "truncated": false}`
  - Walk `File(logsRoot)` recursively, collect relative paths, sizes, isText (same logic as existing)
  - Limit to first 500 files; `"truncated": true` if more
  - Return: `{"files": [{"path", "size", "isText"}], "truncated": boolean}`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/artifacts/{path}` — `handleGetArtifact(ex, id, path)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Resolve `File(logsRoot, path)`, strict prefix check against `logsRoot`
  - If file not found → 404
  - Set `Content-Type: text/plain` or `application/octet-stream`
  - Stream file bytes
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` — `handleDownloadArtifactsZip(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - If `logsRoot` blank or dir doesn't exist → 404
  - Set `Content-Type: application/zip`, `Content-Disposition: attachment; filename="artifacts-{id}.zip"`
  - Stream ZIP of entire `logsRoot` directory (same logic as existing `/api/download-artifacts`)
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` — `handleGetStageLog(ex, id, nodeId)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Validate `nodeId` does not contain path separators
  - `File(logsRoot, "$nodeId/live.log")` → 404 if absent
  - Return file content as `text/plain`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/failure-report` — `handleGetFailureReport(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `File(logsRoot, "failure_report.json")` → 404 if absent
  - Return file content as `application/json`
  - Status: 200

---

### Phase 6: Export and Import endpoints (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines/{id}/export` — `handleExportPipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Build `pipeline-meta.json` bytes: `{"id", "fileName", "dotSource", "originalPrompt", "familyId", "simulate", "autoApprove"}`
  - Create ZIP in memory: add `pipeline-meta.json`
  - Set `Content-Type: application/zip`, `Content-Disposition: attachment; filename="pipeline-{id}.zip"`
  - Stream ZIP bytes
  - Status: 200

- [ ] `POST /api/v1/pipelines/import` — `handleImportPipeline(ex)`:
  - Read request body as raw bytes (ZIP)
  - Parse ZIP: extract `pipeline-meta.json` → parse fields
  - `onConflict` query param: `"overwrite"` or `"skip"` (default `"skip"`)
  - Same logic as existing `/api/import-run`
  - If `onConflict=skip` and ID exists → return `{"status": "skipped", "id": existingId}`
  - Else: `registry.upsertImported(storedRun)`; `runner.submit(...)` with imported config
  - Return: `{"status": "started", "id": newId}`
  - Status: 201

---

### Phase 7: DOT operation endpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `POST /api/v1/dot/render` — `handleRenderDot(ex)`:
  - Parse JSON body: `dotSource` (required)
  - Run `dot -Tsvg` process (same as existing `/api/render`)
  - Return: `{"svg": "..."}` or `{"error": "...", "code": "RENDER_ERROR"}`
  - Status: 200 or 400

- [ ] `POST /api/v1/dot/validate` — `handleValidateDot(ex)`:
  - Parse JSON body: `dotSource` (required)
  - `Parser.parse(dotSource)` → on ParseException: return diagnostics
  - `Validator.validate(graph)` → list of `Diagnostic`
  - Return: `{"valid": boolean, "diagnostics": [{"severity", "message", "nodeId"?}]}`
  - Status: 200

- [ ] `POST /api/v1/dot/generate` — `handleGenerateDot(ex)`:
  - Parse JSON body: `prompt` (required)
  - Call LLM generation (same backend as existing `/api/generate`)
  - Return: `{"dotSource": "..."}` or error
  - Status: 200

- [ ] `GET /api/v1/dot/generate/stream` — `handleGenerateDotStream(ex)`:
  - Query param: `prompt` (required)
  - Set SSE headers
  - Stream DOT generation events: `{"delta": "..."}`, `{"done": true, "dotSource": "..."}`, or `{"error": "..."}`
  - Status: 200 (SSE)

- [ ] `POST /api/v1/dot/fix` — `handleFixDot(ex)`:
  - Parse JSON body: `dotSource` (required), `error` (required)
  - Call LLM fix (same backend as existing `/api/fix-dot`)
  - Return full fixed DOT: `{"dotSource": "..."}`
  - Status: 200

- [ ] `GET /api/v1/dot/fix/stream` — `handleFixDotStream(ex)`:
  - Query params: `dotSource` (required), `error` (required)
  - Stream fix events via SSE
  - Status: 200 (SSE)

- [ ] `POST /api/v1/dot/iterate` — `handleIterateDot(ex)`:
  - Parse JSON body: `baseDot` (required), `changes` (required)
  - Call LLM iterate (same backend as existing `/api/iterate/stream` but non-streaming)
  - Return: `{"dotSource": "..."}`
  - Status: 200

- [ ] `GET /api/v1/dot/iterate/stream` — `handleIterateDotStream(ex)`:
  - Query params: `baseDot` (required), `changes` (required)
  - Stream iterate events via SSE
  - Status: 200 (SSE)

---

### Phase 8: Settings and Models endpoints (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/settings` — `handleGetSettings(ex)`:
  - Query all known settings from `store`:
    - `fireworks_enabled`
  - Return: `{"fireworks_enabled": "true"}`
  - Status: 200

- [ ] `GET /api/v1/settings/{key}` — `handleGetSetting(ex, key)`:
  - `store.getSetting(key)` → null if not found → 404
  - Return: `{"key": "fireworks_enabled", "value": "true"}`
  - Status: 200

- [ ] `PUT /api/v1/settings/{key}` — `handlePutSetting(ex, key)`:
  - Parse JSON body: `value` (required)
  - Validate known keys; unknown keys → 400
  - `store.setSetting(key, value)`
  - Return: `{"key": key, "value": value}`
  - Status: 200

- [ ] `GET /api/v1/models` — `handleGetModels(ex)`:
  - Call `ModelCatalog.all()` (or equivalent) to get list of available models
  - Return: `{"models": [{"id": "claude-opus-4-6", "provider": "anthropic", "displayName": "..."}]}`
  - Status: 200

---

### Phase 9: SSE event streams (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/events` — `handleEvents(ex)`:
  - Register SSE client in the existing `sseClients` list (need access to it from RestApiRouter)
  - OR: `WebMonitorServer.broadcastUpdate()` already broadcasts to all SSE clients; wire in
    a parallel `restSseClients` list in the same `broadcastUpdate()` call
  - Send initial snapshot: `allPipelinesJson()`
  - Stream events until client disconnects
  - Same heartbeat logic as existing `/events`
  - Status: 200 (SSE)

- [ ] `GET /api/v1/events/{id}` — `handleEventsSingle(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Register client in a per-pipeline SSE client set
  - Send initial pipeline state snapshot
  - Filter broadcasts to only events for the requested pipeline ID
  - Stream until disconnect
  - Status: 200 (SSE)

  **SSE sharing approach**: Add `restSseClients: CopyOnWriteArrayList<SseClient>` to
  `WebMonitorServer`. In `broadcastUpdate()`, fan out to `restSseClients` in addition to
  `sseClients`. `RestApiRouter` exposes a method `addSseClient(client: SseClient)` for the
  router to register clients.

---

### Phase 10: Tests (~5%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Create

**Tasks:**
- [ ] Use embedded `HttpServer` for integration tests:
  - Spin up `WebMonitorServer` on an ephemeral port with an in-memory `PipelineRegistry` and test `RunStore`
  - Make requests via `java.net.http.HttpClient` (JDK 11+ built-in)
  - Assert response codes and JSON shapes
- [ ] Test cases:
  - `GET /api/v1/pipelines` returns 200 with empty array on fresh server
  - `POST /api/v1/pipelines` with valid dotSource returns 201 with `id` field
  - `POST /api/v1/pipelines` with missing dotSource returns 400
  - `GET /api/v1/pipelines/{id}` with unknown id returns 404
  - `GET /api/v1/pipelines/{id}` with known id returns 200 with status field
  - `POST /api/v1/pipelines/{id}/pause` on non-running pipeline returns 409
  - `DELETE /api/v1/pipelines/{id}` on running pipeline returns 409
  - `POST /api/v1/dot/validate` with valid DOT returns `{"valid": true}`
  - `POST /api/v1/dot/validate` with invalid DOT returns `{"valid": false, "diagnostics": [...]}`
  - `GET /api/v1/settings` returns 200 with known setting keys
  - `PUT /api/v1/settings/fireworks_enabled` with `{"value": "false"}` returns 200
  - `GET /api/v1/settings/unknown_key` returns 404
  - `GET /api/v1/models` returns 200 with non-empty models array
  - `GET /api/v1/pipelines/{id}/failure-report` on pipeline without report returns 404

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Create | All `/api/v1/` route handlers, dispatch logic, helpers |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Register `/api/v1/` context; expose `runner` field; wire SSE clients |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Create | Integration tests for REST API endpoints |

## Definition of Done

### Pipelines
- [ ] `GET /api/v1/pipelines` returns 200 JSON array of all pipelines
- [ ] `POST /api/v1/pipelines` accepts JSON body; creates and starts pipeline; returns 201 with `id`
- [ ] `GET /api/v1/pipelines/{id}` returns 200 pipeline JSON or 404
- [ ] `PATCH /api/v1/pipelines/{id}` updates dotSource/displayName/originalPrompt; returns 200
- [ ] `DELETE /api/v1/pipelines/{id}` deletes non-running pipeline; returns 200; running → 409
- [ ] `POST /api/v1/pipelines/{id}/run` reruns pipeline; returns 200; already running → 409
- [ ] `POST /api/v1/pipelines/{id}/pause` pauses running pipeline; not running → 409
- [ ] `POST /api/v1/pipelines/{id}/resume` resumes paused pipeline; not paused → 409
- [ ] `POST /api/v1/pipelines/{id}/cancel` cancels running/paused pipeline
- [ ] `POST /api/v1/pipelines/{id}/archive` archives pipeline
- [ ] `POST /api/v1/pipelines/{id}/unarchive` unarchives pipeline
- [ ] `POST /api/v1/pipelines/{id}/iterations` creates new pipeline with same familyId; returns 201
- [ ] `GET /api/v1/pipelines/{id}/family` returns family members array with versionNum
- [ ] `GET /api/v1/pipelines/{id}/stages` returns stages array
### Artifacts & Logs
- [ ] `GET /api/v1/pipelines/{id}/artifacts` returns file listing (max 500)
- [ ] `GET /api/v1/pipelines/{id}/artifacts/{path}` serves artifact content; path traversal blocked
- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` streams ZIP of all artifacts
- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` returns stage log content or 404
- [ ] `GET /api/v1/pipelines/{id}/failure-report` returns failure_report.json or 404
### Import/Export
- [ ] `GET /api/v1/pipelines/{id}/export` returns ZIP with pipeline-meta.json
- [ ] `POST /api/v1/pipelines/import` imports ZIP; starts pipeline; onConflict respected
### DOT
- [ ] `POST /api/v1/dot/render` returns SVG or error
- [ ] `POST /api/v1/dot/validate` returns `{"valid": boolean, "diagnostics": [...]}`
- [ ] `POST /api/v1/dot/generate` returns generated dotSource
- [ ] `GET /api/v1/dot/generate/stream` streams DOT generation via SSE
- [ ] `POST /api/v1/dot/fix` returns fixed dotSource
- [ ] `GET /api/v1/dot/fix/stream` streams DOT fix via SSE
- [ ] `POST /api/v1/dot/iterate` returns iterated dotSource
- [ ] `GET /api/v1/dot/iterate/stream` streams DOT iteration via SSE
### Settings & Models
- [ ] `GET /api/v1/settings` returns all settings as JSON object
- [ ] `GET /api/v1/settings/{key}` returns single setting or 404
- [ ] `PUT /api/v1/settings/{key}` updates setting; unknown key → 400
- [ ] `GET /api/v1/models` returns available LLM models
### Events
- [ ] `GET /api/v1/events` streams SSE with all-pipeline updates
- [ ] `GET /api/v1/events/{id}` streams SSE filtered to single pipeline
### Quality
- [ ] All error responses use shape `{"error": "...", "code": "..."}`
- [ ] CORS headers on all endpoints
- [ ] No path traversal possible via artifact endpoints
- [ ] All existing browser UI routes continue to work
- [ ] Build passes: `export JAVA_HOME=... && gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] `RestApiRouterTest.kt` passes with 14 test cases
- [ ] No regressions to existing functionality

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| JDK HttpServer context prefix matching causes routing conflicts | Low | High | `/api/v1/` context is more specific than existing `/api/` contexts due to HttpServer's longest-match behavior; test during Phase 1 |
| `PipelineRunner` not accessible from `WebMonitorServer` (currently private) | Medium | Medium | Extract `runner` as internal/protected field; constructor injection into `RestApiRouter` |
| SSE client list in `WebMonitorServer` is private | Medium | Low | Add `fun addRestSseClient(client)` method or expose `restSseClients` list |
| Streaming DOT generation/fix cannot easily be made non-streaming | Low | Low | Accept POST + stream response (same as existing); non-streaming versions use the same LLM backend but collect full response |
| ZIP import endpoint body parsing differs from multipart vs raw bytes | Medium | Low | Accept raw bytes (same as existing implementation) |
| `ModelCatalog` API is not public or doesn't have a list method | Medium | Low | Read the ModelCatalog source; if no list method exists, add a minimal one |
| `PATCH /api/v1/pipelines/{id}` modifying a running pipeline | Low | Medium | Disallow patch of `dotSource` if status is `running` or `paused`; return 409 |
| HttpServer context `/api/v1/` vs existing `/api/` — prefix overlap | Low | Low | JDK HttpServer dispatches to longest matching prefix; `/api/v1/` is distinct from `/api/` |

## Security Considerations

- Path traversal protection on all artifact endpoints: strict `File.canonicalPath.startsWith(logsRoot.canonicalPath)` check (same as existing `/api/run-artifact-file`)
- All JSON bodies size-limited via existing server executor configuration
- No authentication in scope for this sprint; same trust model as existing endpoints
- CORS: `Access-Control-Allow-Origin: *` (same as existing endpoints)
- Settings keys validated against known set; unknown keys rejected with 400

## Dependencies

- Sprint 007 (completed) — `hasFailureReport`, `failure_report.json` artifacts
- Sprint 008 (completed) — optimized SSE broadcast; `PipelineState.toJson()` shape
- Sprint 009 (completed) — Kotest FunSpec test conventions
- No external dependencies

## Open Questions

1. Should `PATCH /api/v1/pipelines/{id}` be allowed on running pipelines? Proposed: disallow
   `dotSource` changes while running; allow `displayName` changes at any time.
2. Should `/api/v1/events/{id}` maintain a separate SSE client list, or share the global one and
   filter on the client side? Proposed: separate list per pipeline ID for minimal bandwidth.
3. Should the `GET /api/v1/pipelines` list include full `dotSource` in list responses? Could be
   expensive for large pipeline sets. Proposed: include in single-pipeline GET, omit from list
   (add a `?full=true` query param for clients that need it).
4. Should the non-streaming DOT endpoints (`POST /api/v1/dot/generate`, etc.) wait for the full
   LLM response before returning? Yes — they are synchronous wrappers around the streaming backends.
