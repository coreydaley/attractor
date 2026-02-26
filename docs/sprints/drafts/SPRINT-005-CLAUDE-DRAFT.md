# Sprint 005: Pipeline Version History

## Overview

Every Attractor pipeline is a living document: users generate it from natural language, then iterate on it one or more times via the Sprint 002 "Iterate" flow. Currently each iteration **overwrites** the existing run's DOT and original prompt in-place, permanently destroying the prior state. Users who want to compare old and new behavior must remember what changed — there is no path back to a version that worked.

This sprint introduces **pipeline version history**: each time a pipeline is iterated, a NEW run is created (new ID, new `logsRoot`, own artifacts). The original run is never modified — it becomes a permanent historical record. All runs that share a common origin are linked by a single `pipeline_family_id` column on `pipeline_runs`, enabling a one-query lookup for the complete history of a pipeline.

The UI adds a collapsible **Version History** section inside each Monitor panel showing all runs in the same family, ordered chronologically. Users can view any historical version's DOT, logs, and artifacts inline, or click "Restore" to enter iterate mode pre-filled with that version's DOT and prompt.

The `pipeline_family_id` approach was chosen over a linked-list `parent_run_id` model because:
1. Single SQL query to fetch all versions (`WHERE pipeline_family_id = ?`)
2. No chain-walking required on startup or in API calls
3. Branching works naturally — restoring v2 and iterating still joins the same family
4. Simpler export/import: one field to carry forward

## Use Cases

1. **Review iteration history**: A pipeline has been iterated three times. User opens its Monitor tab, expands Version History, and sees three version cards with timestamps and status. They can compare DOT sources and outputs without any manual bookkeeping.

2. **Restore a past version**: v2's output was better than v4's. User clicks "Restore" on the v2 card — the Create view opens in iterate mode pre-filled with v2's DOT and prompt. Running it creates v5 in the same family (branching from v2's DOT).

3. **View artifacts of any version**: User wants the stage logs from version 1. They click "Artifacts" on the v1 card — an artifact browser opens showing all files in that run's `logsRoot`, with inline rendering for `.log`, `.md`, `.json`, `.dot` files.

4. **Persist across restarts**: After a server restart, the Version History section on every pipeline tab is fully populated from the DB — `pipeline_family_id` is stored in SQLite.

5. **Legacy runs (pre-Sprint 005)**: Runs created before this sprint have blank `pipeline_family_id`. They appear in the Monitor normally — the Version History section is hidden for standalone/legacy runs. No data loss.

## Architecture

```text
DB Layer: pipeline_family_id column (single flat field)
═══════════════════════════════════════════════════════
pipeline_runs (existing table, one new column added):
  + pipeline_family_id  TEXT NOT NULL DEFAULT ''

Assignment rules:
  New pipeline (upload/generate):   family_id = own run ID (self-bootstrap)
  Iterate:                          family_id = source run's family_id (inherited)
  Re-run:                           family_id = source run's family_id (preserved)
  Import (Sprint 003):              family_id from pipeline-meta.json (preserved)
  Legacy runs:                      family_id = '' (no history shown)

Example lineage:
  run-001  family=run-001   (v1, self-bootstrapped)
  run-002  family=run-001   (v2, iterated from run-001)
  run-003  family=run-001   (v3, iterated from run-002)
  run-004  family=run-001   (v4, restored from v2 then iterated — same family!)
  ↑ one query: SELECT * WHERE pipeline_family_id='run-001' returns all 4


API Changes
═══════════════════════════════════════════════════════
POST /api/iterate (MODIFIED behavior)
  Was: registry.updateDotAndPrompt(id, ...) + resubmit(id)
  Now: PipelineRunner.submit(dotSource, fileName, options, originalPrompt,
           familyId=sourceFamilyId) { broadcastUpdate() }
       Returns: {"newId": newRunId}
       Old run: UNTOUCHED, preserved with its logsRoot and artifacts

GET /api/pipeline-family?id={runId}  (NEW)
  → looks up entry's familyId
  → SELECT * FROM pipeline_runs WHERE pipeline_family_id=familyId ORDER BY created_at ASC
  → returns [{id, displayName, createdAt, status, dotSource, originalPrompt, versionNum}]
  → versionNum computed as 1-based rank in result set (not stored)

GET /api/run-artifacts?id={runId}  (NEW)
  → walks entry.logsRoot directory
  → returns {files: [{path, size, isText}]}
  → capped at 500 files

GET /api/run-artifact-file?id={runId}&path={relPath}  (NEW)
  → validates path is within logsRoot (canonicalFile comparison)
  → streams file; text/plain for .log/.txt/.md/.json/.dot, else application/octet-stream


UI Layer
═══════════════════════════════════════════════════════
Monitor panel (existing):
  ├── graph
  ├── status + action buttons
  ├── stage list
  ├── log tail
  └── [NEW] Version History accordion
      ├── header: "Version History (N versions)" — click to expand
      └── [expanded]:
          ┌──────────────────────────────────────────────────────┐
          │ #1  my-clever-fox  2026-02-26 10:03  ● completed      │
          │     [Artifacts]  [Restore]                            │
          ├──────────────────────────────────────────────────────┤
          │ #2  quiet-river   2026-02-26 10:15  ● running  ← cur │
          │     [Artifacts]  [Restore]                            │
          └──────────────────────────────────────────────────────┘

Artifact Modal (NEW):
  ┌──────────────────────────────────┐
  │ Artifacts — my-clever-fox        │  [✕]
  ├──────────────────────────────────┤
  │ File list     │ File content      │
  │  checkpoint.. │ {                 │
  │  stage1/out.. │   "completedNo..  │
  │  stage2/live.log ← selected      │
  └──────────────────────────────────┘
```

