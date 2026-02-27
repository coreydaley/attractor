# Sprint 010: Full-Featured RESTful API (v1)

## Overview

Nine sprints of feature work have produced a powerful pipeline orchestration system with a browser
UI and a set of functional HTTP endpoints. Those endpoints were designed for the browser: they use
verb-in-URL patterns (`/api/cancel`, `/api/archive`), inconsistent HTTP methods, and form-encoded
bodies — all optimized for convenient `fetch()` calls from the dashboard JS. They are not suitable
for stable external programmatic consumption.

This sprint introduces a first-class, versioned REST API under `/api/v1/` that exposes the full
pipeline capability set: complete CRUD for pipelines, lifecycle control (run, pause, resume,
cancel, rerun), management actions (archive, unarchive, delete), pipeline versioning (iterate,
family history), artifacts (list, download per-file, ZIP download, stage logs, failure reports),
DOT operations (render, validate, generate, fix, iterate — sync and streaming), settings, available
model catalog, and real-time SSE event streams. All endpoints return JSON with a consistent error
envelope and correct HTTP status codes.

The implementation strategy is **additive and zero-regression**: a new `RestApiRouter.kt` file
handles all `/api/v1/` routing and is registered as a single new context in `WebMonitorServer`.
All existing browser-facing routes remain completely unchanged. `WebMonitorServer` receives three
minimal additions to share the SSE broadcast mechanism with the new router. Because `PipelineRunner`
is a Kotlin `object` (singleton), all lifecycle calls use `PipelineRunner.method(registry, store,
onUpdate)` directly — no injection needed. The constraint of no new Gradle dependencies is fully
satisfied; only JDK types already in scope are used.

A companion `docs/api/rest-v1.md` specification document is delivered alongside the code, providing
request/response schema reference and error code definitions for external clients.

## Use Cases

1. **CI/CD integration**: A CI pipeline submits a DOT definition via `POST /api/v1/pipelines`,
   polls `GET /api/v1/pipelines/{id}` until status is `completed` or `failed`, then downloads
   all artifacts via `GET /api/v1/pipelines/{id}/artifacts.zip`.

2. **Shell script orchestration**: A developer script uses `curl` to pause a running pipeline
   (`POST /api/v1/pipelines/{id}/pause`), inspect its stage log
   (`GET /api/v1/pipelines/{id}/stages/{nodeId}/log`), and resume it
   (`POST /api/v1/pipelines/{id}/resume`) without opening the browser.

3. **Failure investigation**: After a pipeline fails, an automated incident reporter fetches
   `GET /api/v1/pipelines/{id}/failure-report` to retrieve structured failure JSON for alerting
   and post-mortem tooling.

4. **Repository portability**: A team exports a known-good pipeline
   (`GET /api/v1/pipelines/{id}/export`) and imports it into another instance
   (`POST /api/v1/pipelines/import`) with explicit `onConflict` control.

5. **Programmatic DOT workflow**: An external tool posts a natural language description to
   `POST /api/v1/dot/generate`, validates the result with `POST /api/v1/dot/validate`, and
   submits it with `POST /api/v1/pipelines`.

6. **Third-party monitoring**: An observability tool subscribes to `GET /api/v1/events` (SSE)
   for all-pipeline updates, or `GET /api/v1/events/{id}` for a single pipeline, to feed
   real-time status into external dashboards.

7. **Pipeline versioning browser**: A client lists `GET /api/v1/pipelines/{id}/family` to present
   all iterations of a pipeline family and navigate version history.

## Architecture

