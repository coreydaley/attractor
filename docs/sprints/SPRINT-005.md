# Sprint 005: Pipeline Version History

## Overview

Attractor's Sprint 002 "Iterate" flow has a destructive flaw: each iteration overwrites the existing run's `dot_source` and `original_prompt` in-place, permanently erasing the prior state. Users cannot trace what DOT generated which output, compare results across iterations, or recover a pipeline version that worked.

This sprint introduces **pipeline version history** by making runs immutable. When a user iterates a pipeline, a NEW run is created with a new ID and its own `logsRoot` — the original run is never modified. All runs that share a common origin are linked by a single `pipeline_family_id` column on `pipeline_runs`, enabling a one-query lookup for the complete history of a pipeline.

The sprint delivers two user-facing features:
1. **Version History panel** — a collapsible accordion below each Monitor panel listing all versions in the same family, with status badges, timestamps, and Restore buttons that re-enter iterate mode from any historical version.
2. **Artifact Browser** — a modal that lists all files in a run's `logsRoot` directory with inline rendering for text/log/JSON/DOT files, directly fulfilling the seed requirement to "view the results and artifacts that were generated."

## Use Cases

1. **Review iteration history**: A pipeline has been iterated three times. User opens its Monitor tab, expands Version History, and sees three version cards with timestamps and status badges.

2. **Restore a past version**: v2's output was better than v4's. User clicks "Restore" on the v2 card — the Create view opens in iterate mode pre-filled with v2's DOT and prompt. Running it creates v5 in the same family.

3. **View artifacts of any version**: User wants the stage logs from version 1. They click "Artifacts" on the v1 card — a modal opens showing all files in that run's `logsRoot` with inline rendering for `.log`, `.md`, `.json`, `.dot` files.

4. **Persist across restarts**: After a server restart, the Version History section on every pipeline tab is fully populated from the DB — `pipeline_family_id` is stored in SQLite.

5. **Legacy runs (pre-Sprint 005)**: Runs created before this sprint have blank `pipeline_family_id`. They appear normally — the Version History section is hidden for standalone/legacy runs. No data loss.

## Architecture

```text
DB: One new column on pipeline_runs
═══════════════════════════════════════════════════════
pipeline_runs (existing table):
  + pipeline_family_id  TEXT NOT NULL DEFAULT ''
  + INDEX idx_pipeline_runs_family_created (pipeline_family_id, created_at)

Assignment rules:
  New pipeline (POST /api/run):    familyId = own run ID (self-bootstrap)
  Iterate (POST /api/iterate):     familyId = source run's familyId (inherited)
  Re-run (POST /api/rerun):        NO CHANGE — resubmit() reuses same run ID (existing behavior)
  Import (POST /api/import-run):   familyId from pipeline-meta.json; if absent, blank
  Legacy runs:                     familyId = '' (family-of-one; history panel hidden)

Example family:
  run-001  familyId=run-001  (v1, first run)
  run-002  familyId=run-001  (v2, iterated from run-001)
  run-003  familyId=run-001  (v3, restored from v2 then iterated)
  ↑ one query: SELECT * WHERE pipeline_family_id='run-001' returns all 3


API: Modified + new endpoints
═══════════════════════════════════════════════════════
POST /api/run (UPDATED — was: no familyId)
  → generates id, calls PipelineRunner.submit(..., familyId=id) [self-bootstrap]

POST /api/iterate (MODIFIED behavior)
  Was: registry.updateDotAndPrompt(id) + resubmit(id) [overwrites in-place]
  Now: PipelineRunner.submit(dotSource, entry.fileName, entry.options,
           originalPrompt, familyId=entry.familyId) → new run
       Returns: {"newId": newRunId}   [was: {"id": id}]
       Old run: UNTOUCHED, preserved in DB and memory

GET /api/pipeline-family?id={runId}  (NEW)
  → runs = store.getByFamilyId(entry.familyId)
  → [{id, displayName, createdAt, status, dotSource, originalPrompt, versionNum}]
  → versionNum = 1-based rank in chronological result set (computed, not stored)
  → empty if familyId blank

GET /api/run-artifacts?id={runId}    (NEW)
  → walks entry.logsRoot; returns [{path, size, isText}]; capped at 500

GET /api/run-artifact-file?id={runId}&path={relPath}  (NEW)
  → strict canonicalFile path-prefix check; 403 on violation
  → text/plain for .log/.txt/.md/.json/.dot; application/octet-stream otherwise


UI: Version History accordion + Artifact modal
═══════════════════════════════════════════════════════
Monitor panel (existing):
  ├── graph / status / actions / stage list / log tail [unchanged]
  └── [NEW] Version History accordion
      ├── header: "Version History (N versions)" — click to expand/collapse
      └── [expanded] version cards, newest-last:
          ┌─────────────────────────────────────────────────────┐
          │ #1  my-clever-fox  2026-02-26 10:03  ● completed    │
          │     (prompt snippet)    [Artifacts]  [Restore]      │
          ├─────────────────────────────────────────────────────┤
          │ #2  quiet-river   2026-02-26 10:15  ● running  ★    │
          │     (prompt snippet)    [Artifacts]  [Restore]      │
          └─────────────────────────────────────────────────────┘
          ★ = current run (highlighted border)

Artifact Modal (NEW):
  ┌──────────────────────────────────────┐
  │ Artifacts — my-clever-fox   [✕]      │
  ├─────────────┬────────────────────────┤
  │ Files       │ File content           │
  │  stage1/... │ {"completedNodes":...  │
  │  stage2/... │                        │
  └─────────────┴────────────────────────┘
```

