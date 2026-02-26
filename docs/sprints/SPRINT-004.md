# Sprint 004: Dashboard Tab

## Overview

The Monitor view currently treats pipeline tabs as the only navigation model: each run gets a dynamic tab, and reviewing any run requires clicking into its individual tab. When multiple pipelines are in-flight simultaneously, there is no way to see all activity at a glance — users must click through tabs one at a time.

This sprint adds a **static "Dashboard" tab** permanently pinned as the leftmost tab in `#tabBar`. Unlike pipeline-run tabs (which come and go as runs are created), the Dashboard tab always exists. When selected, `#mainContent` renders a summary panel of all non-archived pipeline runs — each in a compact card showing pipeline name, status badge, currently-executing stage name, and (for active runs) a live elapsed time counter. Clicking any card navigates directly to that pipeline's detail tab.

All changes are client-side JavaScript and HTML/CSS embedded in `WebMonitorServer.kt`. No server endpoints are added and no Gradle dependencies change. The implementation extends the existing `selectedId` / `renderMain()` pattern with a `DASHBOARD_TAB_ID` sentinel and a parallel `dashboardTimer`.

## Use Cases

1. **Multi-pipeline monitoring**: User starts three pipelines concurrently. Dashboard shows all three cards with live stage progress and ticking elapsed timers — no tab-switching required.
2. **Default landing view**: User opens the app. Dashboard is shown immediately (rather than a "no selection" placeholder), providing orientation from the first paint.
3. **Historical overview**: User wants to see all recent runs (completed, failed, etc.) alongside active ones. Dashboard shows every non-archived pipeline in one scrollable panel.
4. **Quick navigation**: On the Dashboard, user sees one pipeline in a stuck-looking state. They click its card to jump directly to its full detail view with logs and graph.
5. **Empty-state orientation**: No pipelines have ever been submitted. Dashboard shows a friendly empty state with a prompt to create one.

## Architecture

```
#tabBar (always rendered)
├── [Dashboard tab]        ← static, always leftmost; onclick="selectTab(DASHBOARD_TAB_ID)"
├── [Pipeline run tab 1]   ← dynamic, per run (existing behavior)
└── ...

selectedId = DASHBOARD_TAB_ID
└── renderMain()
    └── renderDashboard()
        ├── collects all non-archived pipelines from pipelines{}
        ├── sorts by status (running/paused first) then by tab order
        ├── renders #mainContent as .dashboard-layout
        │   ├── .dashboard-grid (CSS grid, responsive columns)
        │   │   └── .dash-card  onclick="selectTab(id)"  (one per pipeline)
        │   │       ├── .dash-card-name            pipeline name
        │   │       ├── .dash-card-row
        │   │       │   ├── .badge.badge-{status}  status badge
        │   │       │   └── .dash-elapsed          elapsed (active) or finished time (terminal)
        │   │       └── .dash-stage-row
        │   │           ├── stage icon
        │   │           └── .dash-stage-name       current stage / 'Paused' / 'Waiting…' / 'Done'
        │   └── .dash-empty (if no pipelines at all)
        └── startDashboardTimer()

DASHBOARD_TAB_ID = '__dashboard__'  (constant, used in all HTML/JS string generation)

applyUpdate(data)                   [existing, unchanged]
└── merge into pipelines{}
└── if (isNew && selectedId === DASHBOARD_TAB_ID) selectedId = p.id  ← auto-nav
└── renderTabs()   → always prepends Dashboard tab first
└── renderMain()   → routes to renderDashboard() or buildPanel() based on selectedId

dashboardTimer (setInterval, 1 second when Dashboard is visible)
└── tickDashboardElapsed()
    └── for each .dash-elapsed span: recompute using elapsed(state) helper
        (uses startedAt / finishedAt from pipeline state — same as detail panel)
```

### Sentinel Pattern

`var DASHBOARD_TAB_ID = '__dashboard__';` is declared as a JS variable (not `const` for compatibility with the existing embedded JS style). Every place that generates HTML or compares `selectedId` uses this variable rather than the string literal, eliminating typo risk.