```
WebMonitorServer (minimal changes: restSseClients list + addRestSseClient() + broadcastUpdate fanout)
├── createContext("/") → dashboardHtml                          [UNCHANGED]
├── createContext("/api/pipelines") → allPipelinesJson          [UNCHANGED]
├── createContext("/api/run") → browser submit                  [UNCHANGED]
├── ... (all /api/* routes UNCHANGED) ...
└── createContext("/api/v1/") → restApi.handle(ex)             ← NEW

RestApiRouter (new file)
  constructor(registry: PipelineRegistry, store: RunStore, onUpdate: () -> Unit)
  fun handle(ex: HttpExchange)
    ├── CORS preflight: OPTIONS → 204
    ├── Parse path segments, extract path variables
    ├── Dispatch via when(method, segments) expression
    └── Delegates to:
         ├── PipelineRunner.submit / resubmit / resumePipeline (object, static-style calls)
         ├── PipelineRegistry.get / getOrHydrate / cancel / pause / archive / delete / ...
         ├── RunStore.getSetting / setSetting / getByFamilyId
         ├── Validator.validate / Parser.parse
         ├── ModelCatalog.listModels
         └── LLM generation helpers (same as existing /api/generate, /api/fix-dot, /api/iterate/stream)

────────────────────────────────────────────────────────────────
Endpoint Matrix (REST API v1)
────────────────────────────────────────────────────────────────
Pipeline CRUD
  GET    /api/v1/pipelines               list all (no dotSource in list)
  POST   /api/v1/pipelines               create + run → 201
  GET    /api/v1/pipelines/{id}          get single (includes dotSource)
  PATCH  /api/v1/pipelines/{id}          update dotSource / displayName / originalPrompt
  DELETE /api/v1/pipelines/{id}          delete (non-running only) → 200; running → 409

Pipeline Lifecycle
  POST   /api/v1/pipelines/{id}/rerun    re-run in-place → 200; already running → 409
  POST   /api/v1/pipelines/{id}/pause    pause running → 200; not running → 409
  POST   /api/v1/pipelines/{id}/resume   resume paused → 200; not paused → 409
  POST   /api/v1/pipelines/{id}/cancel   cancel running/paused → 200
  POST   /api/v1/pipelines/{id}/archive  archive → 200
  POST   /api/v1/pipelines/{id}/unarchive unarchive → 200
  POST   /api/v1/pipelines/{id}/iterations create new iteration (new family member) → 201
  GET    /api/v1/pipelines/{id}/family   list family versions with versionNum
  GET    /api/v1/pipelines/{id}/stages   list stages array

Artifacts & Logs
  GET    /api/v1/pipelines/{id}/artifacts              list files (max 500)
  GET    /api/v1/pipelines/{id}/artifacts/{path}       get artifact content (traversal-safe)
  GET    /api/v1/pipelines/{id}/artifacts.zip          download all as ZIP
  GET    /api/v1/pipelines/{id}/stages/{nodeId}/log    stage live.log
  GET    /api/v1/pipelines/{id}/failure-report         failure_report.json

Import / Export
  GET    /api/v1/pipelines/{id}/export   export as ZIP (pipeline-meta.json)
  POST   /api/v1/pipelines/import        import from ZIP (?onConflict=skip|overwrite) → 201

DOT Operations
  POST   /api/v1/dot/render              render DOT → SVG
  POST   /api/v1/dot/validate            lint/validate → {valid, diagnostics}
  POST   /api/v1/dot/generate            generate from prompt (sync) → {dotSource}
  GET    /api/v1/dot/generate/stream     generate from prompt (SSE) → delta events
  POST   /api/v1/dot/fix                 fix broken DOT (sync) → {dotSource}
  GET    /api/v1/dot/fix/stream          fix broken DOT (SSE) → delta events
  POST   /api/v1/dot/iterate             iterate on existing DOT (sync) → {dotSource}
  GET    /api/v1/dot/iterate/stream      iterate on existing DOT (SSE) → delta events

Settings
  GET    /api/v1/settings                all settings as JSON object
  GET    /api/v1/settings/{key}          single setting or 404
  PUT    /api/v1/settings/{key}          update setting (known keys only) → 200

Models
  GET    /api/v1/models                  list all LLM models from ModelCatalog

Events (SSE)
  GET    /api/v1/events                  all-pipeline SSE stream
  GET    /api/v1/events/{id}             single-pipeline SSE stream (filtered)
────────────────────────────────────────────────────────────────

Error Response Shape (ALL endpoints)
  400: {"error": "missing dotSource", "code": "BAD_REQUEST"}
  404: {"error": "pipeline not found", "code": "NOT_FOUND"}
  409: {"error": "pipeline is running", "code": "INVALID_STATE"}
  500: {"error": "internal error", "code": "INTERNAL_ERROR"}

Pipeline JSON Response Shape (GET /api/v1/pipelines/{id})
  {
    "id": "run-1234567890-1",
    "displayName": "My Pipeline",
    "fileName": "pipeline.dot",
    "status": "completed",
    "archived": false,
    "hasFailureReport": false,
    "simulate": false,
    "autoApprove": true,
    "familyId": "run-...",
    "originalPrompt": "...",
    "dotSource": "digraph {...}",       ← single-item GET only, omitted from list
    "startedAt": 1700000000000,
    "finishedAt": 1700000001234,
    "currentNode": null,
    "stages": [
      { "index": 0, "name": "start", "nodeId": "start",
        "status": "completed", "startedAt": ..., "durationMs": 450, "error": null, "hasLog": true }
    ],
    "logs": ["[2026-02-27T...] ✓ start (450ms)", ...]
  }
```

