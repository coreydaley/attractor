# Sprint 016 Intent: Closeable Pipeline Tabs

## Seed

Users should have the ability to close a tab without having to archive or delete the pipeline,
each tab should have an 'x' to close the tab and not display it in the interface, but the
pipeline should still display on the dashboard page. Open tabs should persist across screen
refreshes.

## Context

The Monitor view shows a horizontal tab bar where each pipeline family gets one tab. Currently,
the only way to remove a tab is to archive or delete the underlying pipeline. Users with many
past runs accumulate a crowded tab bar with no lightweight way to clear it. The feature requests
a "close" affordance: an × button on each tab that hides the tab from the bar without touching
the pipeline's data — it still appears as a card on the Dashboard.

"Open tabs should persist across screen refreshes" means the set of explicitly closed tabs must
survive a page reload. `localStorage` already carries `attractor-selected-tab` and
`attractor-theme`; adding `attractor-closed-tabs` follows the same pattern.

## Recent Sprint Context

- **Sprint 013** (In-App Docs): Added `/docs` route + docs nav button. Pure additive HTML/JS.
- **Sprint 014** (DOT File Upload): Added `.dot` file picker to Create view; pure client-side JS/HTML.
- **Sprint 015** (Comprehensive Tests): Closed test gaps across REST API, CLI, and browser API;
  introduced `WebMonitorServerBrowserApiTest`, `RestApiLlmTest`, `ModelsCommandTest`, `EventsCommandTest`.

Recent pattern: all UI features touch only `WebMonitorServer.kt`; tests are Kotest `FunSpec`
with an ephemeral `WebMonitorServer` on port 0.

## Relevant Codebase Areas

| Location | Line | Description |
|----------|------|-------------|
| `WebMonitorServer.kt` | ~2199 | `.tab`, `.tab-bar` CSS — where `.tab-close` style goes |
| `WebMonitorServer.kt` | ~2756 | Module-scope JS vars (`pipelines{}`, `selectedId`, `_storedTab`) |
| `WebMonitorServer.kt` | ~2974 | `renderTabs()` — filter closed tabs, add × buttons |
| `WebMonitorServer.kt` | ~2999 | `selectTab(id)` — remove from closedTabs when (re-)selecting |
| `WebMonitorServer.kt` | ~3634 | `applyUpdate()` — skip auto-open for closed tabs; clean up on delete |

## Constraints

- No new Gradle dependencies
- No server-side changes — purely client-side `localStorage` state management
- Close button must not trigger `selectTab()` — requires `event.stopPropagation()`
- Dashboard tab (⊞ Dashboard) must NOT have a close button
- `closedTabs` entries for server-deleted pipelines must be pruned to prevent unbounded localStorage growth
- Kotest FunSpec test pattern (ephemeral port 0 `WebMonitorServer`)

## Success Criteria

1. Every pipeline tab has a visible × close button
2. Clicking × removes the tab from the tab bar without archiving/deleting
3. The pipeline card remains visible on the Dashboard after closing
4. Clicking the dashboard card reopens the tab (removes from `closedTabs`, selects it)
5. Closed tabs persist in `localStorage` key `attractor-closed-tabs` across page refreshes
6. If the persisted `selectedId` was a closed tab, it falls back to Dashboard on refresh
7. A new pipeline (never explicitly closed) still auto-opens in the tab bar
8. Markup-presence test confirms `closeTab` JS and `.tab-close` CSS are present

## Verification Strategy

- Edge cases:
  - Closing the currently active tab → switch to Dashboard
  - Closing an already-closed tab → idempotent (no crash)
  - Page refresh with closed tabs → absent from tab bar, present on dashboard
  - Page refresh with `selectedId` pointing to a closed tab → falls back to Dashboard
  - Server deletes a pipeline → clean up its entry from `closedTabs`
  - New pipeline for a closed family arrives → stays closed (not auto-reopened)
- Testing: markup-presence assertions added to `WebMonitorServerBrowserApiTest.kt`

## Uncertainty Assessment

- Correctness uncertainty: **Low** — well-understood `localStorage` pattern, no server interaction
- Scope uncertainty: **Low** — seed is specific and bounded
- Architecture uncertainty: **Low** — extends existing tab rendering; no new files required

## Open Questions

1. When a new run starts for a family whose tab is closed, should it auto-reopen? → **Stay closed**
2. Should `closeTab()` clear per-tab render caches? → **Yes** (logRenderedCount, graphSigFor, graphRenderGen)
3. Should the × button appear on archived tabs? → **Yes** (orthogonal to archive state)
