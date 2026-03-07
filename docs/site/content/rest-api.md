---
title: "REST API"
weight: 20
---

## Overview

The REST API v1 is mounted at `/api/v1/` and provides programmatic access to all project management, DOT generation, validation, settings, model catalog, and real-time event streaming capabilities.

**Base URL:** `http://localhost:7070/api/v1`

All request and response bodies are JSON (`Content-Type: application/json`) unless noted otherwise (ZIP downloads, plain-text logs, SSE streams). CORS headers (`Access-Control-Allow-Origin: *`) are present on all endpoints.

### Error response format

```json
{"error": "human-readable description", "code": "MACHINE_READABLE_CODE"}
```

| Code | HTTP Status | Meaning |
|------|-------------|---------|
| `NOT_FOUND` | 404 | Resource does not exist |
| `BAD_REQUEST` | 400 | Missing or invalid parameter |
| `INVALID_STATE` | 409 | Operation not permitted in current state |
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `RENDER_ERROR` | 400 | Graphviz render failed |
| `GENERATION_ERROR` | 500 | LLM generation failed |

### Project JSON shape

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Unique project identifier |
| `displayName` | string | Human-readable name (auto-generated) |
| `fileName` | string | Source DOT filename |
| `status` | string | idle \| running \| paused \| completed \| failed \| cancelled |
| `archived` | boolean | Whether moved to archive view |
| `hasFailureReport` | boolean | Whether a failure_report.json exists |
| `simulate` | boolean | Simulation mode (no real LLM calls) |
| `autoApprove` | boolean | Skip human review gates automatically |
| `familyId` | string | Groups project versions (iterations) |
| `originalPrompt` | string | Natural language prompt that generated the DOT |
| `startedAt` | long | Unix epoch milliseconds |
| `finishedAt` | long\|null | Unix epoch milliseconds, null if still running |
| `currentNode` | string\|null | Node ID of currently executing stage |
| `stages` | array | Stage execution records |
| `logs` | array | Recent log lines (up to 200) |
| `dotSource` | string | Only in single-project GET responses |

## Endpoints

### Project CRUD

**GET** `/api/v1/projects`

Returns a JSON array of all projects (without `dotSource`).

```bash
curl http://localhost:7070/api/v1/projects
```

---

**POST** `/api/v1/projects`

Create and immediately run a new project. Returns 201 with the new project ID.

Body: `{"dotSource":"...","fileName":"","simulate":false,"autoApprove":true,"originalPrompt":"","agentId":"","modelId":""}` (`dotSource` required; `agentId` and `modelId` are optional overrides for LLM provider and model)

```bash
curl -X POST http://localhost:7070/api/v1/projects \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { graph[goal=\"test\"] start[shape=Mdiamond] exit[shape=Msquare] start->exit }","simulate":true}'
```

---

**GET** `/api/v1/projects/{id}`

Get a single project including `dotSource`. Hydrates from database if not in memory.

```bash
curl http://localhost:7070/api/v1/projects/run-1700000000000-1
```

---

**PATCH** `/api/v1/projects/{id}`

Update `dotSource` or `originalPrompt`. Not allowed while running or paused (returns 409).

```bash
curl -X PATCH http://localhost:7070/api/v1/projects/run-1700000000000-1 \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph Updated { ... }"}'
```

---

**DELETE** `/api/v1/projects/{id}`

Delete project and artifacts. Not allowed while running or paused (returns 409).

```bash
curl -X DELETE http://localhost:7070/api/v1/projects/run-1700000000000-1
```

### Project Lifecycle

**POST** `/api/v1/projects/{id}/rerun`

Reset and re-execute from the beginning. Not allowed if already running (409).

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/rerun
```

---

**POST** `/api/v1/projects/{id}/pause`

Signal a running project to pause after its current stage. Returns `{"paused":true}`. Project must be running (409 otherwise).

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/pause
```

---

**POST** `/api/v1/projects/{id}/resume`

Resume a paused project. Creates a new project ID. Returns `{"id":"...","status":"running"}`.

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/resume
```

---

**POST** `/api/v1/projects/{id}/cancel`

Cancel a running or paused project. Returns `{"cancelled":true}`.

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/cancel
```

---

**POST** `/api/v1/projects/{id}/archive`

Move project to the archived view. Returns `{"archived":true}`.

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/archive
```

---

**POST** `/api/v1/projects/{id}/unarchive`

Restore a project from the archived view. Returns `{"unarchived":true}`.

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/unarchive
```

