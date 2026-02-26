# Sprint 006: Pipeline History Navigation

## Overview

Sprint 005 established immutable version history per `pipeline_family_id`, but the UI still treats history as mostly passive metadata. Users can inspect cards and restore into iterate mode, yet they cannot directly open a historical run as the active panel unless that run already exists in the current tab session.

This sprint closes that gap by making history entries first-class navigation targets. Every version card gets a `View` action that opens the selected version as a full panel, even after restart when that run is not currently in memory. The panel also gains a compact version navigator (`Prev` / `Next` + `vN of M`) so users can traverse family members without bouncing back to the accordion.

The feature is explicitly read-only for historical navigation. Viewing a hydrated run must not change rerun/iterate semantics or accidentally trigger execution paths.

## Use Cases

1. **Open historical run from accordion**: User clicks `View` on `v1` while currently on `v3`, and the monitor switches to a full `v1` panel (graph, stages, logs, artifacts).
2. **Restart-safe history navigation**: After server restart, family members not in tab bar can still be opened via `View` and rendered from DB-backed hydration.
3. **Linear family traversal**: User uses `Prev` / `Next` controls in panel header to move across versions in chronological order.
4. **State clarity while navigating**: Panel shows exactly which version is active (`v2 of 4`) and keeps URL/hash/tab selection aligned.
5. **Preserve restore flow**: `Restore` from any historical version still enters iterate mode with that version’s DOT and prompt.

## Architecture

```text
Client history navigation
──────────────────────────────────────────────────────────
Version History card actions:
  [View]     -> selectOrHydrateRun(member.id) -> selectTab(member.id)
  [Artifacts]-> openArtifacts(member.id, ...)
  [Restore]  -> enterIterateMode(member.id, member.dotSource, member.originalPrompt)

Version navigator (in active panel):
  Prev / Next buttons + "vN of M"
  -> same selectOrHydrateRun(targetRunId) path

Server-side hydration path
──────────────────────────────────────────────────────────
GET /api/pipeline-family?id={runId}
  - Existing endpoint from Sprint 005; used as source of ordered members

GET /api/pipeline-view?id={runId}    (NEW)
  - If run already in registry: return existing payload from in-memory entry
  - Else:
      store.getById(runId) -> hydrateEntry(storedRun) -> registry.insertHydrated(entry)
      return same payload shape as normal pipeline snapshot
  - Hydrated entries are marked view-only in response

Registry model extension
──────────────────────────────────────────────────────────
PipelineEntry
  + isHydratedViewOnly: Boolean   (true when loaded on-demand from DB history)

Client state
──────────────────────────────────────────────────────────
pipelines[id] remains canonical panel model
versionFamilies[familyId] caches ordered member ids
selectedId tracks active run tab as today
```

## Implementation Plan

### Phase 1: Registry On-Demand Hydration Path (~25%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `isHydratedViewOnly: Boolean = false` to `PipelineEntry`.
- [ ] Add `fun getOrHydrate(id: String): PipelineEntry?` in `PipelineRegistry`:
  - return in-memory entry when present
  - else load `StoredRun` via `RunStore.getById(id)` and call `hydrateEntry(...)`
  - cache hydrated entry in registry map for normal rendering paths
- [ ] Ensure hydrated entries carry complete read-only display state (DOT, prompt, logs root, stage statuses) needed by existing UI panels.
- [ ] Mark hydrated entries as `isHydratedViewOnly = true` to gate run-control actions in UI.
- [ ] Keep existing startup hydration behavior unchanged.

### Phase 2: Backend View Endpoint + Payload Updates (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `GET /api/pipeline-view?id={runId}`:
  - 400 for blank id
  - 404 when run not found in DB/registry
  - uses `registry.getOrHydrate(id)` to load entry
  - returns pipeline JSON payload equivalent to entries in `allPipelinesJson()`
- [ ] Extend pipeline JSON shape with:
  - `familyId`
  - `isHydratedViewOnly`
