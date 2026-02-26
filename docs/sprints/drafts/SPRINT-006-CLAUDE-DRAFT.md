# Sprint 006: Pipeline History Navigation

## Overview

Sprint 005 laid the groundwork for pipeline version history: runs are linked by `pipeline_family_id`, a Version History accordion lists all family members on each panel, and an Artifact modal lets you browse files. But Sprint 005 stopped short of full navigation — clicking a version card can only open artifacts or enter Restore mode. There is no way to actually **visit** a past version as a live panel, and there is no indication of which version you're currently viewing within a family.

This sprint closes that gap with three tightly integrated features:

1. **[View] button on version history cards** — clicking opens that run as a full interactive panel (stages, logs, graph, artifact modal). If the run is not in the current session (e.g. after a server restart), it is loaded on-demand from SQLite via a new `/api/load-run` endpoint and hydrated into the in-memory registry before the panel is rendered.

2. **Version navigator in each panel** — a compact breadcrumb strip shows "v2 of 3 ›" below the panel title for any run that belongs to a multi-member family, with prev / next arrows to step through the chronological history without opening the accordion.

3. **Ephemeral tab semantics for on-demand loaded runs** — history runs loaded via [View] appear in the tab bar with a subtle visual indicator (dashed border, no close button needed). They persist in the tab bar for the session, identical to normally submitted runs, since they are fully hydrated registry entries.

Together these features make the pipeline tab the canonical place to browse all history: every version is a first-class navigable panel, not just a row in a list.

## Use Cases

1. **Navigate to any historical version**: Pipeline has been iterated 4 times. User opens Monitor, selects the latest tab, expands Version History. Clicks [View] on v2. Panel switches instantly to v2 — its stages, logs, graph, and DOT source fully accessible.

2. **Cycle through versions with the navigator**: User is on v3 of 4. Sees "v3 of 4" in the navigator strip. Clicks ‹ prev arrow → jumps to v2. Clicks › next arrow → returns to v3. Fast comparison without the accordion.

3. **View history after a server restart**: Server restarted; only one pipeline tab is loaded (via SSE/DB). User expands Version History — all 4 family members are listed (from the `/api/pipeline-family` query). Clicks [View] on v1. Client calls `/api/load-run?id=run-v1`; server hydrates it from DB; run appears in tab bar and panel renders normally.

4. **Compare artifacts across versions**: User clicks [Artifacts] on v1 card, views `checkpoint.json`. Closes modal. Clicks [View] on v1. Now the full panel is open. Clicks [Artifacts] from the detail panel's action bar (the existing Download Artifacts/Export buttons). Works identically to any other run.

5. **Standalone run (no family)**: Pipeline has never been iterated (familyId == id, single member). Version History is hidden (Sprint 005 behavior unchanged). Version navigator strip is also hidden. No regression.

## Architecture

```
Client-side state additions
═══════════════════════════════════════════════════════════
var vhMembersById = {};  // keyed by runId: { versionNum, totalVersions, prevId, nextId }
// Populated when loadVersionHistory() resolves; cleared in buildPanel()

Server: New endpoint
═══════════════════════════════════════════════════════════
GET /api/load-run?id={runId}
  → registry.get(id) if present: return {status:"cached"}
  → else: store.getById(id) → null? 404
  → hydrateAndRegister(run):
      entry = registry.hydrateEntry(run)    ← existing private helper
      registry.entries[id] = entry          ← expose via new public method
  → broadcastUpdate()
  → return {status:"loaded", id:run.id}
  (Client then calls selectTab(id) — same flow as normal SSE-discovered run)

Client: [View] button flow
═══════════════════════════════════════════════════════════
function viewHistoryRun(runId) {
  if (pipelines[runId]) {
    selectTab(runId);
    return;
  }
  // Not yet in client state — load from server
  fetch('/api/load-run?id=' + encodeURIComponent(runId))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.error) { alert('Failed to load run: ' + data.error); return; }
      // Server has now hydrated + broadcast; kickPoll() to pick up the new entry
      kickPoll();
      // After poll resolves, select the tab
      // Use a one-shot listener: after next applyUpdate that includes runId, selectTab(runId)
      pendingSelectId = runId;
    });
}

var pendingSelectId = null;   // consumed by applyUpdate() after next poll
// In applyUpdate(data): if (pendingSelectId && data.id === pendingSelectId) {
//   selectTab(pendingSelectId); pendingSelectId = null; }

Version Navigator strip
═══════════════════════════════════════════════════════════
#versionNav (rendered in buildPanel, after panel-header, before pipeline-meta)
  <div class="version-nav" id="versionNav" style="display:none;">
    <button class="vn-arrow" id="vnPrev" onclick="navVersion(-1)">&#8249;</button>
    <span class="vn-label" id="vnLabel">v2 of 4</span>
    <button class="vn-arrow" id="vnNext" onclick="navVersion(+1)">&#8250;</button>
  </div>

function updateVersionNav(runId) {
  var info = vhMembersById[runId];
  var el = document.getElementById('versionNav');
  if (!info || info.totalVersions < 2) { el.style.display = 'none'; return; }
  el.style.display = '';
  document.getElementById('vnLabel').textContent = 'v' + info.versionNum + ' of ' + info.totalVersions;
  document.getElementById('vnPrev').disabled = !info.prevId;
  document.getElementById('vnNext').disabled = !info.nextId;
}

function navVersion(delta) {
  var info = vhMembersById[selectedId];
  if (!info) return;
  var targetId = delta < 0 ? info.prevId : info.nextId;
  if (!targetId) return;
  viewHistoryRun(targetId);
}
```

