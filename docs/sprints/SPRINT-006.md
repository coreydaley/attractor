# Sprint 006: Pipeline History Navigation

## Overview

Sprint 005 made runs immutable and linked by `pipeline_family_id`, giving each pipeline a complete version lineage. The Version History accordion lists all family members on every panel. But the accordion is passive: you can see a list of past versions but you cannot navigate to them. Clicking a card opens an artifact modal or enters Restore mode — there is no way to open a previous run as a full interactive panel.

This sprint makes history navigation first-class. Every version card gains a **[View]** button that opens that run as a full panel — stages, logs, graph, artifact modal, all the same UI as a live run. Runs that are not in the current session (e.g., after a server restart) are loaded on-demand from SQLite via a new `GET /api/pipeline-view` endpoint and hydrated into the in-memory registry before the panel renders. A compact **version navigator strip** below the panel title shows which version you're on and provides prev/next arrows to step through the family chronologically.

Because on-demand loaded runs are retrieved from a completed historical state, mutating controls (Re-run, Pause, Resume, Cancel, Archive, Delete) are hidden for view-only hydrated entries. Export, Artifacts, and Restore remain available — these are read-only or create-new operations.

## Use Cases

1. **Navigate to any historical version**: Pipeline has been iterated four times. User opens Monitor, selects the latest tab, expands Version History. Clicks [View] on v2. Panel switches instantly to v2 — its stages, logs, graph, and DOT source fully accessible.

2. **Cycle through versions with the navigator**: User is on v3 of 4. Sees "v3 of 4" in the navigator strip. Clicks ‹ prev → jumps to v2. Clicks › next → returns to v3. Fast comparison without touching the accordion.

3. **View history after a server restart**: Server restarted; only the latest pipeline tab is present. User expands Version History — all 4 family members listed (from `/api/pipeline-family`). Clicks [View] on v1. Client calls `/api/pipeline-view?id=run-v1`; server hydrates from DB; run appears in tab bar and panel renders normally.

4. **Access artifacts of any version**: User views v1 via [View]. From the full panel, clicks [Download Artifacts] or [Export] — same flows as any other run. Or clicks [Artifacts] on the version card directly.

5. **Restore and iterate from history**: User views v2, decides it was better. Clicks [Restore] in the version card — enters Iterate mode pre-filled with v2's DOT and prompt. Creates v5 in the same family.

6. **Standalone run (no family)**: Pipeline has never been iterated. Version History hidden (Sprint 005 behavior). Navigator strip also hidden. No regression.

## Architecture

```text
New endpoint
═══════════════════════════════════════════════════════════
GET /api/pipeline-view?id={runId}
  → registry.getOrHydrate(id):
      If run already in registry → return in-memory entry
      Else: store.getById(id) → null? 404
            hydrateEntry(storedRun), mark isHydratedViewOnly=true
            addToRegistry(entry)
  → return single-pipeline JSON object (same shape as allPipelinesJson() entries)
    includes "isHydratedViewOnly": true


PipelineEntry (extended)
═══════════════════════════════════════════════════════════
PipelineEntry
  + isHydratedViewOnly: Boolean = false   (true only for on-demand DB loads)


Client flow: [View] or navigator
═══════════════════════════════════════════════════════════
function selectOrHydrateRun(runId) {
  if (pipelines[runId]) {
    selectTab(runId);
    maybeAutoExpandVH(runId);
    return;
  }
  fetch('/api/pipeline-view?id=' + encodeURIComponent(runId))
    .then(r => r.json())
    .then(data => {
      if (data.error) { showViewError(data.error); return; }
      // Merge into client state
      applyPipelineEntry(data);    // inserts into pipelines{}, orderedIds
      renderTabs();
      selectTab(runId);
      maybeAutoExpandVH(runId);
    })
    .catch(() => showViewError('Failed to load run'));
}

function maybeAutoExpandVH(runId) {
  vhExpanded = true;
  if (vhData) renderVersionHistory(runId, vhData);
}


Version Navigator (panel header)
═══════════════════════════════════════════════════════════
#versionNav (rendered in buildPanel, after panel-header, before pipeline-meta)
  ‹ prev  |  v2 of 4  |  next ›
  (hidden when family has < 2 members or no familyId)

vhMembersById[runId] = { versionNum, totalVersions, prevId, nextId }
  Built by loadVersionHistory() after /api/pipeline-family resolves.

function navVersion(delta) {
  var info = vhMembersById[selectedId]; if (!info) return;
  var target = delta < 0 ? info.prevId : info.nextId; if (!target) return;
  selectOrHydrateRun(target);
}


Read-only gating for on-demand loaded runs
═══════════════════════════════════════════════════════════
pipelines[id].isHydratedViewOnly = true   → set from server JSON

In updatePanel(id):
  var viewOnly = p.isHydratedViewOnly;
  // Hide mutating controls for view-only:
  cancelBtn, pauseBtn, resumeBtn, rerunBtn, iterateBtn → style.display='none' if viewOnly
  archiveBtn, unarchiveBtn, deleteBtn → style.display='none' if viewOnly
  // Keep enabled regardless: downloadBtn, exportBtn, and Restore in version cards
```