### Iterate Flow: Before vs. After

| Step | Sprint 002 (current) | Sprint 005 (new) |
|------|---------------------|-----------------|
| Click "Iterate" | Opens Create view, pre-fill DOT | Same |
| Click "Run Pipeline" | `POST /api/iterate {id, dot, prompt}` | Same call, different server behavior |
| Server | `updateDotAndPrompt(id)` + `resubmit(id)` | `submit(dot, ..., familyId=entry.familyId)` → NEW run |
| Old run | **Overwritten** in DB | **Preserved** — dot_source, logs, artifacts intact |
| New run | Same ID, same tab | New ID; appears in Version History on the existing tab |
| Response | `{"id": "run-001"}` | `{"newId": "run-002"}` |
| UI navigation | Stays on same tab | Stays on same tab; `kickPoll()` discovers new run via SSE |

## Implementation Plan

### Phase 1: DB — `pipeline_family_id` column + index (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `familyId: String = ""` to `StoredRun` data class
- [ ] Migration: `ALTER TABLE pipeline_runs ADD COLUMN pipeline_family_id TEXT NOT NULL DEFAULT ''` (inside `runCatching {}`)
- [ ] Migration: `CREATE INDEX IF NOT EXISTS idx_pipeline_runs_family_created ON pipeline_runs(pipeline_family_id, created_at)` (inside `runCatching {}`)
- [ ] `insert()`: add `familyId: String = ""` parameter; include in INSERT as `pipeline_family_id`
- [ ] `getAll()`: read `pipeline_family_id` into `StoredRun.familyId`
- [ ] `getById()`: read `pipeline_family_id` into `StoredRun.familyId`
- [ ] `insertOrReplaceImported()`: include `pipeline_family_id` in INSERT OR REPLACE
- [ ] Add `fun getByFamilyId(familyId: String): List<StoredRun>`:
  - Guard: `if (familyId.isBlank()) return emptyList()`
  - Query: `SELECT ... FROM pipeline_runs WHERE pipeline_family_id=? ORDER BY created_at ASC`

