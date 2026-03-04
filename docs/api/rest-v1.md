# Attractor REST API v1

## Overview

The REST API v1 is mounted at `/api/v1/` and provides programmatic access to all project management, DOT generation, validation, settings, model catalog, and real-time event streaming capabilities.

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
| `INVALID_STATE` | 409 | Operation not permitted in current project state |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `RENDER_ERROR` | 400 | Graphviz render failed |
| `GENERATION_ERROR` | 500 | LLM DOT generation failed |

### CORS

All endpoints return `Access-Control-Allow-Origin: *` and handle `OPTIONS` preflight with `204 No Content`.

---

## Project JSON shape

The project object returned by most project endpoints has the following shape:

```json
{
  "id": "run-1234567890-1",
  "displayName": "Autumn Falcon",
  "fileName": "my-project.dot",
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
    "[2025-01-01T00:00:00Z] Project started: Autumn Falcon [run-1234567890-1]"
  ]
}
```

When the `full` variant is returned (single-project GET), the additional field `dotSource` is included:

```json
{
  "dotSource": "digraph MyProject { ... }"
}
```

**Status values:** `idle`, `running`, `paused`, `completed`, `failed`, `cancelled`

**Stage status values:** `running`, `completed`, `failed`, `retrying`, `diagnosing`, `repairing`, `pending`

---

## Export ZIP format

When exporting a project the ZIP archive has the following structure:

```
project-{name}-{idSuffix}.zip
├── project-meta.json
└── artifacts/
    ├── manifest.json
    ├── checkpoint.json
    ├── failure_report.json          ← only on failed projects
    ├── workspace/                   ← all files the LLM created/modified
    ├── {nodeId}/
    │   ├── prompt.md
    │   ├── response.md
    │   ├── live.log
    │   └── status.json
    └── {nodeId}_repair/             ← only when a repair was attempted
        └── ...
```

The `artifacts/` directory is only present if the project has an on-disk workspace (i.e. it was actually executed, not just metadata-imported).

### `project-meta.json` schema

All `StoredRun` fields are included (except the absolute `logsRoot` path, which is recomputed on import):

```json
{
  "id": "run-1234567890-1",
  "fileName": "my-project.dot",
  "dotSource": "digraph MyProject { ... }",
  "status": "completed",
  "simulate": false,
  "autoApprove": true,
  "createdAt": 1700000000000,
  "projectLog": "...",
  "archived": false,
  "originalPrompt": "original prompt text",
  "finishedAt": 1700000060000,
  "displayName": "Autumn Falcon",
  "familyId": "run-1234567890-1"
}
```

Required fields for import: `fileName`, `dotSource`. All other fields default gracefully if absent (backward-compatible with old export ZIPs that contain only minimal metadata).

### artifacts/ directory contents

| Path | Description |
|---|---|
| `artifacts/manifest.json` | Run summary written at execution start: run ID, graph name, goal, start time |
| `artifacts/checkpoint.json` | Resume state saved after every stage: completed nodes, context variables, retry counts, stage durations |
| `artifacts/failure_report.json` | AI-generated failure diagnosis — only present when the project failed and was not recovered |
| `artifacts/workspace/` | Shared working directory for the entire run — all files the LLM created or modified: source code, build output, test results, reports, etc. |
| `artifacts/{nodeId}/prompt.md` | The exact prompt sent to the LLM for this stage, with all variable substitutions applied |
| `artifacts/{nodeId}/response.md` | The complete LLM response text for this stage |
| `artifacts/{nodeId}/live.log` | Real-time log of every tool call, command execution, and its output for this stage |
| `artifacts/{nodeId}/status.json` | Stage outcome: SUCCESS / FAILED / PARTIAL_SUCCESS, notes, error message if any |
| `artifacts/{nodeId}_repair/` | If Attractor attempted an LLM-guided repair, this holds the repair attempt's own prompt, response, log, and status |

---

## Endpoints

### Projects

#### 1. List all projects

```
GET /api/v1/projects
```

Returns a JSON array of all project objects (without `dotSource`).

**Response 200:**
```json
[
  { "id": "...", "displayName": "...", "status": "completed", ... }
]
```

**curl:**
```bash
curl http://localhost:8080/api/v1/projects
```

---

#### 2. Create a project

```
POST /api/v1/projects
Content-Type: application/json
```

