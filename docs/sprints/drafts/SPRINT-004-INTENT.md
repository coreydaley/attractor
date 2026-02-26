# Sprint 004 Intent: Dashboard Tab

## Seed

I want to have a static tab on the left hand side of the tabs area that says "Dashboard" that shows a dashboard of any pipelines that are running and their current overall status and what stage they are currently executing and their current run time

## Context

Attractor is a DOT-based pipeline runner with a multi-tab web dashboard. The Monitor view holds a horizontal `#tabBar` where each running or completed pipeline gets its own dynamically-created tab. The only way to see what's happening across all pipelines is to click through tabs one at a time. There is no at-a-glance view of all running activity simultaneously.

The request is to add a **static "Dashboard" tab** permanently pinned to the left of the tab bar. When selected, `#mainContent` shows a summary panel listing all currently running (and paused) pipelines in compact cards: pipeline name, status badge, currently executing stage name, and live elapsed run time. Clicking a card navigates to that pipeline's detail tab.

## Recent Sprint Context

- **Sprint 001** — Fixed SSE delivery latency and polling cadence. Established `applyUpdate()` as the single data-update entry point and `kickPoll()` for immediate polling. Elapsed timer (`tickElapsed`) is a `setInterval` that ticks the active panel's counters.
- **Sprint 002** — Added in-place Iterate flow. `selectedId` is the core UI state variable; `renderMain()` reads it to decide what to render. `buildPanel()` / `updatePanel()` work on real pipeline IDs.
- **Sprint 003** — Export/Import round-trip via ZIP. Pattern: new endpoints + minimal UI surface, all in the same `WebMonitorServer.kt`. Settings view added as an entirely independent `#viewSettings` div.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — **sole file to change**
  - `renderTabs()` — builds `#tabBar` innerHTML; has an early-return that shows a "No pipelines" placeholder when the pipeline list is empty
  - `selectTab(id)` — sets `selectedId`, forces panel rebuild; currently only handles real pipeline IDs
  - `renderMain()` — if `selectedId` is set, calls `buildPanel()` then `updatePanel()`; needs to branch on `'__dashboard__'` sentinel
  - `applyUpdate(data)` — called on every SSE message + poll; calls `renderTabs()` and `renderMain()`; also auto-selects the first pipeline (`if (isNew && !selectedId) selectedId = p.id`)
  - `tickElapsed()` — 1-second interval updating `#pElapsed` and `.stage-live-dur` elements in the detail panel
  - `buildPanel(id)` — clears `elapsedTimer` when rebuilding the scaffold; dashboard will need a parallel `dashboardTimer`

## Constraints

- No server-side changes — dashboard is pure client-side rendering over data already in the `pipelines` JS state object
- No new Gradle dependencies
- Java 21 build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- Must coexist with pipeline-run tabs; Dashboard tab is always visible (even when zero pipelines)
- Must not interfere with `buildPanel` / `updatePanel` which use `selectedId` as a real pipeline ID

## Success Criteria

1. A "Dashboard" tab is always visible at the left of `#tabBar`
2. Clicking it shows a summary panel (not a pipeline detail) listing all running/paused pipelines
3. Each pipeline card shows: pipeline name, status badge, currently executing stage name, and live elapsed time
4. Elapsed counters tick every second (like the per-pipeline elapsed counter)
5. Clicking a pipeline card navigates to that pipeline's detail tab
6. Dashboard auto-updates as SSE/poll events arrive
7. Empty state shown when no pipelines are currently running

## Verification Strategy

- Testing approach: manual smoke test
  1. Load the app with no pipelines — Dashboard tab is visible, empty state shows
  2. Start a pipeline — card appears with pipeline name, "running" badge, current stage, ticking elapsed
  3. Start a second pipeline simultaneously — both cards visible on dashboard
  4. Click a card — navigates to that pipeline's detail tab
  5. All running pipelines complete — cards disappear, empty state shows (or completed pipelines shown with completed badge)
  6. Build passes: `export JAVA_HOME=... && ~/.gradle/.../gradle-8.7/bin/gradle -p . jar`

## Uncertainty Assessment

- Correctness uncertainty: **Low** — pure client-side rendering, no new server state
- Scope uncertainty: **Low** — seed is specific; main open question is which pipeline statuses to show
- Architecture uncertainty: **Low** — `'__dashboard__'` sentinel in `selectedId` extends existing patterns cleanly

## Open Questions

1. Should Dashboard show **only running** pipelines, or also recently-completed/failed/paused ones?
2. Should Dashboard auto-select when the page first loads (replacing the current behavior where the first arriving pipeline auto-selects)?
3. When a new pipeline is submitted and the user is on the Dashboard, should the view stay on Dashboard or auto-navigate to the new pipeline tab?
4. Any specific visual treatment for the Dashboard tab to distinguish it from pipeline run tabs (e.g. icon, pinned styling)?