### Phase 2: Registry + Runner — family ID threading (~15%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Tasks:**
- [ ] `PipelineEntry`: add `familyId: String = ""` field
- [ ] `PipelineRegistry.register()`: add `familyId: String = ""` parameter; pass to `store.insert()` and store in entry
- [ ] `PipelineRegistry.hydrateEntry()`: read `run.familyId` into `PipelineEntry.familyId`
  - Fallback: `run.familyId.ifBlank { "" }` — keep blank for legacy runs; do NOT auto-fill `run.id` (let the UI/API hide history for blank families rather than fabricate one)
- [ ] `PipelineRegistry.upsertImported()`: `hydrateEntry()` carries `familyId` automatically via new field
- [ ] `PipelineRunner.submit()`: add `familyId: String = ""` parameter; pass to `registry.register()`
  - When `familyId` is blank: self-bootstrap: capture `id` first, then call `registry.register(id, ..., familyId = id)`
  - When `familyId` is non-blank: pass through (iterate case)

> **Note**: `PipelineRunner.resubmit()` (used by Re-run) is **NOT changed** — it continues to reset and reuse the same run ID.

### Phase 3: Backend — Modified `/api/iterate` + new endpoints (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**

**Modify `POST /api/run` (was: no familyId):**
- [ ] Generate `id` first; pass `familyId = id` to `PipelineRunner.submit(...)` (explicit self-bootstrap)

**Modify `POST /api/iterate`:**
- [ ] Was: `registry.updateDotAndPrompt(id, ...) + PipelineRunner.resubmit(id, ...)`
- [ ] Now:
  1. `entry = registry.get(id)` → 404 if null; 400 if `dotSource` blank
  2. 409 if `entry.state.status.get() == "running"`
  3. `newId = PipelineRunner.submit(dotSource, entry.fileName, entry.options, registry, store, originalPrompt, familyId = entry.familyId) { broadcastUpdate() }`
  4. Return `{"newId": newId}` (changed from `{"id": id}`)

**Add `GET /api/pipeline-family?id={runId}`:**
- [ ] `entry = registry.get(id)` → 404 if null
- [ ] If `entry.familyId.isBlank()` → return `{"members":[]}`
- [ ] `members = store.getByFamilyId(entry.familyId)` → build JSON array
- [ ] Each member: `{id, displayName, createdAt, status, dotSource, originalPrompt}` — versionNum = 1-based index
- [ ] Cap at 100 members

**Add `GET /api/run-artifacts?id={runId}`:**
- [ ] `entry = registry.get(id)` → 404 if null
- [ ] `logsRoot` blank or dir doesn't exist → return `{"files":[],"truncated":false}`
- [ ] Walk `entry.logsRoot` recursively; `{path: relPath, size: bytes, isText: Boolean}` per file
- [ ] `isText` ext set: `{log, txt, md, json, dot, kt, py, js, sh, yaml, yml}`
- [ ] Cap at 500 files; `{"files":[...],"truncated":true/false}`

**Add `GET /api/run-artifact-file?id={runId}&path={relPath}`:**
- [ ] `entry = registry.get(id)` → 404; logsRoot blank → 404
- [ ] `targetFile = File(entry.logsRoot, relPath).canonicalFile`
- [ ] Security: `if (!targetFile.path.startsWith(File(entry.logsRoot).canonicalFile.path + File.separator))` → **403**
- [ ] `!targetFile.exists()` → 404
- [ ] `Content-Type: text/plain; charset=utf-8` for text exts; else `application/octet-stream`
- [ ] Stream file to response body

**Update `allPipelinesJson()`:**
- [ ] Include `"familyId":${js(entry.familyId)}` per pipeline in the JSON

**Update `GET /api/export-run` (Sprint 003):**
- [ ] Add `"familyId":"..."` to `pipeline-meta.json` JSON

**Update `POST /api/import-run` (Sprint 003):**
- [ ] Parse optional `"familyId"` from `pipeline-meta.json`; use it when constructing `StoredRun`

