# Sprint 006 Merge Notes

## Claude Draft Strengths
- Detailed CSS/HTML/JS specifications with exact class names, measurements, and code snippets
- `vhMembersById` index built from `/api/pipeline-family` response for O(1) navigator lookups
- Explicit 5-second timeout guard for `pendingSelectId` to prevent stale navigation
- Well-scoped to two files only (PipelineRegistry.kt + WebMonitorServer.kt)
- Clear Phase breakdown with percentage estimates

## Codex Draft Strengths
- `isHydratedViewOnly: Boolean` on `PipelineEntry` — explicit read-only enforcement for on-demand loaded runs
- `getOrHydrate(id)` naming in PipelineRegistry — cleaner than `addHydratedEntry()`
- `/api/pipeline-view` returns full pipeline JSON payload directly, enabling inline client-side merge without broadcast + poll round-trip
- `selectOrHydrateRun(runId)` function — clean async pattern that fetches view data and merges into `pipelines{}` immediately
- Correctly identified read-only constraint from intent doc

## Valid Critiques Accepted

1. **Read-only enforcement**: My draft said "no special-casing needed" for action buttons. Codex correctly identified this violates the intent's constraint. Accept `isHydratedViewOnly` on `PipelineEntry` and gate mutating controls (Re-run, Pause, Resume, Cancel, Archive, Delete) in `updatePanel()`. Allow: Restore, Artifacts, Export.

2. **`pendingSelectId` wired to wrong payload shape**: `applyUpdate(data)` iterates `data.pipelines[]`, not `data.id`. My approach wouldn't fire. Replace with Codex's direct-merge approach: `/api/pipeline-view` returns the pipeline JSON, client merges it into `pipelines{}` and calls `selectTab()` immediately. No broadcast, no poll needed.

3. **`synchronized(lock)` non-existent**: The pseudocode used `synchronized(lock)` but PipelineRegistry has no `lock` field. Use `@Synchronized` or `synchronized(this)` on `addHydratedEntry` — same approach as any other mutating method in that file. Verifiable during implementation.

4. **Terminology**: Drop "ephemeral" — the correct behavior is session-persistent (loaded runs stay in the tab bar until server restarts, identical to normal runs).

5. **Inline errors only**: No `alert()` anywhere in the error paths. Consistent inline error rendering.

## Critiques Rejected

1. **Codex vagueness on CSS/JS detail**: Codex's Phase 3/4 tasks are abstract. Keep Claude's detailed spec (exact class names, CSS values, inline code). This avoids ambiguity during implementation.

2. **Codex's `RunStore.kt` in Files Summary**: Codex added RunStore as a "Verify/Modify (if needed)" entry. `RunStore.getById()` already exists from Sprint 003. Not a new file change; don't add it.

## Interview Refinements Applied

1. **Auto-expand accordion on View/navigator navigation**: Added explicit `vhExpanded = true; renderVersionHistory(...)` call in `selectOrHydrateRun()` and `navVersion()`.

2. **Hard-stop at nav boundaries**: Already in both drafts; explicitly specified with `disabled` attribute and `if (!info.prevId/nextId) return;` guards.

3. **Scope confirmed**: [View] + navigator — both drafts agree on this scope.

## Final Decisions

- **Endpoint name**: `/api/pipeline-view` (Codex's naming, more descriptive than `/api/load-run`)
- **Registry method**: `getOrHydrate(id)` (Codex's naming) — encapsulates DB lookup + hydration + registration
- **Selection flow**: Codex's direct-merge (merge pipeline JSON into `pipelines{}`, then `selectTab()`) — no broadcast/poll needed
- **Read-only gating**: `isHydratedViewOnly` on `PipelineEntry` + client `pipelines[id].isHydratedViewOnly` check in `updatePanel()` hides mutating buttons
- **Allow on view-only**: Export, Artifacts, Restore (via version history) — these are read-only operations
- **Implementation detail**: All CSS/HTML/JS specs from Claude draft retained
