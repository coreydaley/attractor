# Sprint 002: Pipeline Iteration

## Overview

Attractor's inner development loop today has a gap: you can run a pipeline, watch it execute in real time, see what succeeded or failed — but if you want to tweak the pipeline and run it again, you must leave the web UI, edit the `.dot` file in a text editor, and re-upload. There is no in-browser path from "this run didn't do what I wanted" to "I want to adjust the pipeline and try again."

This sprint adds a dedicated **Iterate** flow. When a pipeline run reaches a terminal state (completed, failed, or cancelled), an "Iterate" button appears next to Re-run and Archive. Clicking it navigates to the Create view with the run's existing DOT source pre-populated — so the user has a live-editable starting point. If they want to change the pipeline by describing modifications in natural language, a new "Modify" button calls a new `/api/iterate/stream` endpoint that sends both the base DOT and the change description to the LLM, streaming back a revised pipeline. If they prefer to edit the DOT directly, they can do that too, then click Run.

The result is a tight observe → modify → re-run loop, entirely within the browser. The original run is preserved unchanged; the iterated pipeline launches as a fresh tab.

## Use Cases

1. **Add a step after failure**: A pipeline fails partway through. User clicks Iterate, types "Add a validation step before the exit node", clicks Modify, and runs the revised pipeline.
2. **Tweak prompts**: A pipeline completes but the outputs aren't quite right. User iterates, directly edits the `prompt` attributes in the DOT textarea, and reruns with better instructions.
3. **Fork a successful run**: A pipeline worked well and the user wants a variant with an extra human review gate. Iterate pre-fills the DOT; the user describes the change; Modify + Run creates a parallel variant without touching the original.
4. **Quick reformat**: User uploads a hand-written `.dot`, runs it, realises the structure needs adjustment. Iterate lets them use the LLM to restructure without going back to the file system.

## Architecture

### Flow Diagram

```
Monitor View (terminal-state run)
    │
    │ click "Iterate"
    ▼
Create View
    ├── DOT textarea:      pre-filled with pipelines[id].dotSource
    ├── Description field: empty, placeholder "Describe modifications…"
    └── Buttons row:
        ├── [Modify]  — calls /api/iterate/stream {baseDot, changes}
        │                 streams back modified DOT → replaces dotPreview
        │                 triggers renderGraph() on completion
        └── [Run Pipeline] — existing runGenerated() unchanged
```

### New Endpoint

```
POST /api/iterate/stream
Request body:  { "baseDot": "<DOT source>", "changes": "<natural language change request>" }
Response:      text/event-stream
  data: {"delta":"..."}         ← streaming text chunks
  data: {"done":true,"dotSource":"..."}  ← final cleaned DOT
  data: {"error":"..."}         ← on failure
```

This mirrors `/api/generate/stream` exactly; the only difference is the prompt construction inside `DotGenerator.iterateStream()`.

### New DotGenerator Method

```kotlin
fun iterateStream(baseDot: String, changes: String, onDelta: (String) -> Unit): String {
    val prompt = """
Given the following existing Attractor pipeline DOT source:

$baseDot

Modify it according to these instructions: $changes

Output ONLY the modified raw DOT source — no markdown fences, no explanations.
Keep all existing nodes and edges unless the instructions explicitly say to remove them.
""".trimIndent()
    return generateStream(prompt, onDelta)
}
```

### Client-Side State

The Create view gains a `iterateSourceId` variable. When set to a pipeline ID, the view is in "iterate mode":
- DOT textarea is pre-filled with the existing DOT
- Description placeholder changes to "Describe modifications to make…"
- A "Modify" button appears (replaces or supplements the auto-generate-on-type behavior)
- Leaving the Create view clears `iterateSourceId`

### Component Interaction (after this sprint)

```
Monitor View
  [Iterate btn] ──onclick──► showView('create') + enterIterateMode(id)
                                    │
                                    ├── dotPreview.value = pipelines[id].dotSource
                                    └── iterateSourceId = id

Create View
  [Modify btn] ──onclick──► callIterateStream(baseDot, changes)
                                    │
                                    └── POST /api/iterate/stream
                                              │
                                        DotGenerator.iterateStream()
                                              │
                                        streams modified DOT back
                                              │
                                        dotPreview.value = result
                                        renderGraph()

  [Run Pipeline] ──onclick──► runGenerated()  (unchanged)
                                    │
                                    └── POST /api/upload
                                              │
                                        PipelineRunner.submit()
                                              │
                                        new run tab in Monitor
```

## Implementation Plan

### Phase 1: Backend — `DotGenerator.iterateStream()` + `/api/iterate/stream` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/DotGenerator.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `fun iterateStream(baseDot: String, changes: String, onDelta: (String) -> Unit): String` to `DotGenerator`
  - Constructs a prompt from `baseDot` + `changes` then delegates to `generateStream()`
  - Validates API key presence (same guard as other methods)
- [ ] Add `/api/iterate/stream` HTTP context to `WebMonitorServer` (modelled on `/api/generate/stream`)
  - Reads `{ "baseDot": "...", "changes": "..." }` from request body
  - Returns `text/event-stream` SSE; `X-Accel-Buffering: no`
  - Calls `DotGenerator.iterateStream(baseDot, changes) { delta -> writeSSEDelta(...) }`
  - On completion: sends `{"done":true,"dotSource":"..."}` event
  - On error: sends `{"error":"..."}` event

### Phase 2: Frontend — Iterate button in Monitor panel (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS in `buildPanel`, `updatePanel`)