### Phase 4: Frontend — Version History panel + Artifact modal (~45%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**CSS additions:**
- [ ] `.version-history` — `margin-top:16px; border-top:1px solid #30363d; padding-top:12px;`
- [ ] `.vh-header` — `cursor:pointer; display:flex; align-items:center; gap:6px; color:#8b949e; font-size:0.85rem; font-weight:600; user-select:none; background:none; border:none; padding:0;`
- [ ] `.vh-header:hover` — `color:#e6edf3;`
- [ ] `.vh-list` — `margin-top:10px; display:flex; flex-direction:column; gap:6px;`
- [ ] `.vh-card` — `background:#161b22; border:1px solid #30363d; border-radius:6px; padding:10px 12px;`
- [ ] `.vh-card.vh-current` — `border-color:#388bfd;`
- [ ] `.vh-card-top` — `display:flex; align-items:center; gap:8px; margin-bottom:4px;`
- [ ] `.vh-ver` — `font-family:monospace; background:#21262d; border-radius:4px; padding:1px 6px; font-size:0.75rem; color:#f0f6fc; flex-shrink:0;`
- [ ] `.vh-name` — `font-size:0.8rem; color:#c9d1d9; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;`
- [ ] `.vh-ts` — `font-size:0.75rem; color:#6e7681; flex-shrink:0;`
- [ ] `.vh-card-bottom` — `display:flex; align-items:center; gap:6px;`
- [ ] `.vh-prompt` — `flex:1; font-size:0.75rem; color:#6e7681; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;`
- [ ] `.vh-actions` — `display:flex; gap:4px; flex-shrink:0;`
- [ ] `.btn-vh` — `font-size:0.75rem; padding:3px 8px; border-radius:4px; border:1px solid #30363d; background:#21262d; color:#c9d1d9; cursor:pointer;`
- [ ] `.btn-vh:hover` — `border-color:#388bfd; color:#f0f6fc;`
- [ ] `.artifact-overlay` — `position:fixed; inset:0; background:rgba(0,0,0,.75); z-index:200; display:none; align-items:center; justify-content:center;`
- [ ] `.artifact-dialog` — `background:#0d1117; border:1px solid #30363d; border-radius:8px; width:800px; max-width:90vw; height:520px; display:flex; flex-direction:column;`
- [ ] `.artifact-dialog-hdr` — `padding:12px 16px; border-bottom:1px solid #30363d; display:flex; justify-content:space-between; align-items:center; flex-shrink:0;`
- [ ] `.artifact-body` — `display:flex; flex:1; overflow:hidden;`
- [ ] `.artifact-files` — `width:220px; overflow-y:auto; border-right:1px solid #21262d; padding:6px 0; flex-shrink:0;`
- [ ] `.artifact-file` — `padding:4px 12px; cursor:pointer; font-size:0.78rem; color:#8b949e; font-family:monospace; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;`
- [ ] `.artifact-file.active,.artifact-file:hover` — `background:#161b22; color:#e6edf3;`
- [ ] `.artifact-view` — `flex:1; overflow-y:auto; padding:12px; font-family:monospace; font-size:0.75rem; white-space:pre-wrap; color:#e6edf3; background:#0d1117;`

**JS — Version History:**
- [ ] Add `var vhExpanded = false;` and `var vhData = null;` (reset in `buildPanel()`)
- [ ] Add `function loadVersionHistory(runId)`:
  - Fetches `GET /api/pipeline-family?id=${encodeURIComponent(runId)}`
  - On success: `vhData = data.members; renderVersionHistory(runId, data.members)`
  - On error or empty: `document.getElementById('versionHistory').style.display='none'`
- [ ] Add `function renderVersionHistory(currentRunId, members)`:
  - `members.length < 2` → hide `#versionHistory`; return
  - Show `#versionHistory`; update header label: `"Version History (${members.length} versions)"`
  - If `vhExpanded`: build `.vh-list` with version cards newest-first
  - Each card: version badge `#N`, displayName, formatted timestamp, status badge, prompt snippet (60 chars truncated), `[Artifacts]` btn, `[Restore]` btn; add `.vh-current` to the card where `member.id === currentRunId`
