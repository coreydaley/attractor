# Sprint 003: Pipeline Run Export / Import

## Overview

The current "Download ZIP" button packages only the execution artifacts from a run's `logsRoot` directory — the stage outputs, logs, and checkpoint. It cannot be used to restore a run entry to a new database. There is no import path at all. Sharing a pipeline run between machines, or recovering after a database wipe, requires manual SQL work.

This sprint adds a complete round-trip: **Export** packages a run into a self-contained zip that includes both a `pipeline-meta.json` (capturing every DB field: id, fileName, dotSource, originalPrompt, status, simulate, autoApprove, createdAt, pipelineLog, logsRoot, archived) and the full `logsRoot` directory tree. **Import** accepts that zip, extracts it, restores the files to `logs/`, and registers the run in the DB and in-memory registry — so it appears in the Monitor exactly as if it had run locally.

The implementation is minimal: two new HTTP endpoints (`/api/export-run` and `/api/import-run`), a new `insertFull()` method on `RunStore` for import-time DB restoration, and a small UI addition (Export button in the pipeline action bar + Import button in the Upload modal area).

## Use Cases

1. **Share a pipeline run**: User exports a completed run from machine A. Colleague imports on machine B. Run appears in the Monitor with correct status, logs, and stage list.
2. **Backup and restore**: User wipes the database and imports a previously exported zip to restore the run history.
3. **Archive and reload**: User exports a run before archiving, imports it later when needed for reference — without having kept the original DB.
4. **Iterate on a shared run**: After importing, the user can click Iterate (Sprint 002) to fork the pipeline from the imported DOT source.

## Architecture

```text
Export flow:
GET /api/export-run?id={pipelineId}
    ├── loads PipelineEntry from registry
    ├── builds pipeline-meta.json (all StoredRun fields)
    ├── opens ZipOutputStream on response body
    ├── writes pipeline-meta.json as first entry
    └── walks logsRoot directory → writes all files as "artifacts/<relpath>"
    → returns application/zip, filename "run-<safeName>.zip"

Zip structure:
  pipeline-meta.json          ← DB metadata (all fields)
  artifacts/                  ← logsRoot contents (checkpoint.json, manifest.json, stage dirs)
    checkpoint.json
    manifest.json
    <nodeId>/
      live.log
      ...

Import flow:
POST /api/import-run  (multipart/form-data or raw zip body)
    ├── reads zip from request body
    ├── parses pipeline-meta.json
    ├── validates required fields (id, dotSource, status, fileName)
    ├── checks for ID collision → overwrite (default)
    ├── extracts "artifacts/" entries to logs/<logsRoot-basename>/
    ├── re-derives logsRoot path as logs/<originalLogsRoot basename>
    ├── calls RunStore.insertFull(storedRun) → INSERT OR REPLACE
    ├── calls PipelineRegistry.registerRestored(entry) → adds to in-memory state
    └── broadcasts update → run appears in Monitor

RunStore.insertFull(run: StoredRun):
    INSERT OR REPLACE INTO pipeline_runs (...all fields...) VALUES (...)
    (unlike insert() which sets status='running' and created_at=now,
     this preserves the original values from the zip)

PipelineRegistry.registerRestored(entry: PipelineEntry):
    Same logic as loadFromDB() applied to a single entry
    Reconstructs PipelineState from status/logs/checkpoint
    Does NOT start execution (terminal state only)
```

## Implementation Plan

### Phase 1: RunStore — `insertFull()` (~10%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `fun insertFull(run: StoredRun)`: inserts all fields with `INSERT OR REPLACE INTO pipeline_runs (...) VALUES (...)` preserving original `id`, `created_at`, `status`, `logs_root`, `pipeline_log`, `archived`, `original_prompt`

### Phase 2: PipelineRegistry — `registerRestored()` (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Add `fun registerRestored(entry: PipelineEntry)`:
  - If `id` already in `entries`: replaces the entry + removes old `orderedIds` entry
  - Reconstructs `PipelineState` from `entry.state` data (status, logs, stages from checkpoint)
  - Uses the same reconstruction logic as `loadFromDB()` (crash recovery for "running" → "failed", log lines, stage list from checkpoint)
  - Adds to `entries` and `orderedIds`
  - Does NOT call `store.insert()` (caller is responsible for DB)

