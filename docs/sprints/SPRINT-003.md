# Sprint 003: Pipeline Run Export / Import

## Overview

The current "Download ZIP" button packages only the execution artifacts from a run's `logsRoot` directory — stage outputs, logs, and checkpoint. It cannot restore a run entry to a new database, and there is no import path at all. Sharing a pipeline run between machines, or recovering after a database wipe, requires manual SQL work.

This sprint adds a complete round-trip: **Export** packages a run into a self-contained zip that includes both a `pipeline-meta.json` (all DB fields: id, fileName, dotSource, originalPrompt, status, simulate, autoApprove, createdAt, pipelineLog, logsRoot, archived) and the full `logsRoot` directory tree. **Import** accepts that zip, streams it via `ZipInputStream`, extracts files to `logs/`, registers the run in the DB and in-memory registry, and broadcasts an update — so the run appears in the Monitor exactly as if it had run locally.

The design reuses existing architecture: `RunStore` remains the system of record, `PipelineRegistry.loadFromDB()` reconstruction logic is extracted into a shared helper that both startup hydration and import hydration use, and `WebMonitorServer` hosts the new HTTP endpoints alongside the existing dashboard.

## Use Cases

1. **Share a pipeline run**: User exports a completed run from machine A. Colleague imports on machine B. Run appears in the Monitor with correct status, logs, stage list, and DOT source.
2. **Backup and restore**: User wipes the database and imports a previously exported zip to restore the run history.
3. **Archive and reload**: User exports a run before archiving, imports it later when needed for reference — without having kept the original DB.
4. **Iterate on a shared run**: After importing, the user can click Iterate (Sprint 002) to fork the pipeline from the imported DOT source and original prompt.

## Architecture

```text
EXPORT
GET /api/export-run?id={pipelineId}
    ├── RunStore.getById(id) → canonical StoredRun (all DB fields)
    ├── validates terminal status: completed|failed|cancelled|paused
    ├── opens ZipOutputStream on response body
    ├── writes pipeline-meta.json as first entry
    └── if logsRoot dir exists: walks all files → writes as "logs/<relpath>"
    → returns application/zip, filename "run-<safeName>-<idSuffix>.zip"

IMPORT
POST /api/import-run?onConflict=overwrite|skip  (body: application/zip)
    ├── streams ZipInputStream directly from request body
    ├── extracts all entries to a temp dir
    ├── parses pipeline-meta.json
    ├── validates required fields + status is terminal
    ├── validates zip entry paths (no absolute paths, no ".." traversal)
    ├── derives logsRoot = "logs/<fileNameWithoutExt>-<runId>"
    ├── copies "logs/*" entries from temp dir to logsRoot
    ├── builds StoredRun from metadata
    ├── if onConflict=skip and ID exists → return {"status":"skipped","id":"..."}
    ├── RunStore.insertOrReplaceImported(storedRun) → INSERT OR REPLACE
    ├── registry.upsertImported(storedRun) → in-memory reconstruction
    └── broadcastUpdate() → run appears in Monitor immediately
```

### Zip Structure

```text
run-<name>-<id>.zip
├── pipeline-meta.json          ← all DB fields
└── logs/                       ← logsRoot contents
    ├── checkpoint.json
    ├── manifest.json
    └── <nodeId>/
        ├── live.log
        └── ... stage outputs ...
```

### `pipeline-meta.json` Format

```json
{
  "id": "run-...",
  "fileName": "pipeline.dot",
  "dotSource": "digraph ...",
  "originalPrompt": "...",
  "status": "completed",
  "simulate": false,
  "autoApprove": true,
  "createdAt": 1234567890,
  "pipelineLog": "...",
  "archived": false,
  "logsRootBasename": "HelloWorldGo-run-..."
}
```

`logsRootBasename` = `File(logsRoot).name`. On import, `logsRoot` is re-derived as `logs/<fileNameWithoutExt>-<runId>` (deterministic, ID-anchored, avoids collisions).

### Shared Reconstruction Helper

`PipelineRegistry` gains a private `hydrateEntry(run: StoredRun): PipelineEntry` that encapsulates:
- crash-recovery: `running` → display as `failed`
- log-line restoration from `pipelineLog`
- stage reconstruction from `Checkpoint.load(logsRoot)`

Both `loadFromDB()` and `upsertImported()` call this helper, eliminating drift between startup and import hydration paths.

