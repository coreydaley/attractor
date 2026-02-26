# Sprint 004: Dashboard Tab

## Overview

The Monitor view currently forces users to click through individual pipeline tabs to understand what is running and how it is progressing. With multiple concurrent pipelines, there is no way to get an at-a-glance view of all activity without switching tabs repeatedly. This becomes especially painful when running automated or batch scenarios where several pipelines might be in-flight simultaneously.

This sprint adds a **static "Dashboard" tab** permanently pinned to the left of the `#tabBar`. Regardless of how many pipeline-run tabs exist, the Dashboard tab is always the first tab visible. When selected, `#mainContent` shows a compact summary panel — a grid of cards, one per active (running or paused) pipeline — each showing: the pipeline name, a live status badge, the name of the currently-executing stage, and a ticking elapsed time counter. Clicking any card navigates to that pipeline's full detail tab.

All changes are client-side, live entirely in `WebMonitorServer.kt`, and require no new server endpoints or Gradle dependencies. The implementation extends the existing `renderTabs()` / `selectTab()` / `renderMain()` patterns by introducing a `'__dashboard__'` sentinel ID that routes `renderMain()` to a new `renderDashboard()` code path.

## Use Cases

1. **Multi-pipeline monitoring**: User starts three pipelines concurrently. Dashboard shows all three cards with live stage progress and elapsed timers — no tab-switching required.
2. **Status at a glance**: User submits a long-running pipeline and navigates away to the Create tab. Returning to Monitor, they click Dashboard first to see current status before drilling into a specific tab.
3. **Empty state orientation**: User opens the app before submitting any pipelines. Dashboard tab is visible and shows a friendly empty state with instructions, replacing the current "No pipelines yet" placeholder in the tab bar.
4. **Quick navigation**: On the Dashboard, user notices one pipeline has been running unusually long. They click its card to jump directly to its detail view with logs and stage list.

## Architecture

```
#tabBar (always rendered)
├── [Dashboard tab]   ← static, always leftmost, onclick="selectTab('__dashboard__')"
├── [Pipeline tab 1]  ← dynamic, per run
├── [Pipeline tab 2]
└── ...

selectedId = '__dashboard__'
└── renderMain()
    └── renderDashboard()
        ├── finds pipelines with status === 'running' | 'paused'
        ├── renders #mainContent as dashboard-layout div
        │   ├── .dashboard-grid
        │   │   └── .dash-card (per pipeline)  onclick="selectTab(id)"
        │   │       ├── .dash-card-name         pipeline name
        │   │       ├── .dash-card-row
        │   │       │   ├── .badge              status badge
        │   │       │   └── .dash-elapsed       elapsed time (live)
        │   │       └── .dash-stage-row
        │   │           ├── stage icon (running → ⚡, paused → ⏸)
        │   │           └── .dash-stage-name    currently-executing stage
        │   └── .dash-empty (if no active pipelines)
        └── starts dashboardTimer (1-second tick)

applyUpdate(data) [unchanged]
└── renderTabs() → always prepends Dashboard tab
└── renderMain() → if selectedId === '__dashboard__', calls renderDashboard()

dashboardTimer (setInterval, 1 second)
└── tickDashboardElapsed()
    └── updates .dash-elapsed spans in-place (no full re-render)
```

### Key Design Decisions

**Sentinel ID**: `selectedId = '__dashboard__'` distinguishes the dashboard from real pipeline tabs. The existing `buildPanel()` / `updatePanel()` functions are not called for this ID.

**Static tab in `renderTabs()`**: Dashboard tab is prepended unconditionally. The `if (visibleIds.length === 0) { bar.innerHTML = placeholder }` early-return is removed; the tab bar always has at least one tab.

**Dashboard timer**: When Dashboard is selected, `dashboardTimer = setInterval(tickDashboardElapsed, 1000)` starts. `tickDashboardElapsed()` finds all `.dash-elapsed` elements (which carry `data-started-at` attributes) and updates their text content in-place. This is the same pattern as `tickElapsed()` for stage counters. `buildPanel()` clears `elapsedTimer`; similarly, `selectTab('__dashboard__')` must clear `elapsedTimer` and start `dashboardTimer`, while `selectTab(realId)` must clear `dashboardTimer`.

**Auto-selection**: The existing `if (isNew && !selectedId) selectedId = p.id` behavior is preserved. When the page loads with no pipelines and `selectedId` initializes to `'__dashboard__'`, this condition is truthy for `!selectedId` only when `selectedId` is null/undefined. To preserve the auto-select behavior while also defaulting to the dashboard, I'll initialize `selectedId = '__dashboard__'` and change the condition to `if (isNew && selectedId === '__dashboard__') selectedId = p.id;` — so submitting the first pipeline navigates to it automatically, but the user can always return to the Dashboard.