### Data Flow: [View] on a run already in memory

```
[View] clicked on version card for runId "run-X"
  → viewHistoryRun("run-X")
  → pipelines["run-X"] exists (was SSE-delivered)
  → selectTab("run-X") directly
  → renderTabs(), buildPanel("run-X"), loadVersionHistory("run-X")
  → updateVersionNav("run-X") (uses vhMembersById populated by loadVersionHistory)
```

### Data Flow: [View] on a run NOT in memory (after restart)

```
[View] clicked on version card for runId "run-old"
  → viewHistoryRun("run-old")
  → pipelines["run-old"] does NOT exist
  → GET /api/load-run?id=run-old
      Server: store.getById("run-old") → StoredRun found
              registry.addHydratedEntry(storedRun) ← new public method
              broadcastUpdate()
      Response: {status:"loaded", id:"run-old"}
  → pendingSelectId = "run-old"
  → kickPoll() → GET /api/pipelines → returns run-old in snapshot
  → applyUpdate fires for run-old → pendingSelectId consumed → selectTab("run-old")
  → Panel renders. loadVersionHistory loads family. Navigator shows.
```

### `PipelineRegistry.addHydratedEntry()` (new public method)

```kotlin
fun addHydratedEntry(run: StoredRun) {
    val entry = hydrateEntry(run)   // private helper from Sprint 005
    synchronized(lock) {
        if (!entries.containsKey(run.id)) {
            entries[run.id] = entry
            orderedIds.add(run.id)
        }
        // If already present, no-op (race condition safe)
    }
}
```

This reuses the existing `hydrateEntry()` private helper — a single code path for all hydration, consistent with the Sprint 005 design.

## Implementation Plan

### Phase 1: Backend — `PipelineRegistry.addHydratedEntry()` + `/api/load-run` (~15%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `fun addHydratedEntry(run: StoredRun)` to `PipelineRegistry`: calls `hydrateEntry(run)` (make `hydrateEntry` internal-accessible or keep private and call from within), inserts into `entries` + `orderedIds` if not already present; synchronized
- [ ] Add `GET /api/load-run?id={runId}` in `WebMonitorServer`:
  - `registry.get(id)` present → respond `{"status":"cached","id":id}`
  - `store.getById(id)` null → 404 `{"error":"not found"}`
  - Otherwise: `registry.addHydratedEntry(storedRun)` → `broadcastUpdate()` → `{"status":"loaded","id":id}`
- [ ] No changes to existing endpoints

### Phase 2: Frontend — `vhMembersById` index + [View] button (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS)

**Tasks:**
- [ ] Add `var vhMembersById = {};` and `var pendingSelectId = null;` alongside existing state vars
- [ ] In `loadVersionHistory()` success handler: after setting `vhData`, build `vhMembersById` index:
  ```javascript
  vhMembersById = {};
  var total = data.members.length;
  for (var i = 0; i < total; i++) {
    var m = data.members[i];
    vhMembersById[m.id] = {
      versionNum: i + 1,
      totalVersions: total,
      prevId: i > 0 ? data.members[i-1].id : null,
      nextId: i < total-1 ? data.members[i+1].id : null
    };
  }
  ```
- [ ] Clear `vhMembersById = {}` and `pendingSelectId = null` in `buildPanel()` (alongside existing `vhData = null` reset)
- [ ] Add `function viewHistoryRun(runId)`:
  - If `pipelines[runId]` exists: call `selectTab(runId)` directly; return
  - Else: fetch `/api/load-run?id=...`; on success set `pendingSelectId = runId` and call `kickPoll()`; on error show inline error (not alert)