## Implementation Plan

### Phase 1: API spec document and RestApiRouter skeleton (~15%)

**Files:**
- `docs/api/rest-v1.md` — Create
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Create
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (3 additions only)

**Tasks:**

- [ ] Create `docs/api/rest-v1.md`:
  - Document every endpoint: method, path, path params, query params, request body schema, response schema, error codes
  - Include `curl` examples for common operations
  - Document `pipeline-meta.json` schema for import/export ZIP

- [ ] Create `RestApiRouter.kt`:
  - `class RestApiRouter(private val registry: PipelineRegistry, private val store: RunStore, private val onUpdate: () -> Unit)`
  - Private helper `fun jsonResponse(ex: HttpExchange, status: Int, json: String)`:
    - Sets `Content-Type: application/json`, `Access-Control-Allow-Origin: *`
  - Private helper `fun errorResponse(ex: HttpExchange, status: Int, message: String, code: String)`:
    - Calls `jsonResponse(ex, status, """{"error":${js(message)},"code":"$code"}""")`
  - Private helper `fun js(s: String): String`: JSON-escape a string (copy existing pattern from WebMonitorServer)
  - Private helper `fun readBody(ex: HttpExchange): String`: read request body as UTF-8
  - Private helper `fun readJsonBody(ex: HttpExchange): kotlinx.serialization.json.JsonObject?`:
    - Returns null on parse failure
  - Private helper `fun parseQuery(ex: HttpExchange): Map<String, String>`:
    - Parses `ex.requestURI.query`, URL-decodes values
  - Public `fun handle(ex: HttpExchange)`:
    - Handle `OPTIONS` → CORS preflight → 204
    - Parse `ex.requestURI.path.removePrefix("/api/v1").split("/").filter { it.isNotBlank() }` → `segments`
    - Dispatch via `when` on `(ex.requestMethod, segments)` shape
    - Catch-all → `errorResponse(404, "not found", "NOT_FOUND")`
    - Wrap outer in try/catch(Exception) → `errorResponse(500, e.message, "INTERNAL_ERROR")`

- [ ] Modify `WebMonitorServer.kt` (minimal, 3 additions):
  1. Add `internal val restSseClients = CopyOnWriteArrayList<SseClient>()` field
  2. In `broadcastUpdate()`, after fanning out to `sseClients`, also fan out to `restSseClients`
  3. In `init { }` after all existing `createContext()` calls:
     ```kotlin
     val restApi = RestApiRouter(registry, store) { broadcastUpdate() }
     httpServer.createContext("/api/v1/") { ex -> restApi.handle(ex) }
     ```
     Note: `SseClient` must be `internal` (not `private`) so RestApiRouter can use it, or define an equivalent in RestApiRouter

---

### Phase 2: Pipeline CRUD endpoints (~20%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines` — `handleListPipelines(ex)`:
  - `registry.getAll()` → list of `PipelineEntry`
  - For each: `pipelineEntryToJson(entry, full=false)` → omit `dotSource`
  - Return: JSON array
  - Status: 200

- [ ] `POST /api/v1/pipelines` — `handleCreatePipeline(ex)`:
  - Parse JSON body: `dotSource` (required), `fileName`, `simulate=false`, `autoApprove=true`, `originalPrompt`
  - Validate: `dotSource` blank → `errorResponse(400, "dotSource is required", "BAD_REQUEST")`
  - Call `PipelineRunner.submit(dotSource, fileName ?: "", RunOptions(simulate, autoApprove), registry, store, originalPrompt) { onUpdate() }`
  - Return: `{"id": id, "status": "running"}`
  - Status: 201