**Request body:**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `dotSource` | string | yes | — | DOT source for the project |
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
curl -X POST http://localhost:8080/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { graph[goal=\"test\",label=\"test\"] start[shape=Mdiamond,label=\"Start\"] exit[shape=Msquare,label=\"Done\"] start->exit }","fileName":"test.dot","simulate":true}'
```

---

#### 3. Get a single project

```
GET /api/v1/projects/{id}
```

Returns the full project object including `dotSource`. Hydrates from the database if not in memory.

**Response 200:** Full project object (see Project JSON shape above, including `dotSource`)

**Response 404:**
```json
{ "error": "project not found", "code": "NOT_FOUND" }
```

**curl:**
```bash
curl http://localhost:8080/api/v1/projects/run-1700000000000-1
```

---

#### 4. Update a project (PATCH)

```
PATCH /api/v1/projects/{id}
Content-Type: application/json
```

Allowed only when the project is not `running` or `paused`.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `dotSource` | string | no | New DOT source |
| `originalPrompt` | string | no | Updated prompt |

**Response 200:** Updated full project object

**Response 404:** `NOT_FOUND`

**Response 409:**
```json
{ "error": "cannot update dotSource while project is running or paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X PATCH http://localhost:8080/api/v1/projects/run-1700000000000-1 \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph Updated { ... }"}'
```

---

#### 5. Delete a project

```
DELETE /api/v1/projects/{id}
```

Removes the project from memory, database, and deletes its artifacts directory. Not allowed while `running` or `paused`.

**Response 200:**
```json
{ "deleted": true }
```

**Response 409:**
```json
{ "error": "cannot delete running or paused project", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X DELETE http://localhost:8080/api/v1/projects/run-1700000000000-1
```

---

### Project lifecycle actions

#### 6. Rerun a project

```
POST /api/v1/projects/{id}/rerun
```

Resets and re-executes an existing project from the beginning. Not allowed if already `running`.

**Response 200:**
```json
{ "id": "run-1700000000000-1", "status": "running" }
```

**Response 409:**
```json
{ "error": "project is already running", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/rerun
```

---

#### 7. Pause a project

```
POST /api/v1/projects/{id}/pause
```

Signals a running project to pause after completing its current stage. Project must be `running`.

**Response 200:**
```json
{ "paused": true }
```

**Response 409:**
```json
{ "error": "project is not running", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/pause
```

---

#### 8. Resume a project

```
POST /api/v1/projects/{id}/resume
```

Resumes a `paused` project from the last checkpoint. Project must be `paused`.

**Response 200:**
```json
{ "id": "run-1700000000000-1", "status": "running" }
```

**Response 409:**
```json
{ "error": "project is not paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/resume
```

---

#### 9. Cancel a project

```
POST /api/v1/projects/{id}/cancel
```

Cancels a `running` or `paused` project. The cancel token is set and the project thread is interrupted.

**Response 200:**
```json
{ "cancelled": true }
```

**Response 409:**
```json
{ "error": "project is not running or paused", "code": "INVALID_STATE" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/cancel
```

---

#### 10. Archive a project

```
POST /api/v1/projects/{id}/archive
```

Marks a project as archived (hidden from the default dashboard view). Can be archived regardless of status.

**Response 200:**
```json
{ "archived": true }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/archive
```

---

#### 11. Unarchive a project

```
POST /api/v1/projects/{id}/unarchive
```

Removes the archived flag, making the project visible again.

**Response 200:**
```json
{ "unarchived": true }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/unarchive
```

---

#### 12. Create a project iteration

```
POST /api/v1/projects/{id}/iterations
Content-Type: application/json
```

Creates a new project run in the same family as the given project, using a new DOT source. Used to iterate on a project design while keeping family history.

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
curl -X POST http://localhost:8080/api/v1/projects/run-1700000000000-1/iterations \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph V2 { ... }"}'
```

---

### Family

#### 13. Get project family

```
GET /api/v1/projects/{id}/family
```

Returns all project runs in the same family as the given project (up to 100 members), ordered by creation time.

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
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/family
```

---

### Stages

#### 14. Get project stages

```
GET /api/v1/projects/{id}/stages
```

Returns the list of stage records for the project.

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
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/stages
```

---

#### 15. Get stage log

```
GET /api/v1/projects/{id}/stages/{nodeId}/log
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
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/stages/writeTests/log
```

---

### Artifacts

#### 16. List artifacts

```
GET /api/v1/projects/{id}/artifacts
```

Lists all files in the project's artifacts directory (up to 500 entries).

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
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/artifacts
```

---

#### 17. Get artifact file

```
GET /api/v1/projects/{id}/artifacts/{path...}
```

Returns the raw content of a specific artifact file. Path traversal is blocked; requests that escape the artifacts root return 404. Text files (`log`, `txt`, `md`, `json`, `dot`, `kt`, `py`, `js`, `sh`, `yaml`, `yml`, `toml`, `xml`, `html`, `css`) are served as `text/plain; charset=utf-8`. All others are `application/octet-stream`.

**Response 200:** Raw file bytes

**Response 404:** File not found or path traversal detected

**curl:**
```bash
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/artifacts/writeTests/live.log
```

---

### Failure report

#### 18. Get failure report

```
GET /api/v1/projects/{id}/failure-report
```

Returns the `failure_report.json` file from the project's artifacts directory. Only available when `hasFailureReport` is `true`.

**Response 200:** `Content-Type: application/json` — the raw failure report JSON

**Response 404:** No failure report available

**curl:**
```bash
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/failure-report
```

---

### Import / Export / DOT file

#### 19. Export a project

```
GET /api/v1/projects/{id}/export
```

Downloads a ZIP archive containing `project-meta.json` (all project fields) plus the full `artifacts/` directory (workspace, stage logs, prompts, responses). Use this to back up a project or transfer it between Attractor instances. See [Export ZIP format](#export-zip-format) above for the full structure.

**Response 200:** `Content-Type: application/zip`, `Content-Disposition: attachment; filename="project-<name>-<idSuffix>.zip"`

**curl:**
```bash
curl -o project.zip http://localhost:8080/api/v1/projects/run-1700000000000-1/export
```

---

#### 20. Import a project

```
POST /api/v1/projects/import?onConflict=skip
Content-Type: application/zip
```

Imports a project from a ZIP archive previously exported via the export endpoint. The request body must be the raw ZIP bytes. The project is restored with its original status and artifacts — no re-run is triggered.

**Query parameters:**

| Parameter | Values | Default | Description |
|---|---|---|---|
| `onConflict` | `skip`, `overwrite` | `skip` | If `skip`, returns `{"status":"skipped"}` when a project with the same `familyId` already exists. If `overwrite`, always imports and replaces any existing entry. |

The ZIP must contain a `project-meta.json` with at minimum `fileName` and `dotSource` fields. All other fields default gracefully — old export ZIPs without `artifacts/` are still importable.

**Response 201 (imported):**
```json
{ "status": "imported", "id": "run-1700000001000-3" }
```

**Response 200 (skipped):**
```json
{ "status": "skipped", "id": "run-1700000000000-1" }
```

**Response 400:** ZIP is missing, corrupt, or `project-meta.json` is invalid

**curl:**
```bash
curl -X POST "http://localhost:8080/api/v1/projects/import?onConflict=skip" \
  -H 'Content-Type: application/zip' \
  --data-binary @project.zip
```

---

#### 21. Download a project's DOT file

```
GET /api/v1/projects/{id}/dot
```

Downloads the project's DOT source as a plain-text `.dot` file.

**Response 200:** `Content-Type: text/plain; charset=utf-8`, `Content-Disposition: attachment; filename="<fileName>"`

**Response 404:** project not found, or project has no DOT source

**curl:**
```bash
curl -o project.dot http://localhost:8080/api/v1/projects/run-1700000000000-1/dot
```

---

#### 22. Upload a DOT file to create a project

```
POST /api/v1/projects/dot
Content-Type: text/plain
```

Accepts a raw DOT source string as the request body and immediately submits it as a new project run. Options are passed as query parameters.

**Query parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `fileName` | string | `""` | Display name / filename for the project |
| `simulate` | boolean | `false` | If `true`, runs without LLM calls |
| `autoApprove` | boolean | `true` | If `false`, gates require manual approval |
| `originalPrompt` | string | `""` | Natural-language prompt that produced the DOT |

**Response 201:**
```json
{ "id": "run-1700000001000-3", "status": "running" }
```

**Response 400:** request body is empty

**curl:**
```bash
curl -X POST "http://localhost:8080/api/v1/projects/dot?fileName=my-project.dot" \
  -H 'Content-Type: text/plain' \
  --data-binary @my-project.dot
```

---

### DOT operations

#### 23. Render DOT to SVG

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

#### 24. Validate DOT

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

#### 25. Generate DOT (blocking)

```
POST /api/v1/dot/generate
Content-Type: application/json
```

Uses an LLM to generate an Attractor DOT project from a natural language prompt. Blocks until generation is complete.

**Request body:**

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | yes | Natural language description of the desired project |

**Response 200:**
```json
{ "dotSource": "digraph MyProject { ... }" }
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

#### 26. Generate DOT (streaming SSE)

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

data: {"delta":" MyProject"}

data: {"done":true,"dotSource":"digraph MyProject { ... }"}
```

On error:
```
data: {"error":"No LLM API key configured."}
```

**curl:**
```bash
curl -N "http://localhost:8080/api/v1/dot/generate/stream?prompt=Build%20a%20CI%20project"
```

---

#### 27. Fix DOT (blocking)

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

#### 28. Fix DOT (streaming SSE)

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

#### 29. Iterate DOT (blocking)

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
{ "dotSource": "digraph UpdatedProject { ... }" }
```

**curl:**
```bash
curl -X POST http://localhost:8080/api/v1/dot/iterate \
  -H 'Content-Type: application/json' \
  -d '{"baseDot":"digraph P { ... }","changes":"Add a human review step before the exit node"}'
```

---

#### 30. Iterate DOT (streaming SSE)

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

#### 31. Get all settings

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

#### 32. Get a single setting

```
GET /api/v1/settings/{key}
```

Returns a single setting value. Returns 404 for unknown keys or unset values.

**Known setting keys:**

| Key | Description |
|---|---|
| `fireworks_enabled` | Whether the fireworks animation plays on project completion |

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

#### 33. Update a setting

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

#### 34. List available models

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

#### 35. Global event stream

```
GET /api/v1/events
```

Establishes a persistent SSE connection that receives real-time project state updates for all projects. The first event is always the current full snapshot. Subsequent events are sent whenever any project changes state.

**Response 200:** `Content-Type: text/event-stream`

```
data: {"projects":[...]}

data: {"projects":[...]}

: heartbeat

```

Heartbeats (`: heartbeat`) are sent every ~2 seconds when no update is available, to keep the connection alive through proxies.

**curl:**
```bash
curl -N http://localhost:8080/api/v1/events
```

---

#### 36. Per-project event stream

```
GET /api/v1/events/{id}
```

Establishes a persistent SSE connection scoped to a single project. Returns 404 if the project does not exist. The first event is a per-project snapshot. Subsequent events are forwarded only when the given project's id is present in the update payload.

**Response 200:** `Content-Type: text/event-stream`

First event:
```
data: {"project":{"id":"run-...","status":"running",...}}

```

Subsequent events follow the same format as the global stream.

**Response 404:** Project not found

**curl:**
```bash
curl -N http://localhost:8080/api/v1/events/run-1700000000000-1
```

---

### Git history

#### 37. Get project git summary

```
GET /api/v1/projects/{id}/git
```

Returns a summary of the git repository in the project's workspace directory. The workspace git repository is initialized automatically when a project first runs and receives a commit after each terminal-state event (completed, failed, cancelled, paused).

**Response 200:** `Content-Type: application/json`

```json
{
  "available": true,
  "repoExists": true,
  "branch": "main",
  "commitCount": 4,
  "lastCommit": {
    "hash": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
    "shortHash": "a1b2c3d",
    "subject": "Run run-1700000000000-1 completed: 3 stages",
    "date": "2026-03-04 14:22:10 -0800"
  },
  "dirty": false,
  "trackedFiles": 12,
  "recent": [
    {
      "hash": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
      "shortHash": "a1b2c3d",
      "subject": "Run run-1700000000000-1 completed: 3 stages",
      "date": "2026-03-04 14:22:10 -0800"
    }
  ]
}
```

**Fields:**

| Field | Type | Description |
|---|---|---|
| `available` | boolean | Whether the `git` binary is available on the server's PATH |
| `repoExists` | boolean | Whether a `.git` directory exists in the project's workspace |
| `branch` | string | Current branch name (empty if no commits or detached HEAD) |
| `commitCount` | integer | Total number of commits in the repository |
| `lastCommit` | object\|null | Most recent commit, or `null` if no commits exist |
| `lastCommit.hash` | string | Full 40-character SHA-1 hash |
| `lastCommit.shortHash` | string | Abbreviated 7-character hash |
| `lastCommit.subject` | string | First line of the commit message |
| `lastCommit.date` | string | ISO-8601 commit date with timezone offset |
| `dirty` | boolean | Whether the workspace has uncommitted changes |
| `trackedFiles` | integer | Number of files tracked by git |
| `recent` | array | Up to 5 most recent commits in reverse-chronological order |

**Degraded states:**

| Condition | `available` | `repoExists` | `commitCount` | `lastCommit` |
|---|---|---|---|---|
| `git` not on PATH | `false` | `false` | `0` | `null` |
| No workspace repo initialized | `true` | `false` | `0` | `null` |
| Repo initialized, no commits yet | `true` | `true` | `0` | `null` |

All known project IDs return HTTP 200 regardless of git state. The payload fields reflect the current state.

**Response 404:** Project not found

**curl:**
```bash
curl http://localhost:8080/api/v1/projects/run-1700000000000-1/git
```

---

## Endpoint summary

| # | Method | Path | Description |
|---|---|---|---|
| 1 | GET | `/api/v1/projects` | List all projects |
| 2 | POST | `/api/v1/projects` | Create and run a project |
| 3 | GET | `/api/v1/projects/{id}` | Get a single project (full) |
| 4 | PATCH | `/api/v1/projects/{id}` | Update dotSource / originalPrompt |
| 5 | DELETE | `/api/v1/projects/{id}` | Delete a project and its artifacts |
| 6 | POST | `/api/v1/projects/{id}/rerun` | Rerun from start |
| 7 | POST | `/api/v1/projects/{id}/pause` | Pause a running project |
| 8 | POST | `/api/v1/projects/{id}/resume` | Resume a paused project |
| 9 | POST | `/api/v1/projects/{id}/cancel` | Cancel a running or paused project |
| 10 | POST | `/api/v1/projects/{id}/archive` | Archive a project |
| 11 | POST | `/api/v1/projects/{id}/unarchive` | Unarchive a project |
| 12 | POST | `/api/v1/projects/{id}/iterations` | Create a new iteration in the same family |
| 13 | GET | `/api/v1/projects/{id}/family` | Get all runs in the same family |
| 14 | GET | `/api/v1/projects/{id}/stages` | Get stage list |
| 15 | GET | `/api/v1/projects/{id}/stages/{nodeId}/log` | Get stage log text |
| 16 | GET | `/api/v1/projects/{id}/artifacts` | List artifact files |
| 17 | GET | `/api/v1/projects/{id}/artifacts/{path...}` | Download a specific artifact |
| 18 | GET | `/api/v1/projects/{id}/failure-report` | Get failure report JSON |
| 19 | GET | `/api/v1/projects/{id}/export` | Export project as ZIP (metadata + artifacts) |
| 20 | POST | `/api/v1/projects/import` | Import (restore) project from ZIP |
| 21 | GET | `/api/v1/projects/{id}/dot` | Download project DOT source as a file |
| 22 | POST | `/api/v1/projects/dot` | Upload raw DOT to create and run a project |
| 23 | POST | `/api/v1/dot/render` | Render DOT to SVG (blocking) |
| 24 | POST | `/api/v1/dot/validate` | Validate and lint DOT source |
| 25 | POST | `/api/v1/dot/generate` | Generate DOT from prompt (blocking) |
| 26 | GET | `/api/v1/dot/generate/stream` | Generate DOT from prompt (SSE) |
| 27 | POST | `/api/v1/dot/fix` | Fix broken DOT (blocking) |
| 28 | GET | `/api/v1/dot/fix/stream` | Fix broken DOT (SSE) |
| 29 | POST | `/api/v1/dot/iterate` | Iterate on existing DOT (blocking) |
| 30 | GET | `/api/v1/dot/iterate/stream` | Iterate on existing DOT (SSE) |
| 31 | GET | `/api/v1/settings` | Get all settings |
| 32 | GET | `/api/v1/settings/{key}` | Get a single setting |
| 33 | PUT | `/api/v1/settings/{key}` | Update a setting |
| 34 | GET | `/api/v1/models` | List available LLM models |
| 35 | GET | `/api/v1/events` | Global SSE event stream |
| 36 | GET | `/api/v1/events/{id}` | Per-project SSE event stream |
| 37 | GET | `/api/v1/projects/{id}/git` | Get project workspace git summary |