- [ ] Ensure `allPipelinesJson()` includes `isHydratedViewOnly` for consistency.
- [ ] Keep artifact endpoints compatible with hydrated entries (`logsRoot` from stored run).
- [ ] Do not add mutation endpoints for historical navigation.

### Phase 3: Frontend View Action + Dynamic Panel Materialization (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS/CSS/HTML)

**Tasks:**
- [ ] Add `View` button to each card rendered by `renderVersionHistory(currentRunId, members)`.
- [ ] Add `async function selectOrHydrateRun(runId)`:
  - if `pipelines[runId]` exists, call `selectTab(runId)`
  - else fetch `/api/pipeline-view?id=...`, merge result into `pipelines`, render tabs, select tab
- [ ] Update `buildPanel(id)` and `updatePanel(id)` to support hydrated runs indistinguishably for display.
- [ ] Disable mutating actions for view-only entries:
  - hide/disable rerun/pause/resume/cancel/archive controls when `isHydratedViewOnly=true`
  - keep read-only actions enabled (`Artifacts`, graph view, logs)
- [ ] Preserve existing `Restore` behavior (allowed from any viewed historical run).
- [ ] Ensure URL/hash/state updates use existing `selectTab` path after hydration.

### Phase 4: Version Navigator UX (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS/CSS/HTML)

**Tasks:**
- [ ] Add a compact navigator strip in panel header when family has `>= 2` members.
- [ ] Display current position as `vN of M`.
- [ ] Add `Prev` and `Next` buttons, disabled at boundaries.
- [ ] Navigation uses cached family ordering from `/api/pipeline-family`.
- [ ] Keep accordion and navigator in sync when active version changes.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add on-demand DB hydration path and view-only marker on hydrated entries |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `/api/pipeline-view`, extend JSON, add View action, navigator, and UI gating for view-only runs |
| `src/main/kotlin/attractor/db/RunStore.kt` | Verify/Modify (if needed) | Reuse `getById()` for hydration; adjust query fields only if gaps found |
| `docs/sprints/drafts/SPRINT-006-CODEX-DRAFT.md` | Create | Sprint 006 planning draft |

## Definition of Done

- [ ] Version history cards include a `View` button for each member.
- [ ] Clicking `View` opens that run as the active panel with stages/logs/graph/artifacts visible.
- [ ] Historical run viewing works after server restart for runs not already in memory.
- [ ] Panel shows version navigator for family runs with `Prev` / `Next` and `vN of M`.
- [ ] Navigator switches versions in chronological order and updates selected tab/state.
- [ ] View-only hydrated runs cannot trigger mutating controls directly from monitor panel.
- [ ] Existing `Restore` flow still works from historical versions.
- [ ] No regressions in Sprint 003/004/005 flows (export/import, dashboard, artifacts, version history).
- [ ] Build passes:
  - `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Hydrated runs diverge from live entry JSON shape | Medium | High | Use a single serializer path for both SSE snapshots and `/api/pipeline-view` responses |
| UI accidentally allows mutations on historical entries | Medium | High | Add explicit `isHydratedViewOnly` checks around mutating controls and handlers |
| Family ordering inconsistencies between accordion and navigator | Low | Medium | Source both from same `/api/pipeline-family` ordered list |
| Memory growth from cached hydrated entries | Medium | Medium | Hydrate on demand; optionally cap/history-evict in follow-up if needed |

## Security Considerations

- Reuse existing artifact path traversal protections unchanged.
- Keep all DB lookups parameterized; no string-built SQL in new endpoints.
- `pipeline-view` exposes only run metadata already available in monitor context.
- View hydration is read-only and does not invoke runner execution paths.

## Dependencies

- Sprint 005: Version history data model and `/api/pipeline-family`.
- Sprint 004: Tab selection/rendering model reused by `View` navigation.
- Sprint 003: Existing persistence and retrieval primitives (`RunStore.getById`).

## Open Questions

1. Should hydrated history tabs remain persistent for the session, or be marked ephemeral and removable on deselect?
2. Should navigator be rendered in panel header only, or duplicated near Version History accordion for long pages?
3. Should `View` auto-expand the Version History accordion to reinforce context, or preserve current expansion state?