- [ ] `GET /api/v1/pipelines/{id}` — `handleGetPipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → `entry` or 404
  - Return: `pipelineEntryToJson(entry, full=true)` (includes `dotSource`)
  - Status: 200

- [ ] `PATCH /api/v1/pipelines/{id}` — `handlePatchPipeline(ex, id)`:
  - Parse JSON body: optional `dotSource`, `displayName`, `originalPrompt`
  - `registry.get(id)` → 404 if null
  - If `dotSource` provided and status is `running` or `paused` → 409 "cannot update dotSource while pipeline is running"
  - If `dotSource` provided: `registry.updateDotAndPrompt(id, dotSource, body["originalPrompt"]?.toString() ?: entry.originalPrompt)`
  - Return: updated `pipelineEntryToJson(entry, full=true)`
  - Status: 200

- [ ] `DELETE /api/v1/pipelines/{id}` — `handleDeletePipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status `running` or `paused` → 409 "cannot delete running or paused pipeline"
  - `registry.delete(id)` → `(deleted, logsRoot)`
  - If `logsRoot` non-blank: `java.io.File(logsRoot).deleteRecursively()`
  - Return: `{"deleted": deleted}`
  - Status: 200

- [ ] Private helper `fun pipelineEntryToJson(entry: PipelineEntry, full: Boolean): String`:
  - Build normalized JSON string with all fields from the response shape in Architecture
  - If `full=false`: omit `dotSource` field
  - Calls `entry.state.toJson()` for the stage/log data, then merge top-level fields

---

### Phase 3: Pipeline lifecycle and family endpoints (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `POST /api/v1/pipelines/{id}/rerun` — `handleRerunPipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status `running` → 409 "pipeline is already running"
  - `PipelineRunner.resubmit(id, registry, store) { onUpdate() }`
  - Return: `{"id": id, "status": "running"}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/pause` — `handlePausePipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status not `running` → 409 "pipeline is not running"
  - `registry.pause(id)` → boolean
  - Return: `{"paused": paused}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/resume` — `handleResumePipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status not `paused` → 409 "pipeline is not paused"
  - `PipelineRunner.resumePipeline(id, registry, store) { onUpdate() }` → newId
  - Return: `{"id": newId, "status": "running"}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/cancel` — `handleCancelPipeline(ex, id)`:
  - `registry.get(id)` → 404 if null
  - If status not `running` and not `paused` → 409 "pipeline is not running or paused"
  - `registry.cancel(id)` → boolean
  - Return: `{"cancelled": cancelled}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/archive` — `handleArchivePipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `registry.archive(id)` → boolean
  - Return: `{"archived": archived}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/unarchive` — `handleUnarchivePipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `registry.unarchive(id)` → boolean
  - Return: `{"unarchived": unarchived}`
  - Status: 200