## Implementation Plan

### Phase 1: RunStore Primitives (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `fun getById(id: String): StoredRun?`: `SELECT * FROM pipeline_runs WHERE id=?`, returns null if not found
- [ ] Add `fun insertOrReplaceImported(run: StoredRun)`: `INSERT OR REPLACE INTO pipeline_runs (...all columns...) VALUES (...)` preserving original `id`, `created_at`, `status`, `logs_root`, `pipeline_log`, `archived`, `original_prompt`

### Phase 2: PipelineRegistry — Shared Helper + `upsertImported()` (~15%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Extract private `fun hydrateEntry(run: StoredRun): PipelineEntry`:
  - Creates fresh `PipelineState`
  - Applies crash-recovery rule: `running` → forces status to `failed` (same as current `loadFromDB()`)
  - Restores log lines from `run.pipelineLog`
  - Loads `Checkpoint.load(run.logsRoot)` for stage reconstruction (same as current `loadFromDB()`)
  - Returns fully hydrated `PipelineEntry`
- [ ] Refactor `loadFromDB()` to call `hydrateEntry()` for each run (no behavior change)
- [ ] Add `fun upsertImported(run: StoredRun)`:
  - Calls `hydrateEntry(run)` to build entry
  - If `run.id` already in `entries`: removes old `orderedIds` slot
  - Inserts entry into `entries` map and `orderedIds` list
  - Does NOT call `store.insert()` (caller handles DB)

### Phase 3: Backend — `/api/export-run` (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `GET /api/export-run?id={pipelineId}`:
  - Returns 404 if pipeline not found in registry
  - Returns 409 if status is not in `{completed, failed, cancelled, paused}`
  - Calls `store.getById(id)` to get canonical `StoredRun` (has `createdAt`, `pipelineLog`, `archived`)
  - Builds `pipeline-meta.json` JSON from `StoredRun` fields + `logsRootBasename = File(run.logsRoot).name`
  - Opens `ZipOutputStream` on `ex.responseBody`
  - Writes `pipeline-meta.json` as first zip entry
  - If `logsRoot` dir exists: walks all files, writes each as `"logs/<relpath>"`
  - Sets `Content-Disposition: attachment; filename="run-<safeName>-<idSuffix>.zip"`
  - Sets `Content-Type: application/zip`

### Phase 4: Backend — `/api/import-run` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `POST /api/import-run?onConflict=overwrite|skip` (accepts raw zip body, `Content-Type: application/zip` or `application/octet-stream`):
  - Reads `onConflict` query param (default `"overwrite"`)
  - Streams `ZipInputStream` directly from `ex.requestBody` (no full-body buffering)
  - Extracts all entries to a temp directory (`Files.createTempDirectory()`)
  - **Zip safety**: for each entry, reject absolute paths and any entry containing `..`; strip leading `/`
  - Finds and parses `pipeline-meta.json` → builds `Map<String, Any?>`
  - Returns 400 if `pipeline-meta.json` not found in zip
  - Validates required fields: `id`, `dotSource`, `status`, `fileName` — returns 400 with clear message if missing
  - Validates `status` is terminal (`completed|failed|cancelled|paused`) — returns 400 if not
  - If `onConflict == "skip"` and `store.getById(id) != null`: return `{"status":"skipped","id":"..."}`
  - Derives `logsRoot = "logs/${meta["fileName"]!!.toString().substringBeforeLast(".")}-${meta["id"]}"`
  - Creates `logsRoot` directory
  - Copies `logs/*` entries from temp dir to `logsRoot/`
  - Constructs `StoredRun` from parsed metadata (use `logsRoot` just derived, not from meta)
  - Calls `store.insertOrReplaceImported(storedRun)`
  - Calls `registry.upsertImported(storedRun)`
  - Calls `broadcastUpdate()`
  - Returns `{"status":"imported","id":"..."}`
  - Returns 400 for invalid/corrupt zip

### Phase 5: Frontend — Export Button + Import UI (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (HTML/CSS/JS)

**Tasks:**

**Export button:**
- [ ] In `buildPanel()`: add Export button after Download ZIP:
  `'<button class="btn-download" id="exportBtn" style="display:none;" onclick="exportRun()">&#8599;&ensp;Export</button>'`