### Data Flow: View on an in-memory run

```
[View] clicked for runId already in pipelines{}
  → selectOrHydrateRun(runId)
  → pipelines[runId] exists → selectTab(runId) immediately
  → maybeAutoExpandVH(runId) → expand accordion
```

### Data Flow: View on an out-of-memory run (after restart)

```
[View] clicked for runId NOT in pipelines{}
  → selectOrHydrateRun(runId)
  → fetch GET /api/pipeline-view?id=runId
      Server: registry.getOrHydrate(runId)
              store.getById(runId) → StoredRun
              hydrateEntry(storedRun) → PipelineEntry(isHydratedViewOnly=true)
              addToRegistry(entry)
              return JSON for this single entry
  → applyPipelineEntry(data) → pipelines[runId] now exists
  → renderTabs() → tab appears
  → selectTab(runId) → panel renders
  → loadVersionHistory(runId) → fetches family, builds vhMembersById
  → updateVersionNav(runId) → navigator shows "v1 of 4"
  → maybeAutoExpandVH(runId) → accordion expands
```

## Implementation Plan

### Phase 1: PipelineRegistry — `isHydratedViewOnly` + `getOrHydrate()` (~20%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Add `var isHydratedViewOnly: Boolean = false` to `PipelineEntry` data class
- [ ] Add `fun getOrHydrate(id: String, store: RunStore): PipelineEntry?`:
  - If `entries.containsKey(id)` → return `entries[id]` (already in memory)
  - `store.getById(id)` null → return null
  - `hydrateEntry(storedRun)` → produces a fully hydrated entry (existing private helper)
  - Set `entry.isHydratedViewOnly = true`
  - `synchronized(this)` block: `if (!entries.containsKey(id)) { entries[id] = entry; orderedIds.add(id) }` (idempotent)
  - Return entry
- [ ] `allPipelinesJson()` / `toJson()` already called per entry; make sure `isHydratedViewOnly` is serialized (see Phase 2)

### Phase 2: Backend — `GET /api/pipeline-view` + JSON extension (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `GET /api/pipeline-view?id={runId}`:
  - Blank id → 400 `{"error":"missing id"}`
  - `registry.getOrHydrate(id, store)` null → 404 `{"error":"not found"}`
  - Returns single-entry JSON identical to one element from `allPipelinesJson()`:
    `{"id":...,"fileName":...,"dotSource":...,"originalPrompt":...,"familyId":...,"simulate":...,"isHydratedViewOnly":...,"state":{...}}`
  - Content-Type: `application/json`
- [ ] In `allPipelinesJson()` and the single-entry serializer: add `"isHydratedViewOnly":${entry.isHydratedViewOnly}` per entry
- [ ] In `js()` / helper used by `allPipelinesJson()`: verify boolean serialization is `true`/`false` (Kotlin booleans)

### Phase 3: Frontend — `selectOrHydrateRun()` + [View] button + `applyPipelineEntry()` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS)

**Tasks:**
- [ ] Add `var vhMembersById = {};` alongside existing state vars (cleared in `buildPanel()`)
- [ ] Add `function applyPipelineEntry(entry)`:
  - If `!pipelines[entry.id]`: push `entry.id` to `orderedIds` (or however client maintains order)
  - `pipelines[entry.id] = entry`
  - Merges state fields same as `applyUpdate()` does for each element
- [ ] Add `function maybeAutoExpandVH(runId)`:
  - `vhExpanded = true`
  - If `vhData` is not null: `renderVersionHistory(runId, vhData)`
- [ ] Add `function showViewError(msg)`:
  - Sets text of a small `#viewError` span (near the version accordion header) to `msg`, fades it out after 3 seconds
  - Must be created as a `<span id="viewError" class="view-err"></span>` in `buildPanel()` alongside `#versionHistory`