**"Currently executing stage"**: Find the first stage in `p.state.stages` with `status === 'running'`; fall back to the last stage with `status === 'retrying'`; fall back to `'Initializing…'` if none.

## Implementation Plan

### Phase 1: CSS — Dashboard Styles (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `.dashboard-layout` CSS: full-width layout, padding consistent with `.archived-layout`
- [ ] Add `.dashboard-grid` CSS: CSS grid with `repeat(auto-fill, minmax(280px, 1fr))` columns, gap
- [ ] Add `.dash-card` CSS: card-style container (`background: #161b22`, border, border-radius, padding, cursor pointer, hover effect)
- [ ] Add `.dash-card:hover` CSS: subtle highlight on hover
- [ ] Add `.dash-card-name` CSS: pipeline name — large-ish, `#f0f6fc`, font-weight 600
- [ ] Add `.dash-card-row` CSS: flexbox row for badge + elapsed, space-between
- [ ] Add `.dash-elapsed` CSS: monospace, `#e3b341`, font-weight 600 (matches existing `pElapsed` style)
- [ ] Add `.dash-stage-row` CSS: flexbox row for stage icon + stage name, `#c9d1d9`, small font
- [ ] Add `.dash-stage-icon` CSS: fixed-width icon cell, centered
- [ ] Add `.dash-stage-name` CSS: stage name text, truncate with ellipsis if too long
- [ ] Add `.dash-empty` CSS: centered empty state, `#6e7681`, italic style matching existing `.tab-empty`
- [ ] Add `.tab.dash-tab` CSS: distinct visual treatment for the static Dashboard tab (e.g. slightly dimmer when inactive, or a consistent pinned look)

### Phase 2: renderTabs() — Static Dashboard Tab (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Remove the early-return `if (visibleIds.length === 0) { bar.innerHTML = placeholder; return; }` — it is replaced by the dashboard empty state
- [ ] At the start of `renderTabs()`, prepend the Dashboard tab HTML to the pipeline-tabs HTML:
  ```javascript
  var dashActive = (selectedId === '__dashboard__') ? ' active' : '';
  html = '<div class="tab dash-tab' + dashActive + '" onclick="selectTab(\'__dashboard\')">&#128202; Dashboard</div>';
  ```
- [ ] If `visibleIds.length === 0`, still output just the Dashboard tab (no pipeline tabs, no placeholder)
- [ ] Existing pipeline tab rendering loop runs after Dashboard tab (no other changes to loop)

### Phase 3: selectTab() and renderMain() — Routing (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Add `var dashboardTimer = null;` at top of `<script>` block
- [ ] In `selectTab(id)`:
  - Add: clear `dashboardTimer` when `id !== '__dashboard__'`
  - Clear `elapsedTimer` when `id === '__dashboard__'` (to stop the detail-panel tick)
  - Allow `panelBuiltFor = null` reset for non-dashboard IDs (unchanged)
  - When `id === '__dashboard__'`: call `renderTabs()` and `renderDashboard()` directly (do not call `buildPanel`/`updatePanel`)
- [ ] In `renderMain()`:
  - Add branch: `if (selectedId === '__dashboard__') { renderDashboard(); return; }`
  - Existing logic (buildPanel / updatePanel) follows unchanged
- [ ] Change initial `selectedId` from `null` to `'__dashboard__'`
- [ ] Change `if (isNew && !selectedId) selectedId = p.id;` to `if (isNew && selectedId === '__dashboard__') selectedId = p.id;` in `applyUpdate()`

### Phase 4: renderDashboard() and tickDashboardElapsed() (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (JS)

**Tasks:**
- [ ] Add `function renderDashboard()`:
  - Clears `dashboardTimer`
  - Gets all pipeline IDs from `pipelines` object
  - Filters to `status === 'running'` or `status === 'paused'`
  - If none: renders empty state in `#mainContent`
  - Otherwise: renders `.dashboard-layout` > `.dashboard-grid` > one `.dash-card` per active pipeline
  - Each card:
    - `onclick="selectTab('${id}')"` for navigation
    - `.dash-card-name` = `esc(st.pipeline || p.fileName)`
    - `.badge.badge-${status}` = status badge
    - `.dash-elapsed` with `data-started-at="${st.startedAt || ''}"` and `id="dash-elapsed-${id}"` = current elapsed
    - `.dash-stage-row` with stage icon + currently-executing stage name (find stage with `status === 'running'`, fall back to `'retrying'`, else `'Initializing…'`)
  - After rendering, starts `dashboardTimer = setInterval(tickDashboardElapsed, 1000)`