- [ ] In `applyUpdate(data)`: add after merge logic — `if (pendingSelectId && data.id === pendingSelectId) { var tid = pendingSelectId; pendingSelectId = null; selectTab(tid); }`
- [ ] In `renderVersionHistory()`: add [View] button to each version card in `.vh-actions`:
  ```javascript
  + '<button class="btn-vh" onclick="viewHistoryRun(' + JSON.stringify(m.id) + ')">[View]</button>'
  ```
  Place [View] before [Artifacts] and [Restore] (leftmost, as it's the new primary action)

### Phase 3: Frontend — Version Navigator strip (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**CSS:**
- [ ] `.version-nav` — `display:flex; align-items:center; gap:8px; padding:4px 0 8px 0; margin-bottom:4px;`
- [ ] `.vn-arrow` — `background:none; border:1px solid var(--border); border-radius:4px; color:var(--text-muted); font-size:1rem; padding:1px 8px; cursor:pointer; line-height:1;`
- [ ] `.vn-arrow:hover:not(:disabled)` — `border-color:var(--accent); color:var(--text);`
- [ ] `.vn-arrow:disabled` — `opacity:0.3; cursor:default;`
- [ ] `.vn-label` — `font-size:0.8rem; color:var(--text-muted); font-family:monospace;`

**HTML (in `buildPanel()`):**
- [ ] After panel-header div and before pipeline-meta div, insert:
  ```javascript
  + '<div class="version-nav" id="versionNav" style="display:none;">'
  +   '<button class="vn-arrow" id="vnPrev" onclick="navVersion(-1)">&#8249;</button>'
  +   '<span class="vn-label" id="vnLabel"></span>'
  +   '<button class="vn-arrow" id="vnNext" onclick="navVersion(+1)">&#8250;</button>'
  + '</div>'
  ```

**JS:**
- [ ] Add `function updateVersionNav(runId)`:
  - Gets `vhMembersById[runId]`; if none or `totalVersions < 2` → hide `#versionNav`; return
  - Else: show; set `#vnLabel.textContent = 'v' + info.versionNum + ' of ' + info.totalVersions`
  - `#vnPrev.disabled = !info.prevId`; `#vnNext.disabled = !info.nextId`
- [ ] Add `function navVersion(delta)`:
  - Gets `vhMembersById[selectedId]`; if none return
  - `targetId = delta < 0 ? info.prevId : info.nextId`; if null return
  - Calls `viewHistoryRun(targetId)`
- [ ] In `loadVersionHistory()` success handler: after building `vhMembersById`, call `updateVersionNav(runId)`
- [ ] In `buildPanel(id)`: reset `#versionNav` to `display:none` (already done by innerHTML replacement)
- [ ] In `updatePanel(id)`: if `vhMembersById[id]` exists, call `updateVersionNav(id)` (handles case where panel was open and family loaded late)

### Phase 4: Error handling + edge cases (~15%)

**Tasks:**
- [ ] `viewHistoryRun()` error path: show a small inline error message near the [View] button (e.g., a temporary `<span class="load-err">Failed to load</span>` that disappears after 3 seconds) rather than `alert()`
- [ ] If `/api/load-run` returns `{status:"cached"}` (race: run was delivered via SSE between click and response) — treat same as found in `pipelines{}`: call `selectTab(runId)` immediately
- [ ] `addHydratedEntry()` is idempotent: if run already in registry, no-op (prevents duplicate orderedIds on rapid double-click)
- [ ] `pendingSelectId` timeout guard: if SSE never delivers the run within 5 seconds, clear `pendingSelectId` to prevent stale navigation on future unrelated updates. Use `setTimeout(() => { pendingSelectId = null; }, 5000)` set when storing, cancel on consumption.
- [ ] Loaded-from-DB runs: their status is whatever was persisted (completed/failed/cancelled/paused). The action bar [Re-run], [Iterate], [Export], [Artifacts] buttons should all work correctly — no special-casing needed since the entry is a fully hydrated `PipelineEntry`.
- [ ] `buildPanel()` cleanup: ensure `vhMembersById` reset doesn't break if called before `loadVersionHistory()` has ever resolved (guard with `if (vhMembersById)`)

### Phase 5: `allPipelinesJson()` — ensure loaded runs are broadcast (~15%)

This is largely handled by `broadcastUpdate()` in `/api/load-run`, but:
- [ ] Verify `addHydratedEntry()` run appears in subsequent `GET /api/pipelines` snapshot calls (it will, since it's in `registry.entries`)
- [ ] Verify `allPipelinesJson()` includes all entries including on-demand loaded ones — confirmed, since it iterates `orderedIds` which is updated in `addHydratedEntry()`

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add public `addHydratedEntry(run: StoredRun)` — wraps existing private `hydrateEntry()` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/load-run` endpoint; `viewHistoryRun()` + `pendingSelectId`; [View] button in version cards; version navigator CSS/HTML/JS; `applyUpdate()` pending-select hook |

Only two files change. No new Gradle dependencies.

## Definition of Done

- [ ] `GET /api/load-run?id=X` returns `{status:"loaded"}` or `{status:"cached"}` for known runs; 404 for unknown
- [ ] After `/api/load-run`, `GET /api/pipelines` includes the newly loaded run
- [ ] `PipelineRegistry.addHydratedEntry()` is idempotent (safe to call twice with same run ID)
- [ ] [View] button appears as leftmost action on every version history card (before [Artifacts] and [Restore])
- [ ] Clicking [View] on an in-memory run navigates to its tab immediately (no server call)
- [ ] Clicking [View] on an out-of-memory run (after restart): loads it from DB, tab appears, panel renders with correct stages/logs/DOT
- [ ] Version navigator strip visible on any run that belongs to a family with 2+ members
- [ ] Navigator correctly shows `v{N} of {total}` for the current run
- [ ] Navigator prev/next arrows navigate to the correct adjacent family member
- [ ] ‹ prev arrow is disabled on v1; › next arrow is disabled on the latest version
- [ ] Navigator is hidden for standalone runs (single-member family or blank familyId)
- [ ] `pendingSelectId` is cleared after 5 seconds if SSE never delivers (no stale navigation)
- [ ] Error state in `viewHistoryRun()` shows inline message, not `alert()`
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: Dashboard, Archive, Delete, Pause, Resume, Export/Import, Generate, Re-run, Iterate, Artifact modal, Restore flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `hydrateEntry()` is private in PipelineRegistry — can't call from `addHydratedEntry()` | Low | Medium | Both methods live in the same class; `hydrateEntry()` stays private; `addHydratedEntry()` is a public wrapper calling it directly |
| Rapid [View] clicks load the same run multiple times concurrently | Low | Low | `addHydratedEntry()` is idempotent (no-op if ID already present); `pendingSelectId` only stores one ID at a time |
| SSE latency: `pendingSelectId` set but run not delivered for a long time | Low | Low | 5-second timeout clears `pendingSelectId`; worst case: tab doesn't auto-select, user can click it manually |
| `vhMembersById` not populated when `navVersion()` called (race) | Very Low | Low | Guard: `if (!info) return;` at top of `navVersion()` |
| Loaded-from-DB run has `logsRoot` pointing to a different path than current machine (import scenario) | Low | Medium | Artifact file-serving already gracefully returns empty list if logsRoot missing; no change needed |
| Double-insertion into `orderedIds` if `addHydratedEntry()` races with SSE delivery | Low | Medium | Synchronized block + `containsKey` check prevents double-insertion |

## Security Considerations

- `/api/load-run` only hydrates runs that exist in SQLite (`store.getById()`); cannot be used to inject arbitrary entries
- `run.id` is a server-generated ID (`run-<timestamp>-<counter>`), never directly user-supplied in a dangerous context
- No file serving in this sprint; existing path-traversal protections in `/api/run-artifact-file` unchanged
- `pendingSelectId` is a client-side string holding a run ID; worst case of manipulation is navigating to a different tab

## Dependencies

- Sprint 005 (completed) — `pipeline_family_id`, `PipelineRegistry.hydrateEntry()`, `/api/pipeline-family`, Version History accordion HTML/JS
- Sprint 003 (completed) — `RunStore.getById()` used by `/api/load-run`
- Sprint 004 (completed) — Dashboard tab; `DASHBOARD_TAB_ID` sentinel pattern

## Open Questions

1. Should [View]-loaded historical runs be marked visually differently in the tab bar (e.g. a small clock icon or dashed border)? — deferred; keep uniform appearance for simplicity.
2. Should navigating to a version via the navigator auto-expand the Version History accordion? — leaning yes, as a nice convenience; worth asking.
3. Should the version navigator strip be collapsible or is the compact 3-element strip (‹ v2 of 4 ›) compact enough to always show? — almost certainly always-show is fine given its small footprint.
4. Should the full-snapshot `/api/pipelines` poll endpoint cap the number of entries returned (to avoid performance issues as on-demand loads accumulate)? — deferred; not urgent at current scale.
