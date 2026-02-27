# Corey's Attractor REST API v1

## Overview

The REST API v1 is mounted at `/api/v1/` and provides programmatic access to all pipeline management, DOT generation, validation, settings, model catalog, and real-time event streaming capabilities.

**Base URL:** `http://localhost:<port>/api/v1`

**Default port:** `8080` (set via CLI or configuration)

### Request / Response format

All request and response bodies are JSON (`Content-Type: application/json`) unless noted otherwise (e.g. ZIP downloads, plain-text logs, SSE streams).

### Error response format

All error responses share a common structure:

```json
{
  "error": "human-readable description",
  "code": "MACHINE_READABLE_CODE"
}
```

Common error codes:

| Code | HTTP status | Meaning |
|---|---|---|
| `NOT_FOUND` | 404 | Resource does not exist |
| `BAD_REQUEST` | 400 | Missing or invalid request parameter |
| `INVALID_STATE` | 409 | Operation not permitted in current pipeline state |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `RENDER_ERROR` | 400 | Graphviz render failed |
| `GENERATION_ERROR` | 500 | LLM DOT generation failed |

### CORS

All endpoints return `Access-Control-Allow-Origin: *` and handle `OPTIONS` preflight with `204 No Content`.

---

## Pipeline JSON shape

The pipeline object returned by most pipeline endpoints has the following shape:

```json
{
  "id": "run-1234567890-1",
  "displayName": "Autumn Falcon",
  "fileName": "my-pipeline.dot",
  "status": "running",
  "archived": false,
  "hasFailureReport": false,
  "simulate": false,
  "autoApprove": true,
  "familyId": "run-1234567890-1",
  "originalPrompt": "Write and test a REST API",
  "startedAt": 1700000000000,
  "finishedAt": null,
  "currentNode": "writeTests",
  "stages": [
    {
      "index": 0,
      "name": "Start",
      "nodeId": "start",
      "status": "completed",
      "startedAt": 1700000000050,
      "durationMs": 12,
      "error": null,
      "hasLog": false
    }
  ],
  "logs": [
    "[2025-01-01T00:00:00Z] Pipeline started: Autumn Falcon [run-1234567890-1]"
  ]
}
```

When the `full` variant is returned (single-pipeline GET), the additional field `dotSource` is included:

```json
{
  "dotSource": "digraph MyPipeline { ... }"
}
```

**Status values:** `idle`, `running`, `paused`, `completed`, `failed`, `cancelled`

**Stage status values:** `running`, `completed`, `failed`, `retrying`, `diagnosing`, `repairing`, `pending`

---

## `pipeline-meta.json` schema (import / export)

When exporting a pipeline the ZIP archive contains a single file `pipeline-meta.json`:

```json
{
  "id": "run-1234567890-1",
  "fileName": "my-pipeline.dot",
  "dotSource": "digraph MyPipeline { ... }",
  "originalPrompt": "original prompt text",
  "familyId": "run-1234567890-1",
  "simulate": false,
  "autoApprove": true
}
```

Required fields for import: `fileName`, `dotSource`. All other fields are optional.

---

## Endpoints

### Pipelines

#### 1. List all pipelines

```
GET /api/v1/pipelines
```

Returns a JSON array of all pipeline objects (without `dotSource`).