- [ ] Add `function tickDashboardElapsed()`:
  ```javascript
  function tickDashboardElapsed() {
    var cards = document.querySelectorAll('.dash-elapsed[data-started-at]');
    for (var i = 0; i < cards.length; i++) {
      var startedAt = parseInt(cards[i].getAttribute('data-started-at'), 10);
      if (startedAt) cards[i].textContent = fmtElapsed(startedAt);
    }
  }
  ```
  Where `fmtElapsed(startedAt)` reuses the existing `elapsed()` function logic (or the inline calc from `tickElapsed`).

- [ ] Reuse `fmtDur` / `elapsed()` helper for elapsed display format

### Phase 5: Integration and Cleanup (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] In `buildPanel(id)` — add `clearInterval(dashboardTimer); dashboardTimer = null;` at the top, parallel to existing `clearInterval(elapsedTimer)`
- [ ] Verify `applyUpdate()` auto-navigates to first new pipeline (not `'__dashboard__'`) — matches desired UX
- [ ] Verify Dashboard tab appears `active` when selected and in correct visual position
- [ ] Verify existing `renderTabs()` call in `selectTab()` still correctly marks pipeline tabs as active
- [ ] Build and smoke-test

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Dashboard tab CSS, `renderTabs()` static tab, routing in `renderMain()`/`selectTab()`, `renderDashboard()`, `tickDashboardElapsed()` |

Only one file changes.

## Definition of Done

- [ ] Dashboard tab is always visible at the leftmost position in `#tabBar`, even when no pipelines exist
- [ ] Clicking Dashboard tab shows the dashboard panel in `#mainContent`
- [ ] Running pipelines appear as cards with: name, status badge, currently-executing stage name, elapsed time
- [ ] Elapsed timers on dashboard cards tick every second via `dashboardTimer`
- [ ] Paused pipelines also appear on the dashboard (with "paused" badge)
- [ ] Completed/failed/cancelled pipelines do NOT appear on the dashboard
- [ ] Empty state ("No active pipelines") shown when no running/paused pipelines exist
- [ ] Clicking a pipeline card navigates to that pipeline's detail tab
- [ ] When the first new pipeline is submitted (from any state), the view auto-navigates to it (existing UX preserved)
- [ ] No timer leak: switching away from Dashboard clears `dashboardTimer`; switching to Dashboard clears `elapsedTimer`
- [ ] All existing pipeline tabs and detail views function correctly (no regression)
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `dashboardTimer` not cleared on tab switch → two timers running | Medium | Low | Explicit `clearInterval(dashboardTimer)` in `buildPanel()` and in `selectTab()` when switching to a real pipeline |
| `selectedId = '__dashboard__'` sentinel breaks existing code that treats `selectedId` as a pipeline ID | Medium | Medium | Audit every use of `selectedId`: add guards where pipeline lookup is done (`pipelines[selectedId]` returns undefined for sentinel — existing null-check `if (!p) return;` pattern already handles this) |
| Auto-select behavior change breaks "first pipeline auto-tabs" UX | Low | Medium | Change condition to `if (isNew && selectedId === '__dashboard__') selectedId = p.id;` — preserves intent |
| Dashboard renders stale data between SSE events | Very Low | Low | Dashboard is re-rendered on every `applyUpdate()` call (same cadence as pipeline tabs); `dashboardTimer` handles elapsed-only updates in between |
| Stage extraction logic returns wrong stage | Low | Low | Use explicit `status === 'running'` check; fallback chain to 'retrying' then 'Initializing…' is safe |

## Security Considerations

- No new server-side surface; all changes are to embedded client-side JS/HTML
- `esc()` helper used for all user-supplied strings in generated HTML (pipeline name, stage name)
- `selectTab()` called with `'__dashboard__'` is a local string literal, not from user input

## Dependencies

- Sprint 001 (completed) — SSE + polling infrastructure that drives `applyUpdate()`
- Sprint 002 (completed) — `selectedId` / `renderMain()` patterns this sprint extends
- Sprint 003 (completed) — no direct dependency; coexists cleanly

## Open Questions

1. **Which statuses to show**: Running + paused only (as drafted)? Or include recently-completed pipelines for some period (e.g. last 5 min)?
2. **Auto-navigate on new pipeline**: When the user is on Dashboard and submits a new pipeline, should it stay on Dashboard or navigate to the new pipeline tab? (Draft: navigate, matching current behavior)
3. **Dashboard as initial view**: Should the page default to Dashboard (draft: yes, `selectedId = '__dashboard__'` initial) or to the first pipeline (existing behavior)?
4. **Tab visual treatment**: Should the Dashboard tab have a special icon (e.g. &#128202; 📊) to distinguish it from pipeline tabs?