- [ ] `POST /api/v1/pipelines/{id}/iterations` — `handleCreateIteration(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Parse JSON body: `dotSource` (required), `originalPrompt`, `fileName`
  - `PipelineRunner.submit(dotSource, fileName ?: entry.fileName, RunOptions(entry.options.simulate, entry.options.autoApprove), registry, store, originalPrompt ?: "", familyId=entry.familyId) { onUpdate() }`
  - Return: `{"id": newId, "status": "running", "familyId": entry.familyId}`
  - Status: 201

- [ ] `GET /api/v1/pipelines/{id}/family` — `handleGetFamily(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `store.getByFamilyId(entry.familyId)` → sorted list (by createdAt)
  - Map to member objects: `{"id", "displayName", "fileName", "createdAt", "status", "versionNum", "originalPrompt"}`
    - `versionNum` = 1-indexed position in sorted list
    - Limit to first 100
  - Return: `{"familyId": familyId, "members": [...]}`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/stages` — `handleGetStages(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `entry.state.stages.map { stageRecordToJson(it) }`
  - Return: JSON array
  - Status: 200

---

### Phase 4: Artifacts, logs, and failure report endpoints (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines/{id}/artifacts` — `handleListArtifacts(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - If `logsRoot` blank → `{"files": [], "truncated": false}`
  - Walk `File(logsRoot)` recursively, build list of `{"path", "size", "isText"}`
    - `path` is relative to `logsRoot`
    - `isText`: same extension check as existing `/api/run-artifacts` (`log, txt, md, json, dot, kt, py, js, sh, yaml, yml, toml, xml, html, css`)
  - Limit to first 500; `"truncated": true` if more
  - Return: `{"files": [...], "truncated": boolean}`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/artifacts/{path}` — `handleGetArtifact(ex, id, relPath)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `relPath` = path segments after `artifacts/` joined with `/`
  - Resolve `File(logsRoot, relPath).canonicalFile`
  - Strict traversal check: `canonical.path.startsWith(File(logsRoot).canonicalPath)` → else 404
  - If file not found or not a file → 404
  - Set `Content-Type` based on extension (text/plain or application/octet-stream)
  - Stream file bytes
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` — `handleDownloadArtifactsZip(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - If `logsRoot` blank or `File(logsRoot)` doesn't exist → 404
  - Set `Content-Type: application/zip`, `Content-Disposition: attachment; filename="artifacts-{id}.zip"`
  - Use same ZIP streaming logic as existing `/api/download-artifacts` endpoint
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` — `handleGetStageLog(ex, id, nodeId)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Validate `nodeId` does not contain `/` or `..`
  - `File(logsRoot, "$nodeId/live.log")` → 404 if absent
  - Return content as `text/plain`
  - Status: 200

- [ ] `GET /api/v1/pipelines/{id}/failure-report` — `handleGetFailureReport(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - `File(logsRoot, "failure_report.json")` → 404 if absent
  - Return file content as `application/json`
  - Status: 200

---

### Phase 5: Import/Export endpoints (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/pipelines/{id}/export` — `handleExportPipeline(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Build `pipeline-meta.json` bytes: JSON with `id, fileName, dotSource, originalPrompt, familyId, simulate, autoApprove`
  - Create in-memory `ByteArrayOutputStream` + `ZipOutputStream`
  - Add single entry `pipeline-meta.json`
  - Set `Content-Type: application/zip`, `Content-Disposition: attachment; filename="pipeline-{id}.zip"`
  - Stream ZIP bytes
  - Status: 200

- [ ] `POST /api/v1/pipelines/import` — `handleImportPipeline(ex)`:
  - Read body as raw bytes (`ex.requestBody.readBytes()`)
  - Parse `onConflict` query param: `"overwrite"` or `"skip"` (default `"skip"`)
  - Extract `pipeline-meta.json` from ZIP using `ZipInputStream`
  - Parse meta JSON fields: `fileName, dotSource, originalPrompt, familyId, simulate, autoApprove`
  - If `onConflict=skip` and `registry.get(meta.id)` or `registry.getOrHydrate(meta.id, store)` exists:
    - Return `{"status": "skipped", "id": meta.id}`
  - Else: build `StoredRun` from meta; `registry.upsertImported(storedRun, store)`; then submit via `PipelineRunner.submit(...)`
  - Return: `{"status": "started", "id": newId}`
  - Status: 201

---

### Phase 6: DOT operation endpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `POST /api/v1/dot/render` — `handleRenderDot(ex)`:
  - Parse JSON body: `dotSource` (required)
  - Run `dot -Tsvg` process; read stdout; check exit code
  - Return: `{"svg": "..."}` (status 200) or `{"error": "...", "code": "RENDER_ERROR"}` (status 400)
  - If graphviz not found: 400 with "Graphviz not installed"

- [ ] `POST /api/v1/dot/validate` — `handleValidateDot(ex)`:
  - Parse JSON body: `dotSource` (required)
  - `Parser.parse(dotSource)` → on parse failure: return `{"valid": false, "diagnostics": [{"severity": "error", "message": e.message}]}`
  - `Validator.validate(graph)` → `List<Diagnostic>`
  - `valid = diagnostics.none { it.severity == "error" }` (check actual `Diagnostic` type)
  - Return: `{"valid": boolean, "diagnostics": [{"severity", "message", "nodeId"?}]}`
  - Status: 200

- [ ] `POST /api/v1/dot/generate` — `handleGenerateDot(ex)`:
  - Parse JSON body: `prompt` (required)
  - Call same LLM generation logic as existing `/api/generate` (collect full response, not streaming)
  - Return: `{"dotSource": "..."}` or `{"error": "...", "code": "GENERATION_ERROR"}`
  - Status: 200

- [ ] `GET /api/v1/dot/generate/stream` — `handleGenerateDotStream(ex)`:
  - Query param: `prompt` (required)
  - Set SSE headers: `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Access-Control-Allow-Origin: *`
  - Stream same SSE events as existing `/api/generate/stream`: `{"delta": "..."}`, `{"done": true, "dotSource": "..."}`, `{"error": "..."}`
  - Status: 200

- [ ] `POST /api/v1/dot/fix` — `handleFixDot(ex)`:
  - Parse JSON body: `dotSource` (required), `error` (required)
  - Collect full LLM fix response (same backend as `/api/fix-dot`)
  - Return: `{"dotSource": "..."}` or error
  - Status: 200

- [ ] `GET /api/v1/dot/fix/stream` — `handleFixDotStream(ex)`:
  - Query params: `dotSource` (required), `error` (required)
  - Stream SSE events (same as `/api/fix-dot` but query-param initiated)
  - Status: 200

- [ ] `POST /api/v1/dot/iterate` — `handleIterateDot(ex)`:
  - Parse JSON body: `baseDot` (required), `changes` (required)
  - Collect full LLM iterate response
  - Return: `{"dotSource": "..."}`
  - Status: 200

- [ ] `GET /api/v1/dot/iterate/stream` — `handleIterateDotStream(ex)`:
  - Query params: `baseDot` (required), `changes` (required)
  - Stream SSE events (same as existing `/api/iterate/stream`)
  - Status: 200

---

### Phase 7: Settings, Models, and SSE endpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Add handlers

**Tasks:**

- [ ] `GET /api/v1/settings` — `handleGetSettings(ex)`:
  - Known settings keys: `["fireworks_enabled"]`
  - Build JSON object with `store.getSetting(key) ?: defaultValue` for each
  - Return: `{"fireworks_enabled": "true"}`
  - Status: 200

- [ ] `GET /api/v1/settings/{key}` — `handleGetSetting(ex, key)`:
  - If key not in known set → 404
  - `store.getSetting(key)` → null → 404
  - Return: `{"key": key, "value": value}`
  - Status: 200

- [ ] `PUT /api/v1/settings/{key}` — `handlePutSetting(ex, key)`:
  - If key not in known set → 400 "unknown setting key"
  - Parse JSON body: `value` (required string)
  - `store.setSetting(key, value)`
  - Return: `{"key": key, "value": value}`
  - Status: 200

- [ ] `GET /api/v1/models` — `handleGetModels(ex)`:
  - `ModelCatalog.listModels(null)` → `List<ModelInfo>`
  - Map each to `{"id", "provider", "displayName", "contextWindow", "supportsTools", "supportsVision", "supportsReasoning"}`
  - Return: `{"models": [...]}`
  - Status: 200

- [ ] `GET /api/v1/events` — `handleEvents(ex)`:
  - Set SSE headers
  - Create `SseClient(ex)` (use same inner class pattern as WebMonitorServer, or define equivalent)
  - `webMonitorServer.addRestSseClient(client)` (or register via callback passed in constructor)
  - Send initial snapshot: `allPipelinesJson()`... OR receive it via a callback `val getSnapshot: () -> String`
  - Stream until client disconnects; same heartbeat interval (2s)
  - Remove client from list on disconnect

  **Implementation note**: `RestApiRouter` needs access to the snapshot function and the SSE list.
  Extend constructor: `RestApiRouter(registry, store, onUpdate: () -> Unit, getSseSnapshot: () -> String, addSseClient: (SseClient) -> Unit)` OR make `SseClient` internal and pass a typed callback.

- [ ] `GET /api/v1/events/{id}` — `handleEventsSingle(ex, id)`:
  - `registry.getOrHydrate(id, store)` → 404 if null
  - Same SSE setup as global events
  - On each broadcast, send only if payload contains the requested pipeline ID
  - Initial snapshot: `singlePipelineJson(id)` (the pipeline's current state JSON)

---

### Phase 8: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Create
- `src/test/kotlin/attractor/web/RestApiSseTest.kt` — Create

**Tasks:**

**`RestApiRouterTest.kt` (integration tests, Kotest FunSpec):**
- [ ] Setup: spin up `WebMonitorServer` on ephemeral port with an in-memory `PipelineRegistry` and temp SQLite DB; make requests via `java.net.http.HttpClient` (JDK 11+)
- [ ] `GET /api/v1/pipelines` on fresh server → 200 empty array `[]`
- [ ] `POST /api/v1/pipelines` with valid JSON body → 201 with `id` field
- [ ] `POST /api/v1/pipelines` with missing `dotSource` → 400
- [ ] `GET /api/v1/pipelines/{id}` with unknown id → 404
- [ ] `GET /api/v1/pipelines/{id}` with known id → 200 with all required fields
- [ ] `POST /api/v1/pipelines/{id}/pause` on non-running pipeline → 409
- [ ] `DELETE /api/v1/pipelines/{id}` on running pipeline → 409
- [ ] `POST /api/v1/dot/validate` with valid DOT → 200 `{"valid": true, "diagnostics": []}`
- [ ] `POST /api/v1/dot/validate` with invalid DOT → 200 `{"valid": false, "diagnostics": [...]}`
- [ ] `GET /api/v1/settings` → 200 with `fireworks_enabled` key present
- [ ] `PUT /api/v1/settings/fireworks_enabled` with `{"value": "false"}` → 200
- [ ] `GET /api/v1/settings/unknown_key` → 404
- [ ] `GET /api/v1/models` → 200 with non-empty models array
- [ ] `GET /api/v1/pipelines/{id}/failure-report` on pipeline with no logsRoot → 404
- [ ] `GET /api/v1/pipelines/{id}/artifacts/{path}` with path traversal `../../../etc/passwd` → 404
- [ ] `POST /api/v1/pipelines/{id}/iterations` → 201 with same `familyId` as source
- [ ] `GET /api/v1/pipelines/{id}/family` → 200 with members array including source pipeline

**`RestApiSseTest.kt` (SSE tests, Kotest FunSpec):**
- [ ] `GET /api/v1/events` → 200 with `Content-Type: text/event-stream`
- [ ] Initial snapshot event delivered on connect
- [ ] Disconnect handling (client disconnect does not crash server)
- [ ] `GET /api/v1/events/{id}` for unknown id → 404

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `docs/api/rest-v1.md` | Create | Canonical REST API v1 specification with request/response schemas and `curl` examples |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Create | All `/api/v1/` route handlers, router dispatch, response helpers |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | 3 minimal additions: `restSseClients` list, `addRestSseClient()`, fanout in `broadcastUpdate()`, register `/api/v1/` context |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Create | Integration tests: all endpoint families, happy paths + key failure paths |
| `src/test/kotlin/attractor/web/RestApiSseTest.kt` | Create | SSE contract tests: connect, initial snapshot, disconnect |

## Definition of Done

### API Spec
- [ ] `docs/api/rest-v1.md` exists and documents every `/api/v1/*` endpoint with method, path params, request schema, response schema, error codes, and `curl` examples

### Pipelines CRUD
- [ ] `GET /api/v1/pipelines` → 200 JSON array; no `dotSource` in list entries
- [ ] `POST /api/v1/pipelines` → 201 with `id`; missing `dotSource` → 400
- [ ] `GET /api/v1/pipelines/{id}` → 200 with all fields including `dotSource`; unknown → 404
- [ ] `PATCH /api/v1/pipelines/{id}` → 200 updated; `dotSource` while running → 409
- [ ] `DELETE /api/v1/pipelines/{id}` → 200; running pipeline → 409

### Lifecycle
- [ ] `POST .../rerun` → 200; already running → 409
- [ ] `POST .../pause` → 200; not running → 409
- [ ] `POST .../resume` → 200; not paused → 409
- [ ] `POST .../cancel` → 200; not running/paused → 409
- [ ] `POST .../archive` → 200
- [ ] `POST .../unarchive` → 200
- [ ] `POST .../iterations` → 201 with same `familyId`
- [ ] `GET .../family` → 200 with `versionNum` per member (max 100)
- [ ] `GET .../stages` → 200 with stages array

### Artifacts & Logs
- [ ] `GET .../artifacts` → 200 with file list (max 500, truncated flag)
- [ ] `GET .../artifacts/{path}` → 200 file content; path traversal blocked → 404
- [ ] `GET .../artifacts.zip` → application/zip stream
- [ ] `GET .../stages/{nodeId}/log` → 200 plain text; absent → 404
- [ ] `GET .../failure-report` → 200 JSON; absent → 404

### Import/Export
- [ ] `GET .../export` → application/zip with `pipeline-meta.json`
- [ ] `POST /api/v1/pipelines/import` → 201 started or `{"status":"skipped"}`; `onConflict` respected

### DOT
- [ ] `POST /api/v1/dot/render` → 200 `{"svg":"..."}`; graphviz missing → 400
- [ ] `POST /api/v1/dot/validate` → 200 `{"valid":bool,"diagnostics":[...]}`
- [ ] `POST /api/v1/dot/generate` → 200 `{"dotSource":"..."}`
- [ ] `GET /api/v1/dot/generate/stream` → SSE stream
- [ ] `POST /api/v1/dot/fix` → 200 `{"dotSource":"..."}`
- [ ] `GET /api/v1/dot/fix/stream` → SSE stream
- [ ] `POST /api/v1/dot/iterate` → 200 `{"dotSource":"..."}`
- [ ] `GET /api/v1/dot/iterate/stream` → SSE stream

### Settings & Models
- [ ] `GET /api/v1/settings` → 200 with all known settings
- [ ] `GET /api/v1/settings/{key}` → 200 or 404
- [ ] `PUT /api/v1/settings/{key}` → 200; unknown key → 400
- [ ] `GET /api/v1/models` → 200 non-empty models array

### Events
- [ ] `GET /api/v1/events` → SSE with initial snapshot; all-pipeline updates
- [ ] `GET /api/v1/events/{id}` → SSE filtered to single pipeline; unknown id → 404

### Quality
- [ ] All error responses use `{"error":"...","code":"..."}` shape
- [ ] CORS headers (`Access-Control-Allow-Origin: *`) on all endpoints
- [ ] No path traversal via artifact endpoints
- [ ] All existing browser UI routes continue to work unchanged
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] `RestApiRouterTest.kt` passes with 18+ test cases
- [ ] `RestApiSseTest.kt` passes with 4 test cases
- [ ] No regressions in existing functionality

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| JDK HttpServer context prefix overlap between `/api/v1/` and `/api/` | Low | High | JDK dispatches to longest matching prefix; `/api/v1/` is distinct. Test during Phase 1 skeleton. |
| `SseClient` inner class visibility from `RestApiRouter` | Medium | Medium | Change `private inner class SseClient` to `internal inner class`, or extract to a top-level internal class in the package |
| `broadcastUpdate()` is private in WebMonitorServer | Low | Low | Already addressed: `RestApiRouter` receives `onUpdate: () -> Unit` via constructor; `addRestSseClient` exposed via `internal` method |
| `PipelineRunner.resubmit()` / `resumePipeline()` exact signatures | Medium | Medium | Read these methods carefully before implementing; signatures confirmed from codebase |
| SSE single-pipeline filtered endpoint: full snapshot payloads must be filtered post-generation | Medium | Medium | `GET /api/v1/events/{id}` parses the JSON snapshot and extracts the matching pipeline; small overhead per broadcast |
| ZIP import: raw bytes vs multipart conflict | Low | Low | Use `ex.requestBody.readBytes()` + `ZipInputStream` (raw bytes, same as existing `/api/import-run`) |
| `WebMonitorServer` `allPipelinesJson()` access from RestApiRouter | Medium | Low | Pass a `getSseSnapshot: () -> String` lambda to RestApiRouter constructor that delegates to `allPipelinesJson()` |
| `Validator.validate()` and `Diagnostic` type signatures | Low | Low | Verified in codebase (Sprint 009 uses these); check actual field names before implementing |
| Route registration order: `/api/v1/` registered after browser routes | None | None | `HttpServer.createContext` order doesn't matter for prefix dispatch |

## Security Considerations

- **Path traversal**: All artifact endpoint responses use canonical path prefix checks: `File(logsRoot, relPath).canonicalFile.path.startsWith(File(logsRoot).canonicalPath)`. Reject if check fails → 404.
- **ZIP import**: Extract only `pipeline-meta.json`; do not extract arbitrary ZIP entries to disk. Parse JSON from in-memory `ByteArray`.
- **Error messages**: Error responses expose only `message` string and `code` enum. No stack traces in API responses.
- **CORS**: `Access-Control-Allow-Origin: *` (same as existing endpoints). No auth in scope; structure supports future auth middleware insertion at the router's `handle()` entry point.
- **Request body size**: Add explicit size limit in `readBody()`: if `Content-Length` header exceeds a reasonable threshold (e.g., 10 MB), reject with 400.

## Dependencies

- Sprint 007 (completed) — `hasFailureReport`, `failure_report.json` artifact shape
- Sprint 008 (completed) — optimized SSE broadcast; `PipelineState.toJson()` without filesystem probes
- Sprint 009 (completed) — Kotest FunSpec test conventions
- No external dependencies

## Open Questions

1. Should `PATCH /api/v1/pipelines/{id}` be allowed to update `displayName` while a pipeline is
   running? Proposed: yes — `displayName` is cosmetic and doesn't affect execution. Only `dotSource`
   changes are blocked while running.
2. Should `GET /api/v1/events/{id}` maintain its own SSE client list (optimal) or share the global
   list and filter payloads? Proposed: share global list and filter on payload parse — simpler to
   implement, acceptable overhead for a local tool.
3. Should `/api/v1/health` be added in this sprint? Proposed: defer to a future operational sprint;
   not required for the REST API contract.
