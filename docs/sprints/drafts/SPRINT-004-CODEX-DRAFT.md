# Sprint 004: Dashboard Tab

## Overview

The Monitor currently treats pipeline tabs as the only navigation model: each run is a dynamic tab, and `selectedId` always points to a concrete pipeline ID. This makes single-run inspection strong, but it forces users to click through tabs one by one to understand overall activity.

This sprint adds a **static, pinned Dashboard tab** at the far left of the tab bar. Selecting it renders a dashboard view in `#mainContent` with compact cards for active pipelines (running and paused), showing pipeline name, status, current stage, and live elapsed time. Cards are clickable and route directly into the corresponding run tab.

Scope is intentionally client-only and isolated to `WebMonitorServer.kt` embedded HTML/CSS/JS. No backend API changes and no new dependencies.

## Use Cases

1. **At-a-glance monitoring**: User starts multiple pipelines and immediately sees all active runs in one view without tab-hopping.
2. **Fast triage**: User sees one run paused while another is running and clicks directly into the paused card.
3. **Live progress awareness**: Dashboard elapsed counters and active stage labels update every second while runs are active.
4. **Idle-state clarity**: If no pipeline is running or paused, Dashboard shows a clear empty state while still remaining selectable.

## Architecture

### Selection Model Extension

Add a sentinel tab ID for Dashboard:
- `DASHBOARD_TAB_ID = '__dashboard__'`

`selectedId` semantics become:
- `selectedId === '__dashboard__'` -> render dashboard summary
- `selectedId === <pipelineId>` -> render existing pipeline panel
- `selectedId === null` -> fallback behavior only before first data hydration

### Rendering Flow

```text
applyUpdate(data)
  -> merge pipelines
  -> if first load and nothing selected: selectedId = '__dashboard__'
  -> if selected pipeline disappears: selectedId = '__dashboard__'
  -> renderTabs()       // always includes Dashboard tab first
  -> renderMain()
      -> if selectedId === '__dashboard__': renderDashboard()
      -> else: existing buildPanel()/updatePanel()
```

### Dashboard Card Data Model

Derived purely from `pipelines` in browser state:
- `name`: `p.state.pipeline || p.fileName`
- `status`: `p.state.status || 'idle'`
- `currentStage`: first stage where `status === 'running'`; if none and run paused, show `'Paused'`; else `'Waiting'`
- `elapsed`: reuse existing `elapsed(state)` helper

Dashboard list inclusion rule for Sprint 004:
- include only `status in {'running', 'paused'}`

### Live Timer Strategy

Current code has `elapsedTimer` for detail panel-only ticking. Add parallel timer for dashboard:
- `dashboardTimer = setInterval(tickDashboardElapsed, 1000)` when dashboard is active
- clear on tab switch away from dashboard

`tickDashboardElapsed()` updates:
- `.dash-elapsed[data-id]`
- optional `.dash-stage-live-dur[data-started-at]` for running stage counters

## Implementation Plan

### Phase 1: Tab Bar + Selection Sentinel (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `DASHBOARD_TAB_ID` constant in script scope.
- [ ] Update `renderTabs()` to always prepend a static Dashboard tab before dynamic pipeline tabs.
- [ ] Remove/adjust early-return "No pipelines yet" tab-bar branch so Dashboard tab is visible even with zero runs.
- [ ] Update `selectTab(id)` to handle Dashboard sentinel cleanly (no pipeline graph invalidation for dashboard).
- [ ] Ensure first selection after hydration defaults to Dashboard (replace `if (isNew && !selectedId) selectedId = p.id` behavior).

### Phase 2: Dashboard Main View + Card Navigation (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `renderDashboard()` that writes a dashboard-specific scaffold into `#mainContent`.
- [ ] Add dashboard card markup for active runs with:
  - pipeline name
  - status badge
  - current stage label
  - elapsed counter
- [ ] Add empty-state dashboard panel when no running/paused runs exist.
- [ ] Add click behavior on each card: `selectTab(pipelineId)`.
- [ ] Update `renderMain()` branch:
  - `selectedId === DASHBOARD_TAB_ID` -> dashboard path
  - existing pipeline path unchanged

### Phase 3: Live Elapsed Updates (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `dashboardTimer` variable and lifecycle helpers (`startDashboardTimer()`, `stopDashboardTimer()`).
- [ ] Add `tickDashboardElapsed()` to refresh dashboard elapsed strings every second.
- [ ] Ensure timer teardown on view switch to pipeline tab and on monitor view hide to avoid duplicate intervals.
- [ ] Keep existing `elapsedTimer` logic for detail panel unchanged.

### Phase 4: Styling + Visual Integration (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add dashboard-specific CSS classes (`.dashboard-wrap`, `.dashboard-grid`, `.dash-card`, `.dash-meta`, `.dash-empty`).
- [ ] Style Dashboard tab with existing tab visual language, plus a subtle pinned treatment (e.g., icon + consistent first position).
- [ ] Ensure cards are responsive (single-column on narrow screens).
- [ ] Reuse existing badge classes for status colors (`badge-running`, `badge-paused`) to avoid palette drift.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add static Dashboard tab, dashboard renderer, live elapsed ticking, and dashboard card CSS/JS |
| `docs/sprints/drafts/SPRINT-004-CODEX-DRAFT.md` | Modify | Sprint 004 Codex planning draft |

## Definition of Done

- [ ] Dashboard tab is always visible as the left-most tab in `#tabBar`, even when there are zero pipelines.
- [ ] Clicking Dashboard renders a summary view (not pipeline detail UI).
- [ ] Summary view lists running/paused pipelines only.
- [ ] Each card shows pipeline name, status badge, currently executing stage (or paused/waiting fallback), and elapsed runtime.
- [ ] Elapsed values tick every second while Dashboard is active.
- [ ] Clicking a dashboard card navigates to that run’s detail tab.
- [ ] Dashboard updates automatically from existing SSE + poll update flow (`applyUpdate`).
- [ ] Empty state appears when there are no running/paused pipelines.
- [ ] Existing per-run monitor behavior remains intact: stage list, logs, graph, actions.
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`.
- [ ] No new Gradle dependencies.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Sentinel ID collides with a real run ID | Low | Medium | Use reserved string unlikely to be generated (`'__dashboard__'`) and guard everywhere by strict equality |
| Timer leaks from switching tabs/views | Medium | Low | Centralize dashboard timer start/stop in `renderMain()` and `showView()` |
| Regressions from changing selection defaults | Medium | Medium | Keep fallback to selected run if user already selected one; only default to dashboard on initial hydration/invalid selection |
| Dashboard stage detection is misleading for paused runs | Low | Low | Explicitly label paused runs as `Paused` when no running stage exists |

## Security Considerations

- No new server endpoints and no new request paths.
- Dashboard renders existing in-memory pipeline state only.
- Card labels continue using existing escaping helper (`esc`) before HTML interpolation.

## Dependencies

- Sprint 001 (completed): reliable SSE + poll convergence ensures dashboard freshness.
- Sprint 002 (completed): stable `selectedId` tab selection model is extended, not replaced.
- Sprint 003 (completed): imported runs flow into same `pipelines` state and are naturally visible.

## Open Questions

1. Should dashboard include recently completed/failed runs in a secondary section, or strictly running/paused only for Sprint 004?
2. On first load, should app always start on Dashboard, or preserve current behavior of auto-selecting the first new pipeline when one appears?
3. Should paused runs sort above running runs (triage-first) or follow existing insertion order?