- [ ] Add `function toggleVersionHistory()`:
  - Toggle `vhExpanded`; re-render; update chevron (▼/▲)
- [ ] Add `function restoreVersion(vhRunId, vhDotSource, vhPrompt)`:
  - `enterIterateMode(vhRunId, vhDotSource, vhPrompt)` (Sprint 002 function reused)
  - `showView('create')`
- [ ] In `buildPanel(id)`: append `#versionHistory` div; call `loadVersionHistory(id)` after build
- [ ] In `updatePanel(id)`: if `vhData !== null`, re-call `renderVersionHistory(id, vhData)` (refresh without re-fetch)

**JS — Artifact modal:**
- [ ] Add `var artifactRunId = null;`
- [ ] Add `function openArtifacts(runId, displayName)`:
  - Show `#artifactOverlay`; set `#artifactTitle = esc(displayName)`
  - Fetch `GET /api/run-artifacts?id=${encodeURIComponent(runId)}`
  - On success: render file list in `#artifactFiles`; auto-load first `.isText` file
- [ ] Add `function loadArtifact(relPath)`:
  - Mark `.artifact-file.active` on clicked item
  - Fetch `GET /api/run-artifact-file?id=...&path=${encodeURIComponent(relPath)}`
  - On success: `document.getElementById('artifactView').textContent = responseText`
  - If `isText === false`: show `"Binary file — [Download]"` with `<a>` link
- [ ] Add `function closeArtifacts()`: `document.getElementById('artifactOverlay').style.display='none'`
- [ ] Add modal HTML body element (hidden, outside all panels):
  ```html
  <div class="artifact-overlay" id="artifactOverlay"
       onclick="if(event.target===this)closeArtifacts()">
    <div class="artifact-dialog">
      <div class="artifact-dialog-hdr">
        <span id="artifactTitle">Artifacts</span>
        <button onclick="closeArtifacts()"
          style="background:none;border:none;color:#8b949e;cursor:pointer;font-size:1.1rem;">✕</button>
      </div>
      <div class="artifact-body">
        <div class="artifact-files" id="artifactFiles"></div>
        <div class="artifact-view" id="artifactView">Select a file to view</div>
      </div>
    </div>
  </div>
  ```

**HTML — Version History section in panel (added in `buildPanel()`):**
- [ ] After log-tail div, add:
  ```html
  <div class="version-history" id="versionHistory" style="display:none;">
    <button class="vh-header" onclick="toggleVersionHistory()">
      <span>&#128221;</span>
      <span id="vhLabel">Version History</span>
      <span id="vhChevron" style="margin-left:auto;">&#9660;</span>
    </button>
    <div class="vh-list" id="vhList" style="display:none;"></div>
  </div>
  ```

**JS — Iterate flow update (`runIterated()` from Sprint 002):**
- [ ] After `POST /api/iterate` success, read `data.newId` (not `data.id`):
  - `exitIterateMode()`
  - `kickPoll()` — new run arrives via SSE; browser discovers it naturally
  - `showView('monitor')` — stay on current tab; new run appears in Version History

**JS — `applyUpdate()` / `allPipelinesJson()`:**
- [ ] `pipelines[id].familyId` populated from server JSON
- [ ] `applyUpdate()` merges `familyId` into pipelines state map

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add `familyId` to `StoredRun`; migration; index; `getByFamilyId()`; update CRUD methods |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add `familyId` to `PipelineEntry`; thread through `register()`, `hydrateEntry()` |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Add `familyId` param to `submit()`; self-bootstrap logic; `resubmit()` unchanged |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Modify `POST /api/iterate`; update `POST /api/run`; add `/api/pipeline-family`, `/api/run-artifacts`, `/api/run-artifact-file`; update export/import; Version History panel + Artifact modal |

## Definition of Done