**Tasks:**
- [ ] Add `.btn-iterate` CSS class (styled like `.btn-rerun` but distinct colour — e.g. purple `#6e40c9`)
- [ ] In `buildPanel()`: add `<button class="btn-iterate" id="iterateBtn" style="display:none;" onclick="iteratePipeline()">✎ Iterate</button>` after the Rerun button
- [ ] In `updatePanel()`: show `iterateBtn` when status is `completed | failed | cancelled`
- [ ] Add JS `iteratePipeline()` function:
  ```javascript
  function iteratePipeline() {
    var id = selectedId;
    if (!id || !pipelines[id]) return;
    var dot = pipelines[id].dotSource;
    if (!dot) return;
    enterIterateMode(id, dot);
    showView('create');
  }
  ```

### Phase 3: Frontend — Create view iterate mode (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (HTML/JS in `dashboardHtml`)

**Tasks:**
- [ ] Add `var iterateSourceId = null;` at top of `<script>` block
- [ ] Add `function enterIterateMode(id, dot)`:
  - Sets `iterateSourceId = id`
  - Pre-fills `dotPreview.value = dot`
  - Clears `nlInput.value`
  - Calls `renderGraph()` to show the pre-filled DOT as a preview
  - Shows `modifyBtn`; updates `nlInput` placeholder to "Describe modifications to make to the existing pipeline…"
  - Enables `runBtn`
- [ ] Add `function exitIterateMode()`:
  - Sets `iterateSourceId = null`
  - Restores `nlInput` placeholder to original
  - Hides `modifyBtn`
- [ ] Add "Modify" button to Create view HTML (next to Run Pipeline button), initially hidden:
  ```html
  <button class="btn-modify" id="modifyBtn" style="display:none;" onclick="modifyDot()">Modify Pipeline</button>
  ```
- [ ] Add `.btn-modify` CSS (similar to `.run-btn` styling)
- [ ] Add JS `modifyDot()` function:
  - Reads `baseDot` from `dotPreview.value`
  - Reads `changes` from `nlInput.value`
  - If `changes` is empty: set error status and return
  - Calls `/api/iterate/stream` (same SSE streaming pattern as `generateDot()`)
  - On delta: appends to `dotPreview.value` (stream-replace, clearing first)
  - On done: calls `renderGraph()`
- [ ] In `showView('create')`: call `exitIterateMode()` when transitioning away from Create (to keep state clean)
- [ ] Disable auto-generate-on-type (`nlInput` input handler) while `iterateSourceId != null` to prevent accidental clobber of pre-filled DOT

### Phase 4: Edge cases + polish (~10%)

- [ ] If `pipelines[id].dotSource` is empty (very old run loaded from DB before dotSource was stored), disable Iterate button with `title="No DOT source available for this run"`
- [ ] When `modifyDot()` streaming is in progress: disable `modifyBtn` and `runBtn`; set a "Modifying…" status in `genStatus`
- [ ] Handle SSE error event from `/api/iterate/stream`: show error in `genStatus`, re-enable buttons
- [ ] When a new pipeline is submitted via `runGenerated()` after iterating: call `exitIterateMode()` and switch to Monitor view (same UX as normal Create → Run flow)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Add `iterateStream(baseDot, changes, onDelta)` method |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/iterate/stream` endpoint; Iterate button; iterate-mode JS |

Only 2 files change. No new Gradle dependencies. No DB schema changes.

## Definition of Done

- [ ] `DotGenerator.iterateStream()` compiles and calls `generateStream()` with a correctly-formed prompt
- [ ] `POST /api/iterate/stream` streams back modified DOT and handles errors
- [ ] "Iterate" button appears in Monitor panel for completed/failed/cancelled runs
- [ ] "Iterate" button is hidden for running/paused/idle runs
- [ ] Clicking "Iterate" switches to Create view with DOT pre-filled and `modifyBtn` visible
- [ ] Typing modification instructions and clicking "Modify" calls the LLM and streams back a revised DOT
- [ ] Directly editing the DOT textarea and clicking "Run Pipeline" creates a new run
- [ ] The new run appears as a new tab in Monitor; the original run is unchanged
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions in: Create → Generate → Run, Upload → Run, Re-run, Resume, Pause/Cancel

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `iterateStream()` prompt length exceeds model context limit for very large DOT files | Low | Medium | Model max_tokens=8192 is sufficient for any realistic pipeline; very large DOTs are unlikely |
| Auto-generate-on-type fires while iterate mode is active, clobbering pre-filled DOT | Medium | High | Disable `nlInput` oninput handler when `iterateSourceId != null`; only enable "Modify" button path |
| Old runs in DB have `dotSource=""` (no DOT stored) | Low | Low | Disable Iterate button with tooltip; don't block other actions |
| User clicks Iterate but then navigates back without running — state pollution | Low | Low | `exitIterateMode()` is called in `showView()` transitions |
| LLM returns invalid DOT after modification | Low | Medium | Same `fixStream` auto-fix path as existing generate flow handles this |

## Security Considerations

- No new network surface beyond what `/api/generate/stream` already exposes
- `baseDot` from the client is passed directly to the LLM (same trust model as `prompt` in generate)
- No file system writes; DOT source lives only in memory and browser until user clicks Run

## Dependencies

- Sprint 001 (completed) — SSE infrastructure used by `/api/iterate/stream`

## Open Questions

1. Should Iterate be available for **paused** pipelines too? (They have a DOT; a paused run is still in-progress but the user might want to fork it.) Recommendation: no — only terminal states, to avoid confusing paused vs forked semantics.
2. Should the Iterate button be visible on **running** pipelines (disabled/greyed)? Or only appear when terminal? Recommendation: only appear when terminal, to keep the action bar uncluttered.
3. Should we store the original `prompt` in `PipelineEntry` so Iterate can pre-fill the description field for runs that were generated via Create view? This is a nice-to-have but not blocking — leave for a future sprint.
