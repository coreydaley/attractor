# Sprint 002: Pipeline Iteration

## Overview

The dashboard currently supports three related but separate flows: Create (natural language to DOT), Re-run (same run ID and same DOT), and Resume (continue paused execution). What is missing is the iteration loop from an existing run: reuse its DOT, modify it via natural language or direct edit, and run the result as a new run.

This sprint adds that loop without introducing new backend subsystems. The key enabler already exists: each `PipelineEntry` persists `dotSource`, and the browser already has `pipelines[id].dotSource` from `/api/pipelines` snapshots and SSE updates. The sprint therefore focuses on wiring: a monitor action (`Iterate`), create-mode state in the client, and a server streaming endpoint that modifies DOT using both base DOT and change request.

Scope remains intentionally narrow. We are not adding prompt history, version storage, or pipeline lineage in the DB. The original run remains immutable; iteration always creates a new run via existing `/api/upload` + `PipelineRunner.submit(...)` path.

## Use Cases

1. **Iterate after failure**: User opens a failed run, clicks Iterate, describes a fix (for example "add a validation stage before deploy"), clicks Modify, and runs the revised DOT.
2. **Fork a completed run**: User takes a successful run and creates a variant without changing the original run record.
3. **Direct DOT editing**: User clicks Iterate, edits DOT directly (no LLM call), and runs immediately.
4. **Maintain execution history**: Original run stays untouched; a new tab appears for the iterated run.

## Architecture

Current monitor/create behavior:

```text
Monitor tab (selectedId)
  -> action buttons include Re-run/Archive/Delete
  -> Re-run calls /api/rerun (same pipeline id/state reset)

Create view
  -> nlInput debounced -> /api/generate/stream -> dotPreview
  -> runGenerated() -> /api/upload -> PipelineRunner.submit(new id)
```

Target iteration behavior:

```text
Monitor tab (terminal run)
  -> Iterate button
  -> enterIterateMode(runId)
      - showView('create')
      - nlInput placeholder switches to "Describe modifications..."
      - dotPreview preloaded from pipelines[runId].dotSource
      - runBtn enabled

Modify path
  -> Modify button (create view)
  -> /api/iterate/stream { baseDot, changes }
  -> streamed deltas -> dotPreview
  -> done.dotSource -> renderGraph()

Run path
  -> runGenerated() unchanged transport (/api/upload)
  -> creates NEW run id and monitor tab
  -> exitIterateMode() resets create defaults
```

Server-side addition mirrors existing streaming pattern:

```text
POST /api/iterate/stream
  input:  { baseDot, changes }
  output: SSE lines data: {delta} ... data: {done:true, dotSource}
  impl:   DotGenerator.iterateStream(baseDot, changes, onDelta)
```

## Implementation Plan

### Phase 1: DOT Iteration Primitive + API (~35%)

**Files:**
- `src/main/kotlin/attractor/web/DotGenerator.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `iterateStream(baseDot: String, changes: String, onDelta: (String) -> Unit): String` to `DotGenerator`.
- [ ] Reuse current provider selection and fence-stripping behavior (`selectModel()`, `extractDotSource(...)`).
- [ ] Build a focused iterate prompt that includes base DOT and explicit modification instructions.
- [ ] Add `POST /api/iterate/stream` endpoint in `WebMonitorServer` with same SSE contract/error handling style as `/api/generate/stream` and `/api/fix-dot`.
- [ ] Validate required fields: reject empty `baseDot` or empty `changes` with 400 JSON errors.

### Phase 2: Monitor Iterate Entry Point (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] Add `Iterate` action button in monitor panel scaffold (`buildPanel`) near Re-run.
- [ ] Show `Iterate` only for terminal non-archived runs (`completed`, `failed`, `cancelled`) in `updatePanel`.
- [ ] Implement `iteratePipeline()` function:
- [ ] Validate selected pipeline and `dotSource` presence.
- [ ] Populate create fields (`dotPreview`, placeholder/status) and render preview graph.
- [ ] Preserve existing simulate/auto-approve checkbox behavior.
- [ ] Navigate to Create view and keep selected monitor tab unchanged until new run is submitted.

### Phase 3: Create Iterate Mode + Modify UX (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] Add a create-view `Modify` button (minimal CSS reuse from existing button classes).
- [ ] Add client iterate-mode state (`iterateSourceId` + helper methods `enterIterateMode(id)` / `exitIterateMode()`).
- [ ] In iterate mode, `Modify` should call `/api/iterate/stream` with current DOT as `baseDot` plus `nlInput` as `changes`.
- [ ] Stream deltas into `dotPreview`, finalize with returned `dotSource`, set status, enable Run, and re-render graph.
- [ ] Keep existing debounced auto-generate flow for normal create mode; disable/short-circuit it while iterate mode is active.
- [ ] On successful `runGenerated()`, call `exitIterateMode()` before switching back to monitor.
- [ ] On `resetCreatePage()`, reset iterate mode and restore original placeholder/status text.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Add iterative DOT transformation primitive (`iterateStream`) |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `/api/iterate/stream`, Iterate action, create iterate mode, and Modify streaming UX |
| `docs/sprints/drafts/SPRINT-002-CODEX-DRAFT.md` | Create | Codex sprint plan draft |

## Definition of Done

- [ ] Terminal run detail panel shows an `Iterate` button.
- [ ] Clicking `Iterate` opens Create view with selected run DOT preloaded.
- [ ] Create description field communicates modification intent (placeholder/status reflect iterate mode).
- [ ] Clicking `Modify` streams revised DOT produced from `(baseDot + changes)`.
- [ ] User can skip Modify and directly edit DOT before running.
- [ ] Clicking `Run Pipeline` creates a new run/tab; source run remains unchanged.
- [ ] Existing Generate, Fix DOT, Re-run, Resume, Archive flows still work.
- [ ] Build succeeds with Java 21 + Gradle wrapper.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Create auto-generate conflicts with iterate mode and overwrites preloaded DOT | Medium | Medium | Gate `nlInput` debounce path behind `!iterateSourceId` |
| Empty/legacy `dotSource` for some runs | Low | Low | Hide or disable Iterate when `dotSource` is blank; show user-facing error message |
| Iteration prompt produces malformed DOT | Medium | Medium | Reuse existing render + auto-fix flow (`/api/fix-dot`) after modify completion |
| UX confusion between Re-run and Iterate semantics | Low | Medium | Keep labels explicit: Re-run (same run), Iterate (new variant) |

## Security Considerations

- No new persistence or external network surface beyond one additional local API endpoint.
- Continue escaping streamed output through existing JSON serialization helper (`js(...)`).
- Preserve current CORS and same-origin dashboard behavior.

## Dependencies

- Depends on Sprint 001 realtime foundation for responsive create/monitor updates.
- No new Gradle dependencies.
- Runtime remains Java 21.

## Open Questions

1. Should Iterate include `paused` runs, or remain terminal-only for clearer semantics?
2. In iterate mode, should auto-generate be fully disabled, or should it switch to modify semantics after debounce?
3. Should archived runs expose Iterate in archived list actions, or only via monitor tab view?
