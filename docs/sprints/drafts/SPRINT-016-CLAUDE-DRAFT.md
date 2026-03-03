# Sprint 016: Closeable Pipeline Tabs

## Overview

The Monitor view's tab bar accumulates a tab for every pipeline run. Today the only escape
valve is archiving or deleting the pipeline — heavyweight operations that permanently change
state. Users who have run many pipelines and want a tidy interface shouldn't be forced to
archive work they may still want to reference. This sprint adds a lightweight "close" action:
an × button on each pipeline tab that hides the tab from the bar while leaving the pipeline
fully intact. The pipeline card continues to appear on the Dashboard, and clicking it reopens
the tab instantly.

"Open tabs persist across screen refreshes" is the second key requirement. The SPA already uses
`localStorage` for the active tab ID and the UI theme. We extend that pattern with a new key
`attractor-closed-tabs` — a JSON array of pipeline family IDs — loaded at startup and written on
every close/reopen action. All tab state is therefore durable across refreshes without any
server round-trip.

The implementation is entirely client-side: new CSS for the × button, a `closedTabs` JS object
(acting as a set), a `closeTab(id)` function, and targeted edits to `renderTabs()`,
`selectTab()`, and `applyUpdate()`. No server routes change. No new Gradle dependencies.
One Kotest test confirms the markup is present.

## Use Cases

1. **Declutter without deleting**: A user has run twelve test pipelines. They close nine tabs to
   keep only the three they're actively watching. All twelve pipelines still appear on the Dashboard.

2. **Reopen a closed pipeline**: A user closes a tab, then realises they need it. They click the
   pipeline card on the Dashboard — the tab reopens and becomes the active tab.

3. **Survives a page refresh**: After closing several tabs and refreshing the browser, the closed
   tabs are still gone. The active tab from before the refresh is restored (unless it was one of
   the closed ones, in which case the Dashboard is shown).

4. **New pipeline auto-opens**: A user runs a new pipeline. It has never been closed, so it
   auto-opens in the tab bar as before.

5. **Closing the active tab**: A user clicks × on the currently selected tab. The UI smoothly
   transitions to the Dashboard view.

## Architecture

```
localStorage keys (client-only, no server involvement)
  attractor-selected-tab        existing — active tab ID
  attractor-theme               existing — dark/light
  attractor-closed-tabs         NEW — JSON array of closed family IDs

JS module-scope vars
  var closedTabs = {};          NEW — object used as set: { "run-id": true, ... }
                                  Loaded from localStorage on page init.

Initialization (added near _storedTab lines ~2759):
  var _closedTabsRaw;
  try { _closedTabsRaw = localStorage.getItem('attractor-closed-tabs'); } catch(e){}
  var closedTabs = {};
  if (_closedTabsRaw) {
    try {
      var arr = JSON.parse(_closedTabsRaw);
      if (Array.isArray(arr)) arr.forEach(function(id){ closedTabs[id] = true; });
    } catch(e){}
  }
  // Adjust selectedId: if the stored active tab is closed, fall back to dashboard
  if (_storedTab && closedTabs[_storedTab]) selectedId = DASHBOARD_TAB_ID;

saveClosedTabs() helper (new):
  function saveClosedTabs() {
    try { localStorage.setItem('attractor-closed-tabs', JSON.stringify(Object.keys(closedTabs))); } catch(e){}
  }

closeTab(id) (new):
  function closeTab(id) {
    closedTabs[id] = true;
    saveClosedTabs();
    if (selectedId === id) {
      selectedId = DASHBOARD_TAB_ID;
      localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID);
      panelBuiltFor = null;
    }
    renderTabs();
    renderMain();
  }

selectTab(id) (modified):
  + if (closedTabs[id]) { delete closedTabs[id]; saveClosedTabs(); }
  (existing logic follows unchanged)

renderTabs() (modified):
  visibleIds filter: add  && !closedTabs[id]  (in addition to existing archived check)
  Tab HTML: add × button after the badge:
    + '<span class="tab-close" onclick="event.stopPropagation();closeTab(\'' + esc(id) + '\')" title="Close tab">&times;</span>'

applyUpdate() (modified):
  isNew auto-select: add  && !closedTabs[key]  guard
  Deletion cleanup: also delete closedTabs[existingKey] + saveClosedTabs()

CSS additions (near .tab styles ~line 2201):
  .tab-close { margin-left: 4px; opacity: 0.45; font-size: 0.85rem; line-height: 1;
               padding: 1px 3px; border-radius: 3px; cursor: pointer; flex-shrink: 0; }
  .tab-close:hover { opacity: 1; background: rgba(128,128,128,0.25); }

Data flow — close tab:
  user clicks ×
    → event.stopPropagation() (prevents selectTab)
    → closeTab(id)
      → closedTabs[id] = true; saveClosedTabs()
      → if active: selectedId = DASHBOARD_TAB_ID
      → renderTabs() (tab disappears)
      → renderMain() (if was active: show dashboard)

Data flow — reopen via dashboard:
  user clicks dashboard card → selectTab(id)
    → delete closedTabs[id]; saveClosedTabs()
    → existing selectTab logic (navigate to pipeline)
    → renderTabs() (tab reappears, active)

Data flow — page refresh:
  JS init → load closedTabs from localStorage
           → if _storedTab in closedTabs → selectedId = DASHBOARD_TAB_ID
  SSE/fetch → applyUpdate() → renderTabs() → closed tabs filtered out
```