### Project Versioning

**POST** `/api/v1/projects/{id}/iterations`

Create a new project version in the same family. Body: `{"dotSource":"...","originalPrompt":""}`. Returns 201.

```bash
curl -X POST http://localhost:7070/api/v1/projects/{id}/iterations \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph Updated { ... }","originalPrompt":"Add a test stage"}'
```

---

**GET** `/api/v1/projects/{id}/family`

List all versions in the project's family. Returns `{"familyId":"...","members":[...]}` with `versionNum` per member.

```bash
curl http://localhost:7070/api/v1/projects/{id}/family
```

---

**GET** `/api/v1/projects/{id}/stages`

List the stage execution records for a project.

```bash
curl http://localhost:7070/api/v1/projects/{id}/stages
```

### Artifacts & Logs

**GET** `/api/v1/projects/{id}/artifacts`

List artifact files. Returns `{"files":[{"path":"...","size":N,"isText":true}],"truncated":false}`. Max 500 files.

```bash
curl http://localhost:7070/api/v1/projects/{id}/artifacts
```

---

**GET** `/api/v1/projects/{id}/artifacts/{path}`

Get the content of a specific artifact file. Path traversal is blocked.

```bash
curl http://localhost:7070/api/v1/projects/{id}/artifacts/writeTests/live.log
```

---

**GET** `/api/v1/projects/{id}/artifacts.zip`

Download all artifacts as a ZIP archive (`application/zip`).

```bash
curl -o artifacts.zip http://localhost:7070/api/v1/projects/{id}/artifacts.zip
```

---

**GET** `/api/v1/projects/{id}/stages/{nodeId}/log`

Get the live log for a specific stage as plain text.

```bash
curl http://localhost:7070/api/v1/projects/{id}/stages/writeTests/log
```

---

**GET** `/api/v1/projects/{id}/failure-report`

Get the AI-generated failure diagnosis as JSON. Returns 404 if no failure report exists.

```bash
curl http://localhost:7070/api/v1/projects/{id}/failure-report
```

### Import / Export / DOT file

**GET** `/api/v1/projects/{id}/export`

Export project as a ZIP containing `project-meta.json`.

```bash
curl -o project.zip http://localhost:7070/api/v1/projects/{id}/export
```

---

**POST** `/api/v1/projects/import`

Import from a previously exported ZIP. Query param: `?onConflict=skip` (default) or `?onConflict=overwrite`. Returns 201.

```bash
curl -X POST "http://localhost:7070/api/v1/projects/import?onConflict=skip" \
  -H 'Content-Type: application/zip' \
  --data-binary @project.zip
```

---

**GET** `/api/v1/projects/{id}/dot`

Download the project's DOT source as a plain-text `.dot` file. Returns 404 if the project has no DOT source.

```bash
curl -o project.dot http://localhost:7070/api/v1/projects/{id}/dot
```

---

**POST** `/api/v1/projects/dot`

Upload raw DOT source as the request body to create and immediately run a new project. Options via query params: `fileName`, `simulate` (default `false`), `autoApprove` (default `true`), `originalPrompt`. Returns 201.

```bash
curl -X POST "http://localhost:7070/api/v1/projects/dot?fileName=my.dot" \
  -H 'Content-Type: text/plain' \
  --data-binary @my.dot
```

### DOT Operations

**POST** `/api/v1/dot/render`

Render a DOT graph to SVG via Graphviz. Returns `{"svg":"..."}` or 400 if Graphviz is not installed.

```bash
curl -X POST http://localhost:7070/api/v1/dot/render \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph G { a -> b }"}'
```

---

**POST** `/api/v1/dot/validate`

Parse and lint a DOT project. Returns `{"valid":true,"diagnostics":[]}`.

```bash
curl -X POST http://localhost:7070/api/v1/dot/validate \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"digraph P { ... }"}'
```

---

**POST** `/api/v1/dot/generate`

Generate a DOT project from a natural language prompt (synchronous). Returns `{"dotSource":"..."}`.

```bash
curl -X POST http://localhost:7070/api/v1/dot/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Build and test a Go REST API"}'
```

---

**GET** `/api/v1/dot/generate/stream`

Generate DOT from a prompt with SSE streaming. Query param: `?prompt=...`. Streams `data: {"delta":"..."}` events.

```bash
curl "http://localhost:7070/api/v1/dot/generate/stream?prompt=Build+a+Go+app"
```

