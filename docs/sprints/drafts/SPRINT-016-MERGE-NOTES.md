# Sprint 016 Merge Notes

## Claude Draft Strengths

- Detailed 6-phase plan with exact code snippets — easy to hand off to an implementer
- Correct identification of all five JS touchpoints: `closedTabs` init, `saveClosedTabs()`, `closeTab()`, `renderTabs()`, `selectTab()`, `applyUpdate()`
- Memory cleanup on close (`logRenderedCount`, `graphSigFor`, `graphRenderGen`) — captured from interview
- Full `try/catch` wrapping of all `localStorage` calls — graceful degradation
- Detailed Risks & Mitigations section with `event.stopPropagation()` explicitly called out
- Comprehensive Definition of Done

## Codex Draft Strengths

- Clean architecture diagram showing data flows (`renderTabs`, `closeTab`, `selectTab`, `applyUpdate`)
- Recommends adding tests to existing `WebMonitorServerBrowserApiTest.kt` instead of creating a new file — avoids test file proliferation
- Explicit `closeTab(id, event)` function signature — more readable and browser-safe
- Concise, well-structured phases that are easier to scan

## Valid Critiques Accepted

- **Use `JSON.stringify(id)` not `esc(id)` in inline onclick handlers**: `esc()` does HTML escaping (`&`, `<`, `>`), not JavaScript string escaping. Pipeline IDs should not contain these chars, but `JSON.stringify` is the correct and safe tool for embedding JS values into inline handlers. Accepted.
- **Persist the dashboard fallback selection to localStorage**: If `_storedTab` is closed, not only set `selectedId = DASHBOARD_TAB_ID` but also write it back to `localStorage['attractor-selected-tab']` so state is consistent. Accepted.
- **Explicit `closeTab(id, event)` signature**: Pass `event` explicitly in the onclick attribute rather than relying on the global `event` object. More predictable across browsers. Accepted.
- **Add markup-presence test to existing `WebMonitorServerBrowserApiTest.kt`**: No need to create `CloseableTabsTest.kt` — a few assertions fit naturally in the existing browser API test. Accepted.

## Critiques Rejected

- **Codex Codex draft leaked multi-DB content** (Risk table rows about "Migration safety", "H2 compatibility", "Credential leakage", "Jar size"). These were leftover from a previous sprint's draft and are irrelevant to this sprint. Discarded.
- **Codex suggested `margin-left: auto` for close button positioning**: The existing `.tab` already uses `display:flex; align-items:center; gap:6px`, so `margin-left: 4px` on `.tab-close` is cleaner and consistent with the existing layout. Keeping Claude's CSS.

## Interview Refinements Applied

- **New runs for closed families stay closed**: If the user closed tab X and a new run starts for that family, the tab stays hidden until the user explicitly clicks the Dashboard card. The `applyUpdate()` `isNew && !closedTabs[key]` guard handles this. Applied.
- **Memory cleanup on close**: `closeTab()` also deletes `logRenderedCount[id]`, `graphSigFor[id]`, and `graphRenderGen[id]` when closing a tab. Applied.

## Final Decisions

- **Storage key**: `attractor-closed-tabs` (JSON array of family IDs)
- **Tab key semantics**: Family IDs (same keys used by `pipelines{}` object), not per-run IDs
- **Close button onclick**: `onclick="closeTab(' + JSON.stringify(id) + ', event)"` — explicit event arg, JSON.stringify for safety
- **JS function signature**: `function closeTab(id, event) { event.stopPropagation(); ... }`
- **Test location**: Extend `WebMonitorServerBrowserApiTest.kt` with 3-4 markup-presence assertions; no new test file
- **Fallback selection**: When `_storedTab` is in `closedTabs`, also write `DASHBOARD_TAB_ID` back to localStorage
- **Memory cleanup**: `delete logRenderedCount[id]; delete graphSigFor[id]; delete graphRenderGen[id];` in `closeTab()`