## Implementation Plan

### Phase 1: CSS for the close button (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `.tab-close` and `.tab-close:hover` CSS rules immediately after the `.tab.archived-tab.active` rule (~line 2358):
  ```css
  .tab-close { margin-left: 4px; opacity: 0.45; font-size: 0.85rem; line-height: 1; padding: 1px 3px; border-radius: 3px; cursor: pointer; flex-shrink: 0; }
  .tab-close:hover { opacity: 1; background: rgba(128,128,128,0.25); }
  ```
- [ ] Verify the tab layout (`display:flex; align-items:center; gap:6px`) already accommodates a flex child — it does (`.tab` already has `display:flex; align-items:center; gap:6px` at line 2201)

---

### Phase 2: JS state — init, helpers, closeTab (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] After the `_storedTab` / `selectedId` initialization lines (~2759–2760), add `closedTabs` init:
  ```javascript
  var _closedTabsRaw;
  try { _closedTabsRaw = localStorage.getItem('attractor-closed-tabs'); } catch(e){}
  var closedTabs = {};
  if (_closedTabsRaw) { try { var _cta = JSON.parse(_closedTabsRaw); if (Array.isArray(_cta)) _cta.forEach(function(id){ closedTabs[id] = true; }); } catch(e){} }
  if (_storedTab && closedTabs[_storedTab]) { selectedId = DASHBOARD_TAB_ID; }
  ```
- [ ] Add `saveClosedTabs()` helper near the top of the `// ── Tabs ──` section:
  ```javascript
  function saveClosedTabs() {
    try { localStorage.setItem('attractor-closed-tabs', JSON.stringify(Object.keys(closedTabs))); } catch(e){}
  }
  ```
- [ ] Add `closeTab(id)` function immediately after `saveClosedTabs()`:
  ```javascript
  function closeTab(id) {
    closedTabs[id] = true;
    saveClosedTabs();
    if (selectedId === id) {
      selectedId = DASHBOARD_TAB_ID;
      try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){}
      panelBuiltFor = null;
    }
    renderTabs();
    renderMain();
  }
  ```

---

### Phase 3: renderTabs() — filter closed, add × button (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] In `renderTabs()`, extend the `visibleIds` filter to also exclude closed tabs:
  ```javascript
  // before:
  var visibleIds = ids.filter(function(id) {
    return !pipelines[id].state.archived || id === selectedId;
  });
  // after:
  var visibleIds = ids.filter(function(id) {
    return (!pipelines[id].state.archived || id === selectedId) && !closedTabs[id];
  });
  ```
- [ ] In the tab HTML generation loop, add the × span after the status badge:
  ```javascript
  // before:
  html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
       +  esc(name)
       +  ' <span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
       +  '</div>';
  // after:
  html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
       +  esc(name)
       +  ' <span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
       +  ' <span class="tab-close" onclick="event.stopPropagation();closeTab(\'' + esc(id) + '\')" title="Close tab">&times;</span>'
       +  '</div>';
  ```

---

### Phase 4: selectTab() — reopen on select (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] At the top of `selectTab(id)`, before setting `localStorage` and `selectedId`, add:
  ```javascript
  if (closedTabs[id]) { delete closedTabs[id]; saveClosedTabs(); }
  ```
  This ensures that navigating to a closed pipeline (e.g., via dashboard card click) also
  removes it from the closed set and makes the tab reappear.

---

### Phase 5: applyUpdate() — guard auto-open, prune on delete (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] In `applyUpdate()`, find the `isNew` auto-select line and add the `closedTabs` guard:
  ```javascript
  // before:
  if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null) selectedId = key;
  // after:
  if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null && !closedTabs[key]) selectedId = key;
  ```