- [ ] In `updatePanel()`: show `exportBtn` for same conditions as `downloadBtn` (terminal/paused) AND only if `p.dotSource` is non-empty
- [ ] Add JS `exportRun()`:
  ```javascript
  function exportRun() {
    var a = document.createElement('a');
    a.href = '/api/export-run?id=' + encodeURIComponent(selectedId);
    a.download = '';
    a.click();
  }
  ```

**Import UI:**
- [ ] In the Upload modal HTML: add an "Import from ZIP" section below the file input area:
  ```html
  <div class="field" style="border-top:1px solid #30363d;padding-top:12px;margin-top:8px;">
    <label>Or import an exported run (.zip)</label>
    <input type="file" class="field-file" id="importZipInput" accept=".zip">
  </div>
  <button class="btn-primary" id="importSubmitBtn" onclick="submitImport()" style="display:none;">Import Run</button>
  ```
- [ ] Show `importSubmitBtn` when `importZipInput` has a file selected; hide upload submit button when import file is selected (mutually exclusive inputs)
- [ ] Add JS `submitImport()`:
  - Reads the zip file from `importZipInput` as `ArrayBuffer`
  - POSTs to `/api/import-run?onConflict=overwrite` with `Content-Type: application/octet-stream`
  - On success (`status == "imported"` or `status == "skipped"`): closes modal, `kickPoll()`, `showView('monitor')`, sets `selectedId` to returned `id`
  - On error: shows error message in modal

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add `getById()` lookup and `insertOrReplaceImported()` upsert |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Extract `hydrateEntry()` helper; add `upsertImported()` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/export-run`, `/api/import-run`, Export button, Import UI |

## Definition of Done

- [ ] `GET /api/export-run?id=X` returns a valid zip for completed/failed/cancelled/paused runs
- [ ] Zip contains `pipeline-meta.json` with all fields and `logs/` subdirectory with logsRoot contents
- [ ] `POST /api/import-run` with a valid zip registers the run in DB and memory without server restart
- [ ] Imported run appears in Monitor with correct status, pipeline name, stage list (from checkpoint), logs, and DOT source
- [ ] `originalPrompt` is preserved — imported run can be iterated via the Sprint 002 Iterate button
- [ ] Re-importing same zip with `onConflict=overwrite` (default) replaces the existing entry gracefully
- [ ] Re-importing same zip with `onConflict=skip` returns `{"status":"skipped","id":"..."}` without mutating existing data
- [ ] Export is blocked for running pipelines (returns 409)
- [ ] Import of invalid/corrupt zip (missing `pipeline-meta.json`, missing required fields, non-terminal status) returns 400 with a clear error message
- [ ] Import rejects zip entries with absolute paths or `..` traversal components
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions in existing Download ZIP, Archive, Re-run, Iterate flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Zip path traversal during extraction | Medium | High | Reject absolute paths and `..` in every entry before writing |
| `loadFromDB()` and import hydration drift | Medium | Medium | Both call shared `hydrateEntry()` helper — single source of truth |
| Large artifact bundles cause memory pressure | Low | Medium | `ZipInputStream` streams directly from request body; writes to temp dir |
| `logsRoot` collision on import (same name as existing dir) | Low | Medium | `insertOrReplaceImported()` + `upsertImported()` overwrite semantics handle it |
| Running pipeline exported mid-run | Low | High | 409 guard on `status == "running"` (not in terminal set) |
| Corrupt checkpoint prevents stage reconstruction | Medium | Low | Existing `loadFromDB()` fallback behavior preserved: run imports, stages may be minimal |

## Security Considerations

- Import is a file-ingest endpoint: enforce path normalization and reject unsafe zip entries before any disk write
- Export metadata fetched from DB (`RunStore.getById()`) — canonical, not from in-memory cache
- No code execution or LLM calls triggered on import — purely data restoration
- The `id` field from the zip is used directly as the DB key; `INSERT OR REPLACE` semantics allow overwriting only when `onConflict=overwrite`
- Use existing JSON parsing helpers and prepared SQL statements; no dynamic SQL

## Dependencies

- Sprint 001 (completed) — reliable SSE + polling convergence makes hot-import visibility immediate
- Sprint 002 (completed) — `originalPrompt` field exists in `StoredRun`; export/import carries it through

## Open Questions

1. Should there be a hard maximum zip size for the initial implementation, or defer to filesystem limits?
2. Should `download-artifacts` eventually be deprecated in favor of `export-run`, or kept as a lightweight artifact-only option?
