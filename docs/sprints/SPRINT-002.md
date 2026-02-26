# Sprint 002: Pipeline Iteration

## Overview

Attractor's inner development loop has a gap: after watching a run complete or fail, there is no in-browser path to tweak the pipeline and re-run it. The user must leave the web UI, edit the `.dot` file in a text editor, and re-upload. The `Re-run` button reuses the same DOT unchanged, and `Resume` continues from a checkpoint — neither enables modification.

This sprint adds a dedicated **Iterate** flow. When a pipeline run reaches a terminal state (completed, failed, or cancelled), an "Iterate" button appears in the action bar. Clicking it navigates to the Create view with:
- The run's existing DOT source pre-filled in the DOT textarea
- The original natural language description pre-filled in the description box (if the run was created via the Create view)

The user can describe modifications and click "Modify Pipeline" to have the LLM stream back a revised DOT, or edit the DOT directly. When they click "Run Pipeline", the existing tab and run ID are **reused in-place** — the entry's DOT source is updated, the state resets, and `resubmit()` re-runs the pipeline. No new tab is created; the same run shows updated progress.

Storing the original prompt requires adding an `original_prompt` column to the DB (with a backward-compatible migration) and threading it through `RunStore → StoredRun → PipelineEntry → JSON API → browser`.

## Use Cases

1. **Iterate after failure**: A pipeline fails. User clicks Iterate — the description and DOT pre-fill. User types "Add a validation step before the exit node", clicks Modify, then Run. The same tab resets and re-runs with the revised DOT.
2. **Tweak prompts**: A pipeline completes but outputs aren't right. User iterates, edits `prompt` attributes directly in the DOT textarea, clicks Run. Same tab re-runs.
3. **Refine a generated pipeline**: User created a pipeline via the Create view. After running it, they click Iterate — both the description and DOT come back pre-filled. They tweak the description and Modify to get a better-structured pipeline.
4. **Uploaded pipeline**: User uploaded a `.dot` file. No original prompt is stored, so the description box opens empty. User can describe modifications or edit DOT directly.

## Architecture

```text
Monitor view (terminal-state run)
    │
    │  click "Iterate"
    ▼
iteratePipeline()
    ├── validates dotSource is non-empty
    └── enterIterateMode(id)
            ├── iterateSourceId = id
            ├── dotPreview.value    = pipelines[id].dotSource
            ├── nlInput.value       = pipelines[id].originalPrompt || ''
            ├── nlInput placeholder = "Describe modifications…"
            ├── modifyBtn visible, runBtn enabled
            ├── renderGraph()
            └── showView('create')

Create view (iterate mode)
    ├── [Modify Pipeline] ──► modifyDot()
    │       ├── reads baseDot from dotPreview.value
    │       ├── reads changes from nlInput.value
    │       └── POST /api/iterate/stream {baseDot, changes}
    │               → streams deltas → dotPreview rebuilt
    │               → on done: renderGraph()
    │
    └── [Run Pipeline] ──► runIterated()      ← new, replaces runGenerated() in iterate mode
            ├── POST /api/iterate {id, dotSource, originalPrompt}
            │       ├── registry.updateDotAndPrompt(id, dotSource, originalPrompt)
            │       ├── store.updateDotAndPrompt(id, dotSource, originalPrompt)
            │       └── PipelineRunner.resubmit(id, registry, store, onUpdate)
            ├── exitIterateMode()
            └── showView('monitor')          ← returns to existing tab

New streaming endpoint (LLM modification):
POST /api/iterate/stream
  body:     { "baseDot": "...", "changes": "..." }
  response: text/event-stream
    data: {"delta":"..."}
    data: {"done":true,"dotSource":"..."}
    data: {"error":"..."}
  errors: 400 if baseDot or changes is empty/blank

New apply-and-rerun endpoint:
POST /api/iterate
  body:     { "id": "...", "dotSource": "...", "originalPrompt": "..." }
  response: {"id":"..."}
  errors: 404 if run not found; 400 if dotSource blank

originalPrompt storage path:
  POST /api/upload {dotSource, fileName, simulate, autoApprove, originalPrompt?}
      → PipelineRegistry.register(id, fileName, dotSource, options, originalPrompt)
      → RunStore.insert(..., originalPrompt)
      → persisted to DB
  GET /api/pipelines
      → allPipelinesJson() includes "originalPrompt" field per pipeline
      → browser: pipelines[id].originalPrompt
```

## Implementation Plan