**Response 200:**
```json
[
  { "id": "...", "displayName": "...", "status": "completed", ... }
]
```

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines
```

---

#### 2. Create a pipeline

```
POST /api/v1/pipelines
Content-Type: application/json
```

**Request body:**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `dotSource` | string | yes | — | DOT source for the pipeline |
| `fileName` | string | no | `""` | Display filename |
| `simulate` | boolean | no | `false` | Run in simulation mode (no LLM calls) |
| `autoApprove` | boolean | no | `true` | Skip human approval gates |
| `originalPrompt` | string | no | `""` | Natural language prompt that generated the DOT |
| `familyId` | string | no | `""` | Assign to an existing family (blank = new family) |

**Response 201:**
```json
{ "id": "run-1700000000000-1", "status": "running" }
```

**Response 400:**
```json
{ "error": "dotSource is required", "code": "BAD_REQUEST" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { graph[goal=\"test\",label=\"test\"] start[shape=Mdiamond,label=\"Start\"] exit[shape=Msquare,label=\"Done\"] start->exit }","fileName":"test.dot","simulate":true}'
```

---

#### 3. Get a single pipeline

```
GET /api/v1/pipelines/{id}
```

Returns the full pipeline object including `dotSource`. Hydrates from the database if not in memory.

**Response 200:** Full pipeline object (see Pipeline JSON shape above, including `dotSource`)

**Response 404:**
```json
{ "error": "pipeline not found", "code": "NOT_FOUND" }
```

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1
```

---

#### 4. Update a pipeline (PATCH)

```
PATCH /api/v1/pipelines/{id}
Content-Type: application/json
```

Allowed only when the pipeline is not `running` or `paused`.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `dotSource` | string | no | New DOT source |
| `originalPrompt` | string | no | Updated prompt |

**Response 200:** Updated full pipeline object

**Response 404:** `NOT_FOUND`

**Response 409:**
```json
{ "error": "cannot update dotSource while pipeline is running or paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X PATCH http://localhost:8080/api/v1/pipelines/run-1700000000000-1 \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph Updated { ... }"}'
```

---

#### 5. Delete a pipeline

```
DELETE /api/v1/pipelines/{id}
```

Removes the pipeline from memory, database, and deletes its artifacts directory. Not allowed while `running` or `paused`.

**Response 200:**
```json
{ "deleted": true }
```

**Response 409:**
```json
{ "error": "cannot delete running or paused pipeline", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X DELETE http://localhost:8080/api/v1/pipelines/run-1700000000000-1
```

---

### Pipeline lifecycle actions

#### 6. Rerun a pipeline

```
POST /api/v1/pipelines/{id}/rerun
```

Resets and re-executes an existing pipeline from the beginning. Not allowed if already `running`.

**Response 200:**
```json
{ "id": "run-1700000000000-1", "status": "running" }
```

**Response 409:**
```json
{ "error": "pipeline is already running", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/rerun
```

---

#### 7. Pause a pipeline

```
POST /api/v1/pipelines/{id}/pause
```

Signals a running pipeline to pause after completing its current stage. Pipeline must be `running`.

**Response 200:**
```json
{ "paused": true }
```

**Response 409:**
```json
{ "error": "pipeline is not running", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/pause
```

---

#### 8. Resume a pipeline

```
POST /api/v1/pipelines/{id}/resume
```

Resumes a `paused` pipeline from the last checkpoint. Pipeline must be `paused`.

**Response 200:**
```json
{ "id": "run-1700000000000-1", "status": "running" }
```

**Response 409:**
```json
{ "error": "pipeline is not paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/resume
```

---

#### 9. Cancel a pipeline

```
POST /api/v1/pipelines/{id}/cancel
```

Cancels a `running` or `paused` pipeline. The cancel token is set and the pipeline thread is interrupted.

**Response 200:**
```json
{ "cancelled": true }
```

**Response 409:**
```json
{ "error": "pipeline is not running or paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/cancel
```

---

#### 10. Archive a pipeline

```
POST /api/v1/pipelines/{id}/archive
```

Marks a pipeline as archived (hidden from the default dashboard view). Can be archived regardless of status.

**Response 200:**
```json
{ "archived": true }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/archive
```

---

#### 11. Unarchive a pipeline

```
POST /api/v1/pipelines/{id}/unarchive
```

Removes the archived flag, making the pipeline visible again.

**Response 200:**
```json
{ "unarchived": true }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/unarchive
```

---

#### 12. Create a pipeline iteration

```
POST /api/v1/pipelines/{id}/iterations
Content-Type: application/json
```

Creates a new pipeline run in the same family as the given pipeline, using a new DOT source. Used to iterate on a pipeline design while keeping family history.

**Request body:**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `dotSource` | string | yes | — | New DOT source for this iteration |
| `originalPrompt` | string | no | parent's prompt | Updated prompt |
| `fileName` | string | no | parent's fileName | Display filename |

**Response 201:**
```json
{ "id": "run-1700000001000-2", "status": "running", "familyId": "run-1700000000000-1" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/pipelines/run-1700000000000-1/iterations \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph V2 { ... }"}'
```

---

### Family

#### 13. Get pipeline family

```
GET /api/v1/pipelines/{id}/family
```

Returns all pipeline runs in the same family as the given pipeline (up to 100 members), ordered by creation time.

**Response 200:**
```json
{
  "familyId": "run-1700000000000-1",
  "members": [
    {
      "id": "run-1700000000000-1",
      "displayName": "Autumn Falcon",
      "fileName": "test.dot",
      "createdAt": 1700000000000,
      "status": "completed",
      "versionNum": 1,
      "originalPrompt": "original prompt"
    }
  ]
}
```

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/family
```

---

### Stages

#### 14. Get pipeline stages

```
GET /api/v1/pipelines/{id}/stages
```

Returns the list of stage records for the pipeline.

**Response 200:**
```json
[
  {
    "index": 0,
    "name": "Start",
    "nodeId": "start",
    "status": "completed",
    "startedAt": 1700000000050,
    "durationMs": 12,
    "error": null,
    "hasLog": false
  }
]
```

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/stages
```

---

#### 15. Get stage log

```
GET /api/v1/pipelines/{id}/stages/{nodeId}/log
```

Returns the raw `live.log` text for the specified stage node. The `nodeId` corresponds to the node's `id` attribute in the DOT source.

**Response 200:** `Content-Type: text/plain; charset=utf-8`

```
[2025-01-01T00:00:00Z] Stage started: writeTests
[2025-01-01T00:00:01Z] Writing unit tests...
```

**Response 404:** Stage log not found or stage has not produced any log output.

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/stages/writeTests/log
```

---

### Artifacts

#### 16. List artifacts

```
GET /api/v1/pipelines/{id}/artifacts
```

Lists all files in the pipeline's artifacts directory (up to 500 entries).

**Response 200:**
```json
{
  "files": [
    { "path": "writeTests/live.log", "size": 1234, "isText": true },
    { "path": "failure_report.json", "size": 890, "isText": true }
  ],
  "truncated": false
}
```

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/artifacts
```

---

#### 17. Get artifact file

```
GET /api/v1/pipelines/{id}/artifacts/{path...}
```

Returns the raw content of a specific artifact file. Path traversal is blocked; requests that escape the artifacts root return 404. Text files (`log`, `txt`, `md`, `json`, `dot`, `kt`, `py`, `js`, `sh`, `yaml`, `yml`, `toml`, `xml`, `html`, `css`) are served as `text/plain; charset=utf-8`. All others are `application/octet-stream`.

**Response 200:** Raw file bytes

**Response 404:** File not found or path traversal detected

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/artifacts/writeTests/live.log
```

---

#### 18. Download artifacts ZIP

```
GET /api/v1/pipelines/{id}/artifacts.zip
```

Streams all artifact files as a ZIP archive (`Content-Disposition: attachment; filename="artifacts-<name>.zip"`).

**Response 200:** `Content-Type: application/zip`

**Response 404:** No artifacts directory found

**curl:**
```bash
curl -o artifacts.zip http://localhost:8080/api/v1/pipelines/run-1700000000000-1/artifacts.zip
```

---

### Failure report

#### 19. Get failure report

```
GET /api/v1/pipelines/{id}/failure-report
```

Returns the `failure_report.json` file from the pipeline's artifacts directory. Only available when `hasFailureReport` is `true`.

**Response 200:** `Content-Type: application/json` — the raw failure report JSON

**Response 404:** No failure report available

**curl:**
```bash
curl http://localhost:8080/api/v1/pipelines/run-1700000000000-1/failure-report
```

---

### Import / Export

#### 20. Export a pipeline

```
GET /api/v1/pipelines/{id}/export
```

Downloads a ZIP archive containing `pipeline-meta.json` with the pipeline's DOT source and metadata. Use this to transfer a pipeline between Attractor instances.

**Response 200:** `Content-Type: application/zip`, `Content-Disposition: attachment; filename="pipeline-<id>.zip"`

**curl:**
```bash
curl -o pipeline.zip http://localhost:8080/api/v1/pipelines/run-1700000000000-1/export
```

---

#### 21. Import a pipeline

```
POST /api/v1/pipelines/import?onConflict=skip
Content-Type: application/zip
```

Imports a pipeline from a ZIP archive previously exported via the export endpoint. The request body must be the raw ZIP bytes.

**Query parameters:**

| Parameter | Values | Default | Description |
|---|---|---|---|
| `onConflict` | `skip`, `replace` | `skip` | If `skip`, returns `{"status":"skipped"}` when a pipeline with the same familyId already exists. If `replace`, always creates a new run. |

The ZIP must contain a `pipeline-meta.json` with at minimum `fileName` and `dotSource` fields.

**Response 201 (imported):**
```json
{ "status": "started", "id": "run-1700000001000-3" }
```

**Response 200 (skipped):**
```json
{ "status": "skipped", "id": "run-1700000000000-1" }
```

**Response 400:** ZIP is missing, corrupt, or `pipeline-meta.json` is invalid

**curl:**
```bash
curl -X POST "http://localhost:8080/api/v1/pipelines/import?onConflict=skip" \
  -H 'Content-Type: application/zip' \
  --data-binary @pipeline.zip
```

---

### DOT operations

#### 22. Render DOT to SVG

```
POST /api/v1/dot/render
Content-Type: application/json
```

Renders a DOT source string to SVG using the local Graphviz `dot` binary.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `dotSource` | string | yes | Graphviz DOT source |

**Response 200:**
```json
{ "svg": "<svg xmlns=...>...</svg>" }
```

**Response 400 (render error):**
```json
{ "error": "syntax error near line 3", "code": "RENDER_ERROR" }
```

**Response 400 (Graphviz not installed):**
```json
{ "error": "Graphviz not installed. Run: brew install graphviz", "code": "RENDER_ERROR" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/render \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph G { a -> b }"}'
```

---

#### 23. Validate DOT

```
POST /api/v1/dot/validate
Content-Type: application/json
```

Parses and lints a DOT source using the built-in Attractor validator.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `dotSource` | string | yes | Graphviz DOT source |

**Response 200:**
```json
{
  "valid": true,
  "diagnostics": [
    {
      "severity": "warning",
      "message": "Node 'orphan' is not reachable from Start",
      "nodeId": "orphan"
    }
  ]
}
```

`valid` is `false` if any diagnostic has `severity` = `"error"`.

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/validate \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { ... }"}'
```

---

#### 24. Generate DOT (blocking)

```
POST /api/v1/dot/generate
Content-Type: application/json
```

Uses an LLM to generate an Attractor DOT pipeline from a natural language prompt. Blocks until generation is complete.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | yes | Natural language description of the desired pipeline |

**Response 200:**
```json
{ "dotSource": "digraph MyPipeline { ... }" }
```

**Response 500:**
```json
{ "error": "No LLM API key configured.", "code": "GENERATION_ERROR" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Build and test a Python web scraper"}'
```

---

#### 25. Generate DOT (streaming SSE)

```
GET /api/v1/dot/generate/stream?prompt=<encoded>
```

Streams LLM token deltas as Server-Sent Events. The final event includes the complete DOT source.

**Query parameters:**

| Parameter | Required | Description |
|---|---|---|
| `prompt` | yes | URL-encoded natural language prompt |

**Response 200:** `Content-Type: text/event-stream`

Event stream format:
```
data: {"delta":"digraph"}

data: {"delta":" MyPipeline"}

data: {"done":true,"dotSource":"digraph MyPipeline { ... }"}
```

On error:
```
data: {"error":"No LLM API key configured."}
```

**curl:**
```bash
curl -N "http://localhost:8080/api/v1/dot/generate/stream?prompt=Build%20a%20CI%20pipeline"
```

---

#### 26. Fix DOT (blocking)

```
POST /api/v1/dot/fix
Content-Type: application/json
```

Uses an LLM to fix syntax errors in a DOT source given the error message. Blocks until complete.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `dotSource` | string | yes | The broken DOT source |
| `error` | string | no | The error message from Graphviz or the parser |

**Response 200:**
```json
{ "dotSource": "digraph Fixed { ... }" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/fix \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { a -> }","error":"syntax error near }"}'
```

---

#### 27. Fix DOT (streaming SSE)

```
GET /api/v1/dot/fix/stream?dotSource=<encoded>&error=<encoded>
```

Streaming variant of the DOT fix operation.

**Query parameters:**

| Parameter | Required | Description |
|---|---|---|
| `dotSource` | yes | URL-encoded broken DOT source |
| `error` | no | URL-encoded Graphviz error message |

**Response 200:** `Content-Type: text/event-stream` — same format as generate/stream

**curl:**
```bash
curl -N "http://localhost:8080/api/v1/dot/fix/stream?dotSource=digraph%20P%20%7B%20a%20-%3E%20%7D&error=syntax%20error"
```

---

#### 28. Iterate DOT (blocking)

```
POST /api/v1/dot/iterate
Content-Type: application/json
```

Uses an LLM to modify an existing DOT source according to a natural language change request. Blocks until complete.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `baseDot` | string | yes | The existing DOT source to modify |
| `changes` | string | yes | Natural language description of the desired changes |

**Response 200:**
```json
{ "dotSource": "digraph UpdatedPipeline { ... }" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/iterate \
  -H 'Content-Type: application/json' \
  -d '{"baseDot":"digraph P { ... }","changes":"Add a human review step before the exit node"}'
```

---

#### 29. Iterate DOT (streaming SSE)

```
GET /api/v1/dot/iterate/stream?baseDot=<encoded>&changes=<encoded>
```

Streaming variant of the DOT iterate operation.

**Query parameters:**

| Parameter | Required | Description |
|---|---|---|
| `baseDot` | yes | URL-encoded existing DOT source |
| `changes` | yes | URL-encoded natural language change request |

**Response 200:** `Content-Type: text/event-stream` — same format as generate/stream

**curl:**
```bash
curl -N "http://localhost:8080/api/v1/dot/iterate/stream?baseDot=digraph%20P%20%7B%20...%20%7D&changes=Add%20a%20retry%20loop"
```

---

### Settings

#### 30. Get all settings

```
GET /api/v1/settings
```

Returns all known settings and their current values.

**Response 200:**
```json
{ "fireworks_enabled": "true" }
```

**curl:**
```bash
curl http://localhost:8080/api/v1/settings
```

---

#### 31. Get a single setting

```
GET /api/v1/settings/{key}
```

Returns a single setting value. Returns 404 for unknown keys or unset values.

**Known setting keys:**

| Key | Description |
|---|---|
| `fireworks_enabled` | Whether the fireworks animation plays on pipeline completion |

**Response 200:**
```json
{ "key": "fireworks_enabled", "value": "true" }
```

**Response 404:**
```json
{ "error": "unknown setting: foo", "code": "NOT_FOUND" }
```

**curl:**
```bash
curl http://localhost:8080/api/v1/settings/fireworks_enabled
```

---

#### 32. Update a setting

```
PUT /api/v1/settings/{key}
Content-Type: application/json
```

Sets a setting to a new string value.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `value` | string | yes | New value for the setting |

**Response 200:**
```json
{ "key": "fireworks_enabled", "value": "false" }
```

**Response 400:**
```json
{ "error": "unknown setting key: foo", "code": "BAD_REQUEST" }
```

**curl:**
```bash
curl -X PUT http://localhost:8080/api/v1/settings/fireworks_enabled \
  -H 'Content-Type: application/json' \
  -d '{"value":"false"}'
```

---

### Models

#### 33. List available models

```
GET /api/v1/models
```

Returns all models registered in the model catalog.

**Response 200:**
```json
{
  "models": [
    {
      "id": "claude-opus-4-6",
      "provider": "anthropic",
      "displayName": "Claude Opus 4.6",
      "contextWindow": 200000,
      "supportsTools": true,
      "supportsVision": true,
      "supportsReasoning": true
    }
  ]
}
```

**curl:**
```bash
curl http://localhost:8080/api/v1/models
```

---

### Events (Server-Sent Events)

#### 34. Global event stream

```
GET /api/v1/events
```

Establishes a persistent SSE connection that receives real-time pipeline state updates for all pipelines. The first event is always the current full snapshot. Subsequent events are sent whenever any pipeline changes state.

**Response 200:** `Content-Type: text/event-stream`

```
data: {"pipelines":[...]}

data: {"pipelines":[...]}

: heartbeat

```

Heartbeats (`: heartbeat`) are sent every ~2 seconds when no update is available, to keep the connection alive through proxies.

**curl:**
```bash
curl -N http://localhost:8080/api/v1/events
```

---

#### 35. Per-pipeline event stream

```
GET /api/v1/events/{id}
```

Establishes a persistent SSE connection scoped to a single pipeline. Returns 404 if the pipeline does not exist. The first event is a per-pipeline snapshot. Subsequent events are forwarded only when the given pipeline's id is present in the update payload.

**Response 200:** `Content-Type: text/event-stream`

First event:
```
data: {"pipeline":{"id":"run-...","status":"running",...}}

```

Subsequent events follow the same format as the global stream.

**Response 404:** Pipeline not found

**curl:**
```bash
curl -N http://localhost:8080/api/v1/events/run-1700000000000-1
```

---

## Endpoint summary

| # | Method | Path | Description |
|---|---|---|---|
| 1 | GET | `/api/v1/pipelines` | List all pipelines |
| 2 | POST | `/api/v1/pipelines` | Create and run a pipeline |
| 3 | GET | `/api/v1/pipelines/{id}` | Get a single pipeline (full) |
| 4 | PATCH | `/api/v1/pipelines/{id}` | Update dotSource / originalPrompt |
| 5 | DELETE | `/api/v1/pipelines/{id}` | Delete a pipeline and its artifacts |
| 6 | POST | `/api/v1/pipelines/{id}/rerun` | Rerun from start |
| 7 | POST | `/api/v1/pipelines/{id}/pause` | Pause a running pipeline |
| 8 | POST | `/api/v1/pipelines/{id}/resume` | Resume a paused pipeline |
| 9 | POST | `/api/v1/pipelines/{id}/cancel` | Cancel a running or paused pipeline |
| 10 | POST | `/api/v1/pipelines/{id}/archive` | Archive a pipeline |
| 11 | POST | `/api/v1/pipelines/{id}/unarchive` | Unarchive a pipeline |
| 12 | POST | `/api/v1/pipelines/{id}/iterations` | Create a new iteration in the same family |
| 13 | GET | `/api/v1/pipelines/{id}/family` | Get all runs in the same family |
| 14 | GET | `/api/v1/pipelines/{id}/stages` | Get stage list |
| 15 | GET | `/api/v1/pipelines/{id}/stages/{nodeId}/log` | Get stage log text |
| 16 | GET | `/api/v1/pipelines/{id}/artifacts` | List artifact files |
| 17 | GET | `/api/v1/pipelines/{id}/artifacts/{path...}` | Download a specific artifact |
| 18 | GET | `/api/v1/pipelines/{id}/artifacts.zip` | Download all artifacts as ZIP |
| 19 | GET | `/api/v1/pipelines/{id}/failure-report` | Get failure report JSON |
| 20 | GET | `/api/v1/pipelines/{id}/export` | Export pipeline as ZIP |
| 21 | POST | `/api/v1/pipelines/import` | Import pipeline from ZIP |
| 22 | POST | `/api/v1/dot/render` | Render DOT to SVG (blocking) |
| 23 | POST | `/api/v1/dot/validate` | Validate and lint DOT source |
| 24 | POST | `/api/v1/dot/generate` | Generate DOT from prompt (blocking) |
| 25 | GET | `/api/v1/dot/generate/stream` | Generate DOT from prompt (SSE) |
| 26 | POST | `/api/v1/dot/fix` | Fix broken DOT (blocking) |
| 27 | GET | `/api/v1/dot/fix/stream` | Fix broken DOT (SSE) |
| 28 | POST | `/api/v1/dot/iterate` | Iterate on existing DOT (blocking) |
| 29 | GET | `/api/v1/dot/iterate/stream` | Iterate on existing DOT (SSE) |
| 30 | GET | `/api/v1/settings` | Get all settings |
| 31 | GET | `/api/v1/settings/{key}` | Get a single setting |
| 32 | PUT | `/api/v1/settings/{key}` | Update a setting |
| 33 | GET | `/api/v1/models` | List available LLM models |
| 34 | GET | `/api/v1/events` | Global SSE event stream |
| 35 | GET | `/api/v1/events/{id}` | Per-pipeline SSE event stream |