### Iterate Flow: Before vs. After

| Step | Sprint 002 (current) | Sprint 005 (new) |
|------|---------------------|-----------------|
| Click "Iterate" | Opens Create view, pre-fill DOT | Same |
| Click "Run Pipeline" | `POST /api/iterate {id, dot, prompt}` | Same call, different server behavior |
| Server side | `updateDotAndPrompt(id)` + `resubmit(id)` | `submit(dot, ..., familyId=entry.familyId)` → new run |
| Old run | **Overwritten** — original DOT/logs gone | **Preserved** — unchanged in DB and memory |
| New run | Same ID `id`, same tab | New ID `newId`, appears in Version History |
| UI navigation | Stays on same tab | Stays on same tab — new run shows in history panel |

## Implementation Plan

### Phase 1: DB — `pipeline_family_id` column (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `familyId: String = ""` to `StoredRun` data class
- [ ] Migration: `ALTER TABLE pipeline_runs ADD COLUMN pipeline_family_id TEXT NOT NULL DEFAULT ''` (inside `runCatching {}`)
- [ ] `insert()`: add `familyId: String = ""` parameter; include in INSERT (`pipeline_family_id` column)
- [ ] `getAll()`: read `pipeline_family_id` into `StoredRun.familyId`
- [ ] `getById()`: read `pipeline_family_id` into `StoredRun.familyId`
- [ ] `insertOrReplaceImported()`: include `pipeline_family_id` in INSERT OR REPLACE
- [ ] Add `fun getByFamilyId(familyId: String): List<StoredRun>`:
  - `SELECT ... FROM pipeline_runs WHERE pipeline_family_id=? ORDER BY created_at ASC`
  - Guard: if `familyId.isBlank()` return empty list immediately

### Phase 2: Registry + Runner — family ID threading (~20%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Tasks:**
- [ ] `PipelineEntry`: add `familyId: String = ""` field
- [ ] `PipelineRegistry.register()`: add `familyId: String = ""` parameter; pass to `store.insert()` and store in entry
- [ ] `PipelineRegistry.hydrateEntry()`: read `run.familyId` into `PipelineEntry.familyId`
- [ ] `PipelineRegistry.upsertImported()`: `hydrateEntry()` carries `familyId` automatically via new field
- [ ] `PipelineRunner.submit()`: add `familyId: String = ""` parameter; pass to `registry.register()`
  - When `familyId` is blank: set `familyId = id` AFTER generating `id` (self-bootstrap)
  - When `familyId` is non-blank: use provided value (iterate/re-run cases)

### Phase 3: Backend — Modified `/api/iterate` + new endpoints (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**