- [ ] Add `function selectOrHydrateRun(runId)`:
  ```javascript
  function selectOrHydrateRun(runId) {
    if (pipelines[runId]) {
      selectTab(runId);
      maybeAutoExpandVH(runId);
      return;
    }
    fetch('/api/pipeline-view?id=' + encodeURIComponent(runId))
      .then(function(r) { return r.json(); })
      .then(function(data) {
        if (data.error) { showViewError('Load failed: ' + data.error); return; }
        applyPipelineEntry(data);
        renderTabs();
        selectTab(runId);
        maybeAutoExpandVH(runId);
      })
      .catch(function() { showViewError('Network error loading run'); });
  }
  ```
- [ ] In `renderVersionHistory()`: add [View] button as leftmost action in `.vh-actions` per card:
  ```javascript
  + '<button class="btn-vh" onclick="selectOrHydrateRun(' + JSON.stringify(m.id) + ')">[View]</button>'
  ```
- [ ] Clear `vhMembersById = {}` in `buildPanel()` (alongside existing `vhData = null` reset)

### Phase 4: Frontend — `updatePanel()` read-only gating (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS)

**Tasks:**
- [ ] In `updatePanel(id)`:
  - `var viewOnly = !!(p.isHydratedViewOnly);`
  - After computing button visibility for each button, add override: `if (viewOnly) { btn.style.display = 'none'; }` for:
    - `cancelBtn`, `pauseBtn`, `resumeBtn`, `rerunBtn`, `iterateBtn`
    - `archiveBtn`, `unarchiveBtn`, `deleteBtn`
  - Leave `downloadBtn` and `exportBtn` unaffected (read-only operations)
- [ ] Note: `[Restore]` button lives in version history cards, not in action bar — unaffected; always shown

### Phase 5: Frontend — version navigator strip + CSS (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded CSS/HTML/JS)

**CSS additions:**
- [ ] `.version-nav` — `display:flex; align-items:center; gap:8px; padding:4px 0 8px 0; margin-bottom:4px;`
- [ ] `.vn-arrow` — `background:none; border:1px solid var(--border); border-radius:4px; color:var(--text-muted); font-size:1rem; padding:1px 8px; cursor:pointer; line-height:1;`
- [ ] `.vn-arrow:hover:not(:disabled)` — `border-color:var(--accent); color:var(--text);`
- [ ] `.vn-arrow:disabled` — `opacity:0.3; cursor:default;`
- [ ] `.vn-label` — `font-size:0.8rem; color:var(--text-muted); font-family:monospace;`
- [ ] `.view-err` — `font-size:0.75rem; color:var(--danger,#f85149); margin-left:8px; opacity:1; transition:opacity 0.3s;`

**HTML in `buildPanel()`:**
- [ ] After panel-header div, before pipeline-meta div:
  ```javascript
  + '<div class="version-nav" id="versionNav" style="display:none;">'
  +   '<button class="vn-arrow" id="vnPrev" onclick="navVersion(-1)">&#8249;</button>'
  +   '<span class="vn-label" id="vnLabel"></span>'
  +   '<button class="vn-arrow" id="vnNext" onclick="navVersion(+1)">&#8250;</button>'
  + '</div>'
  ```

**JS:**
- [ ] In `loadVersionHistory()` success handler, after setting `vhData`: build `vhMembersById`:
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
  updateVersionNav(runId);
  ```
- [ ] Add `function updateVersionNav(runId)`:
  ```javascript
  function updateVersionNav(runId) {
    var el = document.getElementById('versionNav');
    if (!el) return;
    var info = vhMembersById[runId];
    if (!info || info.totalVersions < 2) { el.style.display = 'none'; return; }
    el.style.display = '';
    document.getElementById('vnLabel').textContent = 'v' + info.versionNum + ' of ' + info.totalVersions;
    document.getElementById('vnPrev').disabled = !info.prevId;
    document.getElementById('vnNext').disabled = !info.nextId;
  }
  ```
- [ ] Add `function navVersion(delta)`:
  ```javascript
  function navVersion(delta) {
    var info = vhMembersById[selectedId];
    if (!info) return;
    var targetId = delta < 0 ? info.prevId : info.nextId;
    if (!targetId) return;
    selectOrHydrateRun(targetId);
  }
  ```
- [ ] In `updatePanel(id)`: if `vhMembersById[id]` exists, call `updateVersionNav(id)` (handles late-loading navigator state)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add `isHydratedViewOnly` to `PipelineEntry`; add `getOrHydrate(id, store)` public method |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/pipeline-view` endpoint; `isHydratedViewOnly` in JSON; `selectOrHydrateRun()`, `applyPipelineEntry()`, `updateVersionNav()`, `navVersion()`, `maybeAutoExpandVH()`; [View] button in version cards; navigator CSS/HTML; view-only action gating in `updatePanel()` |