`selectedId === DASHBOARD_TAB_ID` is a strict equality check. `buildPanel()` and `updatePanel()` are never called with this value — `renderMain()` branches before them.

### Tab Bar Always Has a Tab

The existing early-return in `renderTabs()`:
```javascript
if (visibleIds.length === 0) { bar.innerHTML = '<div class="tab-empty">...'; return; }
```
is removed. The Dashboard tab is prepended unconditionally. When no pipeline-run tabs exist, only the Dashboard tab appears. "No pipelines yet" messaging moves inside the Dashboard empty state.

### Stage Display Logic

| Pipeline state | Stage shown |
|----------------|-------------|
| `status === 'running'` and a stage has `status === 'running'` | That stage's name |
| `status === 'running'` and no running stage found | `'Waiting…'` |
| `status === 'paused'` | `'Paused'` |
| `status === 'completed'` | `'Done'` |
| `status === 'failed'` | `'Failed'` |
| `status === 'cancelled'` | `'Cancelled'` |
| other | `''` |

### Elapsed Display Logic

| Pipeline state | Elapsed display |
|----------------|-----------------|
| running or paused | Live counter via `tickDashboardElapsed()` using `elapsed(state)` helper |
| completed / failed / cancelled | Static — total duration shown from `state.startedAt` to `state.finishedAt` |
| idle / no startedAt | `—` |

## Implementation Plan

### Phase 1: CSS — Dashboard Styles (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `.dashboard-layout` — wrapper with padding consistent with `.archived-layout`
- [ ] Add `.dashboard-grid` — CSS grid: `grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px;`
- [ ] Add `.dash-card` — `background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; cursor: pointer;`
- [ ] Add `.dash-card:hover` — subtle border highlight (`border-color: #388bfd`)
- [ ] Add `.dash-card-name` — pipeline name; `font-size: 0.95rem; font-weight: 600; color: #f0f6fc; margin-bottom: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`
- [ ] Add `.dash-card-row` — `display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;`
- [ ] Add `.dash-elapsed` — `font-family: monospace; font-size: 0.85rem; color: #e3b341; font-weight: 600;`
- [ ] Add `.dash-stage-row` — `display: flex; align-items: center; gap: 6px; font-size: 0.8rem; color: #8b949e;`
- [ ] Add `.dash-stage-icon` — `flex-shrink: 0;`
- [ ] Add `.dash-stage-name` — `overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`
- [ ] Add `.dash-empty` — `text-align: center; padding: 48px 24px; color: #6e7681; font-style: italic;`
- [ ] Add `.tab.dash-tab` — same base style as `.tab`; no additional treatment needed (active state handled by existing `.tab.active` selector)

### Phase 2: renderTabs() — Static Dashboard Tab (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Add `var DASHBOARD_TAB_ID = '__dashboard__';` at the top of the `<script>` block, alongside existing state vars
- [ ] Remove the early-return branch `if (visibleIds.length === 0) { bar.innerHTML = ...; return; }`
- [ ] At the start of `renderTabs()`, prepend Dashboard tab before the pipeline-tabs loop:
  ```javascript
  var dashActive = selectedId === DASHBOARD_TAB_ID ? ' active' : '';
  html = '<div class="tab dash-tab' + dashActive + '" onclick="selectTab(DASHBOARD_TAB_ID)">&#128202; Dashboard</div>';
  ```
- [ ] Existing pipeline tab rendering loop runs after, unchanged (except empty `bar.innerHTML` case now just renders the Dashboard tab by itself)

### Phase 3: selectTab() / renderMain() Routing (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Add `var dashboardTimer = null;` near existing `var elapsedTimer = null;`
- [ ] Add `function startDashboardTimer()` — `dashboardTimer = setInterval(tickDashboardElapsed, 1000);`
- [ ] Add `function stopDashboardTimer()` — `clearInterval(dashboardTimer); dashboardTimer = null;`
- [ ] In `selectTab(id)`:
  - Add `stopDashboardTimer();` at the top (harmless if not running)
  - When `id !== DASHBOARD_TAB_ID`: existing pipeline logic runs unchanged
  - When `id === DASHBOARD_TAB_ID`: `selectedId = DASHBOARD_TAB_ID; panelBuiltFor = null; renderTabs(); renderMain();` — do NOT call `buildPanel()`