**Modify `POST /api/iterate`:**
- [ ] Was: `registry.updateDotAndPrompt(id, ...) + PipelineRunner.resubmit(id, ...)`
- [ ] Now:
  1. `entry = registry.get(id)` → 404 if null; 400 if dotSource blank
  2. 409 guard: if `entry.state.status.get() == "running"` → return 409
  3. `newId = PipelineRunner.submit(dotSource, entry.fileName, entry.options, registry, store, originalPrompt, familyId = entry.familyId) { broadcastUpdate() }`
  4. Return `{"newId": newId}` (changed from `{"id": id}`)

**Add `GET /api/pipeline-family?id={runId}`:**
- [ ] `entry = registry.get(id)` → 404 if null
- [ ] If `entry.familyId.isBlank()` → return `{"members":[]}`
- [ ] `members = store.getByFamilyId(entry.familyId)` → build JSON array
- [ ] Each member: `{id, displayName, createdAt, status, dotSource, originalPrompt}` — version numbers computed 1-based as index in result
- [ ] Cap at 100 members (edge case guard)

**Add `GET /api/run-artifacts?id={runId}`:**
- [ ] `entry = registry.get(id)` → 404 if null
- [ ] If `entry.logsRoot.isBlank()` or dir doesn't exist → return `{"files":[]}`
- [ ] Walk `entry.logsRoot` recursively; build `{path: relPath, size: bytes, isText: Boolean}` per file
- [ ] `isText = ext in {log, txt, md, json, dot, kt, py, js, sh, yaml, yml}`
- [ ] Cap at 500 files; return `{"files":[...], "truncated":true/false}`

**Add `GET /api/run-artifact-file?id={runId}&path={relPath}`:**
- [ ] `entry = registry.get(id)` → 404 if null; logsRoot blank → 404
- [ ] `targetFile = File(entry.logsRoot, relPath).canonicalFile`
- [ ] Security: `if (!targetFile.path.startsWith(File(entry.logsRoot).canonicalFile.path + File.separator))` → 403
- [ ] If `!targetFile.exists()` → 404
- [ ] `Content-Type: text/plain; charset=utf-8` for text extensions; else `application/octet-stream`
- [ ] `Content-Disposition: inline` for text; `attachment; filename="..."` for binary
- [ ] Stream file to response body

**Update `POST /api/upload`:**
- [ ] Generate `id` first; call `PipelineRunner.submit(..., familyId = id)` — explicit self-bootstrap

**Update `POST /api/generate` → run (the endpoint that receives NL-generated DOT):**
- [ ] Same: `familyId = id` self-bootstrap

**Update `allPipelinesJson()`:**
- [ ] Include `"familyId":${js(entry.familyId)}` per pipeline in the JSON