Only two files change. No new Gradle dependencies.

## Definition of Done

- [ ] `GET /api/pipeline-view?id=X` returns single-entry pipeline JSON for known runs; 404 for unknown
- [ ] `isHydratedViewOnly: true` is included in the response for on-demand loaded entries
- [ ] `isHydratedViewOnly: false` is included in all `allPipelinesJson()` entries (consistency)
- [ ] `PipelineRegistry.getOrHydrate()` is idempotent — calling twice with same id does not duplicate `orderedIds`
- [ ] [View] button appears as leftmost action on every version history card (before [Artifacts] and [Restore])
- [ ] Clicking [View] on an in-memory run navigates to its tab immediately (no server call)
- [ ] Clicking [View] on an out-of-memory run: tab appears, panel renders with correct stages/logs/DOT/graph
- [ ] Navigating to a version via [View] or the navigator auto-expands the Version History accordion
- [ ] Version navigator strip visible on any run that belongs to a family with 2+ members
- [ ] Navigator shows `v{N} of {total}` correctly for the current run
- [ ] ‹ prev disabled on v1; › next disabled on the latest version (hard-stop, no wrap)
- [ ] Navigator is hidden for standalone runs (single-member family or blank familyId)
- [ ] View-only hydrated runs hide: Cancel, Pause, Resume, Re-run, Iterate, Archive, Unarchive, Delete
- [ ] View-only hydrated runs keep: Export, Download Artifacts (and Restore in version cards)
- [ ] Error in `selectOrHydrateRun()` shows inline message near version history, not `alert()`
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: Dashboard, Archive, Delete, Pause, Resume, Export/Import, Generate, Re-run, Iterate, Artifact modal, Restore, Version History accordion flows

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `getOrHydrate()` race: two concurrent [View] clicks for same non-cached run | Low | Low | `synchronized(this)` + `containsKey()` guard makes insertion idempotent |
| `orderedIds` client-side ordering of on-demand loaded entry | Low | Low | `applyPipelineEntry()` appends to end of orderedIds; sufficient for tab bar appearance |
| `vhMembersById` not populated when `navVersion()` called (race) | Very Low | Low | Guard: `if (!info) return;` at top of `navVersion()` |
| Hydrated entry's `logsRoot` doesn't exist on this machine (imported run) | Low | Medium | Artifact listing already returns empty list gracefully; no change needed |
| `isHydratedViewOnly` serialized as Kotlin Boolean → JSON `true`/`false` | Very Low | Low | Kotlin booleans serialize correctly; verify in Phase 2 |
| View-only gating on `iterateBtn` prevents "Restore flow" | Low | Medium | Restore uses `restoreVersion()` in version history cards, not the panel `iterateBtn` — two different code paths; gating iterateBtn doesn't break Restore |
| Large families (many members) slow `loadVersionHistory()` | Very Low | Low | Already capped at 100 members by `/api/pipeline-family` (Sprint 005) |

## Security Considerations

- `/api/pipeline-view` reads only from `RunStore.getById()` — parameterized SQL, no injection surface
- `isHydratedViewOnly` is a server-assigned boolean; never derived from user input
- Client-side action gating is a UI convenience only — mutating endpoints (Re-run, Archive, Delete) already validate state server-side
- No new file-serving surface; existing path traversal protections in `/api/run-artifact-file` unchanged
- `pipeline-view` exposes same data already visible in the Monitor; no new data exposure

## Dependencies

- Sprint 005 (completed) — `pipeline_family_id`, `PipelineRegistry.hydrateEntry()`, `/api/pipeline-family`, Version History accordion HTML/JS, `vhData`, `vhExpanded`
- Sprint 003 (completed) — `RunStore.getById()` used by `getOrHydrate()`
- Sprint 004 (completed) — Dashboard tab; `selectTab()` pattern that `selectOrHydrateRun()` delegates to

## Open Questions

1. Should [View]-loaded runs show a subtle visual indicator in the tab bar (e.g. a clock icon or "view-only" tooltip)? — deferred; not blocking.
2. Should the Dashboard card for a pipeline show a `(v3)` version count badge when a family has 3+ runs? — deferred to a future sprint.
3. Should `/api/pipeline-view` trigger `broadcastUpdate()` so other browser windows see the on-demand loaded run? — Low priority; single-user tool for now.