- [ ] In `renderMain()`: add branch at the very top before `buildPanel()` check:
  ```javascript
  if (selectedId === DASHBOARD_TAB_ID) { renderDashboard(); return; }
  ```
- [ ] In `buildPanel(id)`: add `stopDashboardTimer();` at the top (alongside existing `clearInterval(elapsedTimer)`)
- [ ] Change initial `selectedId` from `null` to `DASHBOARD_TAB_ID`
- [ ] Change `if (isNew && !selectedId) selectedId = p.id;` to `if (isNew && selectedId === DASHBOARD_TAB_ID) selectedId = p.id;` in `applyUpdate()`

### Phase 4: renderDashboard() and tickDashboardElapsed() (~50%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Add `function renderDashboard()`:
  - Calls `stopDashboardTimer()` (clean up any prior instance)
  - Gets all pipeline IDs: `var ids = orderedIds || Object.keys(pipelines);`
  - Filters to non-archived: `ids.filter(function(id) { return !(pipelines[id].state && pipelines[id].state.archived); })`
  - Sort: running/paused first, then by position in `ids`
  - If none: render empty state into `#mainContent`:
    ```html
    <div class="dashboard-layout">
      <div class="dash-empty">
        <div>No pipelines yet.</div>
        <div style="margin-top:8px;">Use <strong>Create</strong> to generate and run a pipeline.</div>
      </div>
    </div>
    ```
  - Otherwise: render `.dashboard-layout > .dashboard-grid > .dash-card` per pipeline
  - Card content:
    ```javascript
    var st = p.state || {};
    var name = esc(st.pipeline || p.fileName || 'pipeline');
    var status = st.status || 'idle';
    var stageLabel = getDashStageLabel(p);
    var stageIcon  = getDashStageIcon(status);
    var elapsedStr = getDashElapsed(st);
    var elapsedId  = 'dash-elapsed-' + id;
    ```
  - After HTML is written to `#mainContent`, calls `startDashboardTimer()`

- [ ] Add `function getDashStageLabel(p)`:
  - If `p.state.status === 'paused'`: return `'Paused'`
  - If `p.state.status === 'completed'`: return `'Done'`
  - If `p.state.status === 'failed'`: return `'Failed'`
  - If `p.state.status === 'cancelled'`: return `'Cancelled'`
  - If `p.state.status === 'running'`: find first `stage` in `p.state.stages` where `stage.status === 'running'`; if found return `esc(stage.name)`; else return `'Waiting…'`
  - Default: return `''`

- [ ] Add `function getDashStageIcon(status)`:
  - `'running'` → `'⚡'`; `'paused'` → `'⏸'`; `'completed'` → `'✓'`; `'failed'` → `'✗'`; `'cancelled'` → `'—'`; default `''`

- [ ] Add `function getDashElapsed(st)`:
  - If no `st.startedAt`: return `'—'`
  - If `st.status === 'running' || st.status === 'paused'`: return `elapsed(st)` (existing helper)
  - Otherwise (terminal): compute total = `(st.finishedAt || Date.now()) - st.startedAt`; format via `fmtDur(total)` (existing helper); return that string

- [ ] Add `function tickDashboardElapsed()`:
  ```javascript
  function tickDashboardElapsed() {
    var spans = document.querySelectorAll('.dash-elapsed[data-pipeline-id]');
    for (var i = 0; i < spans.length; i++) {
      var id = spans[i].getAttribute('data-pipeline-id');
      var p = pipelines[id];
      if (!p || !p.state) continue;
      var st = p.state;
      if (st.status !== 'running' && st.status !== 'paused') continue;
      spans[i].textContent = elapsed(st);
    }
  }
  ```
  (Only updates active runs — terminal runs have static elapsed already in their text)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Dashboard CSS; `DASHBOARD_TAB_ID` constant; `renderTabs()` static tab; `selectTab()`/`renderMain()` routing; `renderDashboard()`; `tickDashboardElapsed()`; timer lifecycle helpers |