- [ ] `pipeline_family_id` column added via backward-compatible migration; existing rows default to `""`
- [ ] DB index `idx_pipeline_runs_family_created` added via migration
- [ ] `POST /api/run` passes `familyId = id` (self-bootstrap)
- [ ] `POST /api/iterate` creates a NEW run sharing `familyId` from source; returns `{"newId": ...}`; old run's dot_source, logs, and artifacts are intact
- [ ] `POST /api/rerun` is NOT changed — same run ID reuse continues
- [ ] `GET /api/pipeline-family?id=X` returns all runs with same `familyId`, ordered by `createdAt`
- [ ] `GET /api/run-artifacts?id=X` returns file listing for run's `logsRoot`
- [ ] `GET /api/run-artifact-file` serves files from `logsRoot`; returns **403** for path-traversal attempts
- [ ] Version History accordion renders below Monitor panel for runs with **2+ family members**
- [ ] Version History is **hidden** for legacy runs (blank `familyId`) and single-member families
- [ ] Each version card shows: version badge, display name, timestamp, status badge, prompt snippet, `[Artifacts]` button, `[Restore]` button
- [ ] Artifact modal opens on `[Artifacts]` click; shows file tree; renders text files inline
- [ ] `[Restore]` enters iterate mode pre-filled with selected version's DOT and prompt
- [ ] After iterate creates new run, current tab stays open; `kickPoll()` delivers new run to SSE; new run appears in Version History
- [ ] `GET /api/export-run` includes `"familyId"` in `pipeline-meta.json`
- [ ] `POST /api/import-run` preserves `familyId` from `pipeline-meta.json` when present
- [ ] Legacy runs (no `familyId`) continue to function normally; no history panel shown
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: Dashboard, Archive, Delete, Pause, Resume, Export/Import, Generate, Re-run flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `getByFamilyId()` with blank familyId returns ALL runs | Low | High | Guard: `if (familyId.isBlank()) return emptyList()` at top of method |
| Path traversal in `/api/run-artifact-file` | Medium | High | `canonicalFile` prefix check; 403 on any violation |
| Sprint 002 frontend: `data.id` → `data.newId` field change | High | Medium | Update `runIterated()` JS in same sprint; explicit DoD check for iterate flow |
| Version History fetch races with panel build (async GET) | Low | Low | Panel shows nothing until fetch completes; no visible flash of broken state |
| Large logsRoot (thousands of files) slow artifact listing | Low | Medium | Cap at 500 files; return `"truncated": true` flag |
| Import zips from pre-Sprint 005 lack `familyId` | Medium | Low | Missing field treated as blank — imported run has no history. Acceptable. |
| `pipeline_family_id` column migration fails on old DB | Very Low | Low | `runCatching {}` wrapper; existing pattern used for all prior migrations |

## Security Considerations

- `/api/run-artifact-file`: `File(...).canonicalFile.path` prefix check strictly enforced before any file read; `../` traversal resolved and rejected with 403
- `pipeline_family_id` is a server-generated run ID (`run-<timestamp>-<counter>`), never derived from user input
- All pipeline names and prompts rendered in version history cards pass through existing `esc()` helper
- No new auth surface; same local-only model as all existing endpoints

## Dependencies

- Sprint 002 (completed) — `POST /api/iterate` refactored; `enterIterateMode()` JS function reused by `[Restore]`
- Sprint 003 (completed) — `pipeline-meta.json` updated to carry `familyId`; `insertOrReplaceImported()` already handles all DB fields
- Sprint 004 (completed) — Dashboard continues to show all runs as cards; no changes needed

## Open Questions

1. Should archived runs still appear in the Version History panel for the current run? (Current plan: yes, visible but potentially de-emphasized)
2. Should the Artifacts modal also offer a direct "Download ZIP" link back to the Sprint 003 `/api/export-run` endpoint?
3. Should the Dashboard card for a pipeline show a `(v3)` version count badge when a family has 3+ runs?