- [ ] In the deletion loop (where `delete pipelines[existingKey]` is called), also prune from `closedTabs`:
  ```javascript
  if (!incoming[existingKey]) {
    delete pipelines[existingKey];
    if (closedTabs[existingKey]) { delete closedTabs[existingKey]; saveClosedTabs(); }
    if (selectedId === existingKey) { selectedId = DASHBOARD_TAB_ID; panelBuiltFor = null; }
  }
  ```

---

### Phase 6: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/web/CloseableTabsTest.kt` — Create

**Tasks:**
- [ ] Kotest `FunSpec` using a real `WebMonitorServer` on an ephemeral port (follow existing test patterns):
  - [ ] `GET /` → 200 OK
  - [ ] Body contains `closeTab` (JS function present)
  - [ ] Body contains `tab-close` (CSS class present)
  - [ ] Body contains `attractor-closed-tabs` (localStorage key present)
  - [ ] Body contains `saveClosedTabs` (helper function present)
  - [ ] Regression: `GET /api/v1/pipelines` → 200 (REST API not broken)
  - [ ] Regression: `GET /docs` → 200 (docs endpoint not broken)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `.tab-close` CSS; `closedTabs` init; `saveClosedTabs()` + `closeTab()` JS; modify `renderTabs()`, `selectTab()`, `applyUpdate()` |
| `src/test/kotlin/attractor/web/CloseableTabsTest.kt` | Create | Markup-presence regression test |

## Definition of Done

### Close Button
- [ ] Each pipeline tab in the Monitor view has an × close button
- [ ] Dashboard tab (⊞ Dashboard) does NOT have a close button
- [ ] Clicking × removes the tab from the tab bar
- [ ] Clicking × does NOT archive or delete the pipeline
- [ ] `event.stopPropagation()` prevents × click from triggering `selectTab()`

### Persistence
- [ ] `closedTabs` is stored in `localStorage` under key `attractor-closed-tabs` as a JSON array
- [ ] Closed tabs are absent from the tab bar after a page refresh
- [ ] If the previously selected tab was closed, the Dashboard is shown on refresh

### Dashboard Visibility
- [ ] Closed pipelines still appear as cards on the Dashboard
- [ ] Clicking a closed pipeline's dashboard card reopens its tab and navigates to it
- [ ] Reopening removes the ID from `closedTabs` and updates `localStorage`

### State Consistency
- [ ] New pipelines (never explicitly closed) still auto-open in the tab bar
- [ ] Pipelines deleted from the server have their `closedTabs` entry pruned
- [ ] Closing the currently active tab transitions to the Dashboard view

### Tests
- [ ] `CloseableTabsTest` passes all assertions
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| × click propagates to `selectTab()`, reopening the tab being closed | High | Medium | `event.stopPropagation()` in the onclick handler prevents the outer div's onclick from firing |
| `closedTabs` localStorage entry grows unboundedly as pipelines are deleted | Low | Low | `applyUpdate()` deletion loop prunes `closedTabs[existingKey]` when pipeline is removed from server |
| `selectedId` points to a closed tab after page refresh | Medium | Low | Init code checks `if (_storedTab && closedTabs[_storedTab]) selectedId = DASHBOARD_TAB_ID` |
| New pipeline for a family the user closed auto-reopens the tab | Low | Low | `applyUpdate()` isNew auto-select guard: `&& !closedTabs[key]` |
| Close button visually clutters the tab | Low | Low | `.tab-close` uses `opacity:0.45` at rest, full opacity only on hover; button is compact (1-char `×`) |
| `localStorage` unavailable (private browsing, quota exceeded) | Low | Low | All `localStorage` calls wrapped in `try/catch`; graceful degradation (tabs not persisted but still closeable for the session) |

## Security Considerations

- All `closedTabs` state is local to the browser. No user data is sent to the server.
- `esc(id)` is used when embedding the pipeline ID in the `onclick` attribute to prevent XSS.
- `localStorage` stores only pipeline family IDs (opaque strings); no sensitive data.

## Dependencies

- Sprint 015 (completed) — current codebase as integration target
- No external dependencies

## Open Questions

1. Should closing a tab also clear its `logRenderedCount[id]`, `graphSigFor[id]`, and
   `graphRenderGen[id]` entries? These are small and naturally evicted on the next render, so
   leaving them is safe but slightly wasteful. Proposed: **leave them** — no memory impact at
   typical pipeline counts.
2. When a new run starts for a family whose tab is closed (e.g., user hits "Re-run" from the
   REST API), should the tab auto-reopen? The current design keeps it closed. Proposed:
   **keep closed** — the user explicitly closed it; they can click the Dashboard card to reopen.
3. Should archived tabs also have an × close button? Currently they have `opacity:0.55` and the
   close button would be dimly visible. Proposed: **yes** — orthogonal to archive state; the ×
   should work on both regular and archived tabs.