Only one file changes.

## Definition of Done

- [ ] `DASHBOARD_TAB_ID = '__dashboard__'` constant used everywhere (no string literal `'__dashboard__'` scattered through code)
- [ ] Dashboard tab always visible at leftmost position in `#tabBar` — even when zero pipelines exist
- [ ] Page loads to Dashboard by default (empty state shows when no pipelines)
- [ ] When the first pipeline is submitted, view auto-navigates from Dashboard to that pipeline's tab
- [ ] Clicking Dashboard tab renders summary panel in `#mainContent`, not a pipeline detail
- [ ] Summary panel shows all non-archived pipelines as cards
- [ ] Each card shows: pipeline name, status badge, current stage label (or 'Paused'/'Done'/'Failed'/'Cancelled'), elapsed time
- [ ] Elapsed counters for running/paused pipelines tick every second (`dashboardTimer`)
- [ ] Terminal pipelines (completed/failed/cancelled) show static total duration
- [ ] Empty state shown when all pipelines are archived (or none exist)
- [ ] Clicking a card navigates to that pipeline's detail tab
- [ ] Switching away from Dashboard clears `dashboardTimer` (no leak)
- [ ] Switching to a pipeline tab calls `stopDashboardTimer()` in `buildPanel()`
- [ ] All existing pipeline tabs and detail views function correctly (no regression)
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `DASHBOARD_TAB_ID` sentinel used as a pipeline ID in existing code | Medium | Medium | Audit every `pipelines[selectedId]` lookup — all have `if (!p) return;` guards; those return cleanly for the sentinel |
| `dashboardTimer` leaks if `stopDashboardTimer()` not called on all exit paths | Medium | Low | `stopDashboardTimer()` called at top of `selectTab()` (all tab switches) and in `buildPanel()` — covers all paths |
| Auto-navigate change breaks "stay on dashboard" UX | Low | Medium | Change is from `!selectedId` to `selectedId === DASHBOARD_TAB_ID`; once user clicks a pipeline tab or dashboard card, `selectedId` is no longer the sentinel, so the auto-nav never fires again until they click Dashboard again |
| Dashboard renders stale stage info between SSE events | Very Low | Low | `applyUpdate()` calls `renderMain()` on every event; `tickDashboardElapsed()` handles elapsed-only updates between events |
| `elapsed(state)` / `fmtDur` helpers don't exist in current code | Low | High | Verify these helpers exist before Phase 4; if not, inline the equivalent logic |

## Security Considerations

- No new server endpoints; no new request paths
- Dashboard renders existing in-memory pipeline state only
- All pipeline names and stage names pass through the existing `esc()` helper before DOM insertion
- `DASHBOARD_TAB_ID` is a fixed string constant, never derived from user input

## Dependencies

- Sprint 001 (completed) — SSE + polling keeps `pipelines{}` state fresh; `applyUpdate()` drives dashboard updates
- Sprint 002 (completed) — stable `selectedId` / `renderMain()` model that this sprint extends
- Sprint 003 (completed) — imported runs flow into `pipelines{}` and appear on Dashboard naturally

## Open Questions

1. Should cards be sorted beyond running-first? (e.g. by creation time, by pipeline name) — deferred; default insertion order is fine for Sprint 004.
2. Should the Dashboard tab show a live count badge (e.g. "Dashboard (2)" when two pipelines are active)? — deferred; nice-to-have.
3. Should the Dashboard tab respond to `showView('monitor')` calls when Monitor is re-selected from the nav? — current behavior: `showView('monitor')` doesn't reset `selectedId`, so Dashboard stays selected if it was last active. Acceptable for now.