### Phase 1: Store `originalPrompt` through the stack (~25%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] `RunStore`: add `original_prompt` field to `StoredRun` data class (default `""`)
- [ ] `RunStore.init`: add backward-compatible migration: `ALTER TABLE pipeline_runs ADD COLUMN original_prompt TEXT NOT NULL DEFAULT ''` (inside `runCatching {}` like existing migrations)
- [ ] `RunStore.insert()`: add `originalPrompt: String = ""` parameter; include in INSERT statement
- [ ] `RunStore.getAll()`: read `original_prompt` column into `StoredRun.originalPrompt`
- [ ] Add `RunStore.updateDotAndPrompt(id: String, dotSource: String, originalPrompt: String)`: updates both `dot_source` and `original_prompt` for a given run ID
- [ ] `PipelineEntry`: add `originalPrompt: String = ""` field
- [ ] `PipelineRegistry.register()`: add `originalPrompt: String = ""` parameter; pass to `store.insert()` and store in `PipelineEntry`
- [ ] Add `PipelineRegistry.updateDotAndPrompt(id, dotSource, originalPrompt)`: updates `entries[id]` via `computeIfPresent` and calls `store.updateDotAndPrompt()`
- [ ] `PipelineRegistry.loadFromDB()`: read `run.originalPrompt` and store in `PipelineEntry`
- [ ] `WebMonitorServer.allPipelinesJson()`: include `"originalPrompt":${js(entry.originalPrompt)}` in each pipeline's JSON object (alongside existing `dotSource`, `fileName`, etc.)
- [ ] `POST /api/upload`: parse optional `originalPrompt` field from request body; pass to `PipelineRegistry.register()`
- [ ] Create view JS `runGenerated()`: include `originalPrompt: document.getElementById('nlInput').value` in the `POST /api/upload` body
- [ ] Create view JS `submitUpload()` (file upload path): send `originalPrompt: ''` (no natural language description for file uploads)

### Phase 2: Backend — `DotGenerator.iterateStream()` + `/api/iterate/stream` + `/api/iterate` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/DotGenerator.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `fun iterateStream(baseDot: String, changes: String, onDelta: (String) -> Unit): String` to `DotGenerator`
  - Validates API key (same guard as other methods)
  - Builds prompt: `"Given the following existing Attractor pipeline DOT source:\n\n$baseDot\n\nModify it according to these instructions: $changes\n\nOutput ONLY the modified raw DOT source — no markdown fences, no explanations.\nKeep all existing nodes and edges unless explicitly told to remove them."`
  - Delegates to `generateStream(prompt, onDelta)`
- [ ] Add `POST /api/iterate/stream` to `WebMonitorServer` (mirrors `/api/generate/stream`):
  - Reads `baseDot` and `changes` from JSON body
  - Returns 400 with `{"error":"baseDot is required"}` if blank
  - Returns 400 with `{"error":"changes is required"}` if blank
  - Sets SSE headers; streams `DotGenerator.iterateStream()` deltas; sends `{"done":true,"dotSource":"..."}` or `{"error":"..."}`
- [ ] Add `POST /api/iterate` to `WebMonitorServer` (apply-and-rerun):
  - Reads `id`, `dotSource`, `originalPrompt` from JSON body
  - Returns 404 if `registry.get(id) == null`
  - Returns 400 if `dotSource` is blank
  - Calls `registry.updateDotAndPrompt(id, dotSource, originalPrompt)`
  - Calls `PipelineRunner.resubmit(id, registry, store) { broadcastUpdate() }`
  - Returns `{"id": id}`

### Phase 3: Frontend — "Iterate" button in Monitor panel (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] In `buildPanel()`: add Iterate button after `rerunBtn`:
  `'<button class="btn-rerun" id="iterateBtn" style="display:none;" onclick="iteratePipeline()">&#9998;&ensp;Iterate</button>'`
- [ ] In `updatePanel()`: show `iterateBtn` when status is `completed | failed | cancelled` AND `p.dotSource` is non-empty
- [ ] Add JS `iteratePipeline()`:
  ```javascript
  function iteratePipeline() {
    var id = selectedId;
    if (!id || !pipelines[id] || !pipelines[id].dotSource) return;
    enterIterateMode(id, pipelines[id].dotSource, pipelines[id].originalPrompt || '');
    showView('create');
  }
  ```

### Phase 4: Frontend — Create view iterate mode + Modify + runIterated (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] Add `var iterateSourceId = null;` at top of `<script>` block
- [ ] Add `function enterIterateMode(id, dot, prompt)`:
  - `iterateSourceId = id`
  - `document.getElementById('dotPreview').value = dot`
  - `document.getElementById('nlInput').value = prompt`
  - Update `nlInput.placeholder` to `"Describe modifications to make\u2026"`
  - Show `modifyBtn`; enable `runBtn`
  - Set `genStatus` text to `"Iterate mode \u2014 modify description or edit DOT"`
  - Call `renderGraph()`
- [ ] Add `function exitIterateMode()`:
  - `iterateSourceId = null`
  - Restore `nlInput.placeholder` to `"e.g. \u201cWrite comprehensive unit tests\u2026\u201d"`
  - Hide `modifyBtn`
  - Reset `genStatus` to `"Start typing to generate\u2026"`