**Update `pipeline-meta.json` in export (Sprint 003):**
- [ ] Add `"familyId": "..."` field to the export JSON (backwards compatible — old imports just won't have it)

**Update `POST /api/import-run`:**
- [ ] Parse optional `"familyId"` from `pipeline-meta.json`; use it when constructing `StoredRun`

### Phase 4: Frontend — Version History panel + Artifact modal (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**CSS additions:**
- [ ] `.version-history` — `margin-top:16px; border-top:1px solid #30363d; padding-top:12px;`
- [ ] `.vh-header` — `cursor:pointer; display:flex; align-items:center; gap:6px; color:#8b949e; font-size:0.85rem; font-weight:600; user-select:none; background:none; border:none; padding:0;`
- [ ] `.vh-header:hover` — `color:#e6edf3;`
- [ ] `.vh-list` — `margin-top:10px; display:flex; flex-direction:column; gap:6px;`
- [ ] `.vh-card` — `background:#161b22; border:1px solid #30363d; border-radius:6px; padding:10px 12px;`
- [ ] `.vh-card.current-version` — `border-color:#388bfd;`
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
- [ ] `.artifact-file.active, .artifact-file:hover` — `background:#161b22; color:#e6edf3;`
- [ ] `.artifact-view` — `flex:1; overflow-y:auto; padding:12px; font-family:monospace; font-size:0.75rem; white-space:pre-wrap; color:#e6edf3; background:#0d1117;`

**JS — Version History:**
- [ ] Add `var vhExpanded = false;` (per-panel, reset in `buildPanel()`)
- [ ] Add `var vhData = null;` (cache for current panel's family data)
- [ ] Add `function loadVersionHistory(runId)`:
  - Fetches `GET /api/pipeline-family?id=${encodeURIComponent(runId)}`
  - On success: `vhData = data.members; renderVersionHistory(runId, data.members)`
  - On error: hides `#versionHistory`
- [ ] Add `function renderVersionHistory(currentRunId, members)`:
  - If `members.length < 2`: `document.getElementById('versionHistory').style.display='none'; return`
  - Show `#versionHistory`; update header label: `"Version History (${members.length} versions)"`
  - If `vhExpanded`: render `.vh-list` with version cards
  - Each card: version badge `#N` (1-based), displayName, formatted timestamp, status badge, prompt snippet (60 chars), `[Artifacts]` button, `[Restore]` button; `.current-version` class for `id === currentRunId`
- [ ] Add `function toggleVersionHistory()`:
  - Toggle `vhExpanded`; re-render history list; update chevron
- [ ] Add `function restoreVersionFromHistory(vhRunId, vhDotSource, vhPrompt)`:
  - `enterIterateMode(vhRunId, vhDotSource, vhPrompt)`; `showView('create')`
- [ ] In `buildPanel(id)`: reset `vhExpanded = false; vhData = null;` then call `loadVersionHistory(id)` after panel is built
- [ ] In `updatePanel(id)`: if `vhData !== null` call `renderVersionHistory(id, vhData)` (refresh without re-fetch)

**JS — Artifact modal:**
- [ ] Add `var artifactRunId = null;`
- [ ] Add `function openArtifacts(runId, displayName)`:
  - `artifactRunId = runId`
  - Set title in `#artifactTitle` to `displayName`
  - Show `#artifactOverlay`
  - Fetch `GET /api/run-artifacts?id=${encodeURIComponent(runId)}`
  - On success: render file list in `#artifactFiles`; auto-load first text file
- [ ] Add `function loadArtifact(relPath)`:
  - Mark active in file list
  - Fetch `GET /api/run-artifact-file?id=${encodeURIComponent(artifactRunId)}&path=${encodeURIComponent(relPath)}`
  - On success (text): set `#artifactView.textContent = responseText`
  - If `isText === false`: show download link in `#artifactView`
- [ ] Add `function closeArtifacts()`: `artifactRunId = null; document.getElementById('artifactOverlay').style.display = 'none'`
- [ ] Add artifact modal HTML (hidden) inside `<body>`:
  ```html
  <div class="artifact-overlay" id="artifactOverlay" onclick="if(event.target===this)closeArtifacts()">
    <div class="artifact-dialog">
      <div class="artifact-dialog-hdr">
        <span id="artifactTitle">Artifacts</span>
        <button onclick="closeArtifacts()" style="background:none;border:none;color:#8b949e;cursor:pointer;font-size:1.1rem;">✕</button>
      </div>
      <div class="artifact-body">
        <div class="artifact-files" id="artifactFiles"></div>
        <div class="artifact-view" id="artifactView">Select a file to view</div>
      </div>
    </div>
  </div>
  ```

**JS — Iterate flow update:**
- [ ] In `runIterated()` (Sprint 002 JS): after `POST /api/iterate` success, read `data.newId`:
  - `exitIterateMode()`
  - If `data.newId`: `kickPoll()` — the new run will appear in the SSE stream; stay on current tab
  - `showView('monitor')`
  - (No longer need to navigate to the old ID explicitly — the tab stays, history panel shows the new run)

**JS — `allPipelinesJson()` and `applyUpdate()`:**
- [ ] `pipelines[id].familyId` now set from server JSON
- [ ] `applyUpdate()`: merge `familyId` into pipelines state

**HTML — Version History section added to panel template in `buildPanel()`:**
- [ ] After log tail div, add:
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

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add `familyId` to `StoredRun`; migration; `getByFamilyId()`; update all CRUD methods |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add `familyId` to `PipelineEntry`; thread through `register()`, `hydrateEntry()` |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Add `familyId` param to `submit()`; self-bootstrap logic |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Modify `POST /api/iterate`; add `/api/pipeline-family`, `/api/run-artifacts`, `/api/run-artifact-file`; update export/import; add Version History panel + Artifact modal |

## Definition of Done

- [ ] `pipeline_family_id` column added via backward-compatible migration; existing rows default to `""`
- [ ] New pipelines (upload/generate) get `familyId = runId` (self-bootstrap)
- [ ] `POST /api/iterate` creates a NEW run sharing `familyId` from source; returns `{"newId": ...}`; old run's dot_source, logs, and artifacts are intact
- [ ] `GET /api/pipeline-family?id=X` returns all runs with same `familyId`, ordered by `createdAt`
- [ ] `GET /api/run-artifacts?id=X` returns file listing for a run's `logsRoot`
- [ ] `GET /api/run-artifact-file` serves files from `logsRoot`; rejects path traversal with 403
- [ ] Version History section renders below panel for runs with 2+ family members
- [ ] Version History is hidden for standalone/legacy runs (familyId blank or only 1 member)
- [ ] Each version card shows: version badge, display name, timestamp, status badge, prompt snippet, Artifacts button, Restore button
- [ ] Artifacts modal shows file tree; renders text files inline; shows download link for binary
- [ ] Restore button enters iterate mode pre-filled with selected version's DOT and prompt
- [ ] Sprint 002 iterate flow: after iterate creates new run, current tab stays open; new run appears in Version History
- [ ] Re-run also inherits `familyId` from source run (new run added to same family)
- [ ] Export (`GET /api/export-run`) includes `familyId` in `pipeline-meta.json`
- [ ] Import (`POST /api/import-run`) preserves `familyId` from `pipeline-meta.json` when present
- [ ] Legacy runs (no `familyId`) continue to work normally; no history section shown
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: Dashboard, Archive, Delete, Pause, Resume, Export/Import, Generate, Re-run flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `getByFamilyId()` with blank familyId returns ALL runs | Low | High | Guard: `if (familyId.isBlank()) return emptyList()` at top of method |
| Path traversal in `/api/run-artifact-file` | Medium | High | Strict `canonicalFile` prefix check; return 403 on violation |
| Sprint 002 frontend: `data.id` vs `data.newId` field name change | High | Medium | Update `runIterated()` JS in same sprint; test full iterate flow |
| `POST /api/rerun` behavior change (new run per re-run may surprise users) | Medium | Medium | Existing tab stays visible; new run appears in history — less surprising than creating a new tab |
| Version History fetch races with panel build | Low | Low | Panel shows nothing until fetch completes; no flash of broken state |
| Large logsRoot (thousands of files) slows artifact listing | Low | Medium | Cap at 500 files; return `"truncated": true` flag in response |
| Import zips from pre-Sprint 005 lack `familyId` | Medium | Low | Missing field treated as blank — imported run has no history. Acceptable. |

## Security Considerations

- `/api/run-artifact-file`: `File(...).canonicalFile.path` prefix check strictly enforced; `../` traversal resolved and rejected with 403
- `pipeline_family_id` is a server-generated run ID (format `run-<timestamp>-<counter>`), never user-controlled input
- All pipeline names/prompts rendered in version history cards pass through existing `esc()` helper
- No new authentication surface; same local-only model as all existing endpoints

## Dependencies

- Sprint 002 (completed) — `POST /api/iterate` refactored; `enterIterateMode()` JS reused by Restore
- Sprint 003 (completed) — `pipeline-meta.json` updated to carry `familyId`; `insertOrReplaceImported()` updated
- Sprint 004 (completed) — Dashboard not affected; `allPipelinesJson()` updated to include `familyId`

## Open Questions

1. Should Re-run (not just Iterate) also inherit `familyId`? The draft assumes yes (preserves the "all runs of this pipeline" grouping), but it changes Re-run behavior slightly.
2. Should the Version History panel also be visible on the Dashboard card (e.g., a "v3" badge on cards with 3+ versions)?
3. Should the Artifacts modal include a download button for the entire run's zip (linking to Sprint 003's export endpoint)?
4. Should version history also be shown for pipelines accessed via the Archive view?