### Phase 3: Backend — `/api/export-run` (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `GET /api/export-run?id={pipelineId}`:
  - Returns 404 if pipeline not found or has no entry
  - Returns 409 if pipeline is currently running (can't export a live run)
  - Builds `pipeline-meta.json` JSON string from all `PipelineEntry` fields + `state.status`, `state.pipelineLog`
  - Opens `ZipOutputStream` on `ex.responseBody`
  - Writes `pipeline-meta.json` as first zip entry
  - If `logsRoot` is non-blank and dir exists: walks all files and writes them as `artifacts/<relpath>`
  - Sets filename: `run-<safeName>-<id-suffix>.zip`

`pipeline-meta.json` format:
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
Note: `logsRootBasename` = `File(logsRoot).name` — the last path segment. On import, the server reassembles as `logs/<logsRootBasename>`.

### Phase 4: Backend — `/api/import-run` (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `POST /api/import-run` (accepts raw zip bytes as request body, `Content-Type: application/zip` or `application/octet-stream`):
  - Reads full request body into byte array
  - Opens as `ZipInputStream`
  - Finds and parses `pipeline-meta.json` entry → creates a `Map<String, Any>` of all fields
  - Validates required fields (`id`, `dotSource`, `status`, `fileName`)
  - Derives `logsRoot = "logs/${meta["logsRootBasename"]}"` (or `"logs/${meta["id"]}"` as fallback)
  - Creates `logsRoot` directory
  - Extracts all `artifacts/*` entries → strips `artifacts/` prefix → writes to `logsRoot/`
  - Constructs `StoredRun` from parsed metadata
  - Calls `store.insertFull(storedRun)`
  - Calls `registry.registerRestored(entry)` where `entry` is built via the same `loadFromDB` logic
  - Calls `broadcastUpdate()`
  - Returns `{"id": "..."}` on success
  - Returns 400 with error message for invalid/missing metadata

### Phase 5: Frontend — Export button + Import UI (~20%)

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
- [ ] In the Upload modal HTML: add an "Import from ZIP" section below the file input:
  ```html
  <div class="field" style="border-top:1px solid #30363d;padding-top:12px;margin-top:8px;">
    <label>Or import an exported run (.zip)</label>
    <input type="file" class="field-file" id="importZipInput" accept=".zip">
  </div>
  <button class="btn-primary" id="importSubmitBtn" onclick="submitImport()" style="display:none;">Import Run</button>
  ```
- [ ] Show `importSubmitBtn` when `importZipInput` has a file selected; hide upload submit when import file selected (mutually exclusive)
- [ ] Add JS `submitImport()`:
  - Reads the zip file from `importZipInput` as `ArrayBuffer`
  - POSTs to `/api/import-run` with `Content-Type: application/octet-stream`
  - On success: closes modal, `kickPoll()`, `showView('monitor')`, sets `selectedId` to imported ID
  - On error: shows error message in modal

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add `insertFull()` for full-fidelity import |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add `registerRestored()` for in-memory reconstruction |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/export-run`, `/api/import-run`, Export button, Import UI |

## Definition of Done

- [ ] `GET /api/export-run?id=X` returns a valid zip for completed/failed/cancelled runs
- [ ] Zip contains `pipeline-meta.json` with all fields and `artifacts/` subdirectory with logsRoot contents
- [ ] `POST /api/import-run` with a valid zip registers the run in DB and memory
- [ ] Imported run appears in Monitor with correct status, pipeline name, stage list (from checkpoint), logs, and DOT source
- [ ] `originalPrompt` is preserved — imported run can be iterated via the Sprint 002 Iterate button
- [ ] Re-importing same zip (same ID) overwrites the existing entry gracefully
- [ ] Export is blocked for running pipelines (returns 409)
- [ ] Import of invalid/corrupt zip returns 400 with a clear error message
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions in existing Download ZIP, Archive, Re-run, Iterate flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Large logsRoot dirs make the zip slow to stream | Low | Low | Zip streams directly to HTTP response body (same as existing download-artifacts) |
| `pipeline-meta.json` missing from imported zip | Medium | Medium | Return 400 with "pipeline-meta.json not found in zip" message |
| `logsRootBasename` collision on import (same name as existing dir) | Low | Medium | The `insertFull()` + `registerRestored()` overwrite semantics handle it |
| Running pipeline exported mid-run | Low | High | Guard export with 409 if `status == 'running'` |
| Zip with absolute `logsRoot` path from another machine | Medium | High | Never store absolute path in zip; always store only basename |
| ZipInputStream reads entries in declaration order; `pipeline-meta.json` may not be first | Low | Medium | Buffer all entries into a temp map, parse meta after full read |

## Security Considerations

- Import endpoint accepts arbitrary zip content: always validate `pipeline-meta.json` fields before writing to DB
- Zip entry names from imported zips are path-sanitized before writing to disk (strip leading `/`, prevent `../` traversal)
- No code execution or LLM calls triggered on import — purely data restoration
- The `id` field from the zip is used directly as the DB key; the `INSERT OR REPLACE` semantics allow overwriting an existing run

## Dependencies

- Sprint 002 (completed) — `originalPrompt` field exists in `StoredRun`; export/import carries it through

## Open Questions

1. Should export include logsRoot artifacts at all, or just `pipeline-meta.json` (DB record only)? Including artifacts means the stage logs and checkpoint are available on the destination, enabling Resume after import.
2. ID collision on import: overwrite (current plan) or generate a fresh ID?
3. Should the import be accessible from a dedicated "Import" button in the header (separate from the Upload modal), or combined with the existing upload flow?