- [ ] In `showView()`: call `exitIterateMode()` when `name !== 'create'`
- [ ] Add "Modify Pipeline" button in Create view `run-row` (initially `display:none`):
  `'<button class="run-btn" id="modifyBtn" style="display:none;" onclick="modifyDot()">&#128260;&ensp;Modify Pipeline</button>'`
- [ ] Add JS `modifyDot()` (streaming via `/api/iterate/stream`):
  - Reads `baseDot` from `dotPreview.value`; `changes` from `nlInput.value`
  - If `changes` is empty: set error status and return
  - Disables `modifyBtn` and `runBtn`; sets status to `"Modifying\u2026"`; clears `dotPreview.value`
  - Fetches `/api/iterate/stream` with SSE; on delta appends to `dotPreview.value`; on done replaces + calls `renderGraph()`; on error shows error + re-enables
- [ ] Add JS `runIterated()`:
  ```javascript
  function runIterated() {
    var id = iterateSourceId;
    var dotSource = document.getElementById('dotPreview').value.trim();
    var originalPrompt = document.getElementById('nlInput').value.trim();
    if (!dotSource) return;
    fetch('/api/iterate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: id, dotSource: dotSource, originalPrompt: originalPrompt })
    }).then(function(r) { return r.json(); })
      .then(function() { exitIterateMode(); showView('monitor'); kickPoll(); })
      .catch(function(e) { /* show error */ });
  }
  ```
- [ ] In `runGenerated()`: check `if (iterateSourceId) { runIterated(); return; }` at the top — so the existing "Run Pipeline" button routes correctly in both modes
- [ ] Guard `nlInput` oninput debounce: `if (iterateSourceId) return;` to prevent auto-generate from overwriting pre-filled DOT

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add `original_prompt` column (migration), `updateDotAndPrompt()` method |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add `originalPrompt` to `PipelineEntry`; `updateDotAndPrompt()` method |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Add `iterateStream()` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/iterate/stream`, `/api/iterate`, Iterate button, iterate-mode JS |

## Definition of Done

- [ ] `RunStore` adds `original_prompt` column via migration; existing rows default to `""`
- [ ] `POST /api/upload` accepts optional `originalPrompt`; stored to DB
- [ ] `GET /api/pipelines` returns `originalPrompt` per pipeline
- [ ] `runGenerated()` sends `originalPrompt: nlInput.value` with every upload
- [ ] "Iterate" button appears for `completed`, `failed`, `cancelled` runs with non-empty `dotSource`
- [ ] Clicking "Iterate" opens Create view; DOT textarea pre-filled; description pre-filled (or empty for file-uploaded pipelines)
- [ ] `nlInput` auto-generate-on-type is suppressed in iterate mode
- [ ] "Modify Pipeline" button visible in iterate mode; calls `/api/iterate/stream`; streams revised DOT into textarea
- [ ] "Run Pipeline" in iterate mode calls `/api/iterate` which updates the existing entry and resubmits
- [ ] The existing Monitor tab and run ID are reused; no new tab is created
- [ ] After iterate-run, the original run's DOT is updated in DB to the new DOT
- [ ] Navigating away from Create exits iterate mode cleanly
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: Create → Generate → Run, Upload → Run, Re-run, Resume, Pause/Cancel, Archive

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Auto-generate-on-type clobbers pre-filled DOT in iterate mode | Medium | High | Guard `nlInput` oninput: `if (iterateSourceId) return;` |
| `dotSource` blank for old runs | Low | Low | `iterateBtn` hidden when `dotSource` is empty |
| Iteration prompt produces invalid DOT | Medium | Medium | `renderGraph()` triggers `/api/fix-dot` auto-fix path on failure |
| Model context window exceeded by very large DOT | Low | Medium | Realistic pipelines are well within limits; error surfaces in `genStatus` |
| DB migration fails on locked connection | Very low | Low | Wrapped in `runCatching {}` like all existing migrations |
| `resubmit()` called on a currently-running pipeline | Low | Medium | `/api/iterate` should guard: return 409 if `status == 'running'` |

## Security Considerations

- `originalPrompt` is user-provided text stored to SQLite — no SQL injection risk since prepared statements are used throughout `RunStore`
- `/api/iterate/stream` and `/api/iterate` have the same local-only CORS policy as all other `/api/*` endpoints
- `baseDot` and `originalPrompt` passed to LLM under the same trust model as existing `prompt` in `/api/generate/stream`
- Streamed output passed through existing `js()` JSON-escaping helper

## Dependencies

- Sprint 001 (completed) — SSE streaming infrastructure; `kickPoll()` for post-resubmit updates

## Open Questions

1. Should Iterate also appear in the Archived runs list? (Deferred)
2. Should archived runs be unarchived automatically when iterated? (Deferred)
3. Should we also store `originalPrompt` for file-uploaded pipelines by letting the user add a description in the Upload modal? (Deferred — scope-creep for now)