---

**POST** `/api/v1/dot/fix`

Fix a broken DOT graph using the LLM (synchronous). Body: `{"dotSource":"...","error":"..."}`.

```bash
curl -X POST http://localhost:7070/api/v1/dot/fix \
  -H 'Content-Type: application/json' \
  -d '{"dotSource":"...","error":"syntax error"}'
```

---

**GET** `/api/v1/dot/fix/stream`

Fix a broken DOT with SSE streaming. Query params: `?dotSource=...&error=...`.

```bash
curl "http://localhost:7070/api/v1/dot/fix/stream?dotSource=...&error=syntax+error"
```

---

**POST** `/api/v1/dot/iterate`

Iterate on an existing DOT graph given a change description (synchronous). Body: `{"baseDot":"...","changes":"..."}`.

```bash
curl -X POST http://localhost:7070/api/v1/dot/iterate \
  -H 'Content-Type: application/json' \
  -d '{"baseDot":"digraph P {...}","changes":"Add a deployment stage after tests"}'
```

---

**GET** `/api/v1/dot/iterate/stream`

Iterate on DOT with SSE streaming. Query params: `?baseDot=...&changes=...`.

```bash
curl "http://localhost:7070/api/v1/dot/iterate/stream?baseDot=...&changes=Add+a+deployment+stage"
```

### Settings

**GET** `/api/v1/settings`

Get all settings as a JSON object.

```bash
curl http://localhost:7070/api/v1/settings
```

---

**GET** `/api/v1/settings/{key}`

Get a single setting. Returns 404 if the key is unknown or not set.

```bash
curl http://localhost:7070/api/v1/settings/execution_mode
```

---

**PUT** `/api/v1/settings/{key}`

Update a setting. Body: `{"value":"..."}`. Returns 400 for unknown keys.

```bash
curl -X PUT http://localhost:7070/api/v1/settings/execution_mode \
  -H 'Content-Type: application/json' \
  -d '{"value":"cli"}'
```

### Models

**GET** `/api/v1/models`

List all available LLM models from the model catalog.

```bash
curl http://localhost:7070/api/v1/models
```

---

**GET** `/api/v1/agents`

List all agents and their available models, filtered by which providers are currently enabled and the active execution mode. Returns:

```json
{
  "executionMode": "api",
  "agents": [
    {
      "id": "anthropic",
      "displayName": "Anthropic",
      "directApiModels": [{"id": "claude-sonnet-4-6", "displayName": "Claude Sonnet 4.6"}, ...],
      "cliModels": [...]
    }
  ]
}
```

`directApiModels` is `null` for CLI-only providers (Copilot). `cliModels` is `null` for API-only providers (Custom). Model lists come from the DB cache (populated by the Refresh action); if not yet cached, the built-in catalog is used as fallback.

```bash
curl http://localhost:7070/api/v1/agents
```

---

**POST** `/api/v1/settings/fetch-models`

Fetch the live model list from a provider's API and cache it in the database. Only supported in Direct API mode for `anthropic`, `openai`, and `gemini`.

Body: `{"provider": "anthropic"}`

Response: `{"ok": true, "models": [...]}` on success, or `{"ok": false, "error": "..."}` on failure (always 200).

```bash
curl -X POST http://localhost:7070/api/v1/settings/fetch-models \
  -H 'Content-Type: application/json' \
  -d '{"provider":"anthropic"}'
```

### Events / SSE

**GET** `/api/v1/events`

Subscribe to a Server-Sent Events stream of all project state updates. Streams `data: {"projects":[...]}` on every change, with a heartbeat every 2 seconds.

```bash
curl -N http://localhost:7070/api/v1/events
```

---

**GET** `/api/v1/events/{id}`

Subscribe to events for a single project. Returns 404 if the project is not found. Auto-delivers the current state on connect.

```bash
curl -N http://localhost:7070/api/v1/events/run-1700000000000-1
```

### Git History

**GET** `/api/v1/projects/{id}/git`

Get the git history summary for a project's workspace. Returns `{"available":bool,"repoExists":bool,"branch":"...","commitCount":N,"lastCommit":{...}|null,"dirty":bool,"trackedFiles":N,"recent":[...]}`.

Returns 404 for unknown project IDs. Returns 200 with degraded payload when git is unavailable or the repo has no commits.

```bash
curl http://localhost:7070/api/v1/projects/{id}/git
```
