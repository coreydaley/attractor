# Sprint 016: Closeable Pipeline Tabs

## Overview

The Monitor view's tab bar accumulates one tab per pipeline family. Over time users who have run many pipelines end up with a crowded bar and no lightweight way to clear it. Today the only escape valve is archiving or deleting the underlying pipeline — heavyweight operations that permanently change state. Users who want a tidy interface without losing their pipeline history shouldn't have to make that trade-off.

This sprint adds a "close" affordance: an × button on each pipeline tab that hides the tab from the bar while leaving the pipeline fully intact. The pipeline card continues to appear on the Dashboard, and clicking it reopens the tab and navigates to it instantly. Closed tabs survive page refreshes: the SPA already uses `localStorage` for `attractor-selected-tab` and `attractor-theme`, and this sprint adds a parallel key `attractor-closed-tabs` (a JSON array of family IDs) that is read on startup and written on every close/reopen action.

The implementation is entirely client-side: new CSS for the × button, a `closedTabs` JS object (acting as a set), a `closeTab(id, event)` function, and targeted edits to `renderTabs()`, `selectTab()`, and `applyUpdate()`. No server routes change. No new Gradle dependencies.

## Use Cases

1. **Declutter without deleting**: A user has run twelve test pipelines. They close nine tabs to keep only the three they're actively watching. All twelve pipelines still appear on the Dashboard.

2. **Reopen a closed pipeline**: A user closes a tab, then realises they need it. They click the pipeline card on the Dashboard — the tab reopens and becomes the active tab.

3. **Survives a page refresh**: After closing several tabs and refreshing the browser, the closed tabs are still gone. The previously active tab is restored (unless it was one of the closed ones, in which case the Dashboard is shown).

4. **New pipeline auto-opens**: A user runs a new pipeline that has never been explicitly closed. It auto-opens in the tab bar as before. A new run for a previously closed family stays closed until the user explicitly reopens it.

5. **Closing the active tab**: A user clicks × on the currently selected tab. The UI smoothly transitions to the Dashboard view.

## Architecture

```
localStorage keys (client-only, no server involvement)
  attractor-selected-tab    existing — active tab ID
  attractor-theme           existing — dark/light
  attractor-closed-tabs     NEW — JSON array of closed family IDs

JS module-scope
  var closedTabs = {};   // object as set: { "family-id": true, ... }

Initialization (near _storedTab, ~line 2759):
  var _closedTabsRaw;
  try { _closedTabsRaw = localStorage.getItem('attractor-closed-tabs'); } catch(e){}
  var closedTabs = {};
  if (_closedTabsRaw) {
    try {
      var _cta = JSON.parse(_closedTabsRaw);
      if (Array.isArray(_cta)) _cta.forEach(function(id){ closedTabs[id] = true; });
    } catch(e) {}
  }
  // If restored active tab is closed, fall back to Dashboard and persist the correction
  if (_storedTab && closedTabs[_storedTab]) {
    selectedId = DASHBOARD_TAB_ID;
    try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){}
  }

saveClosedTabs() — new helper:
  function saveClosedTabs() {
    try { localStorage.setItem('attractor-closed-tabs', JSON.stringify(Object.keys(closedTabs))); } catch(e){}
  }

closeTab(id, event) — new:
  function closeTab(id, event) {
    event.stopPropagation();
    closedTabs[id] = true;
    saveClosedTabs();
    // Free per-tab render caches
    delete logRenderedCount[id];
    delete graphSigFor[id];
    delete graphRenderGen[id];
    if (selectedId === id) {
      selectedId = DASHBOARD_TAB_ID;
      try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){}
      panelBuiltFor = null;
    }
    renderTabs();
    renderMain();
  }

renderTabs() (modified):
  visibleIds filter adds:  && !closedTabs[id]
  Tab HTML adds after badge:
    ' <span class="tab-close" onclick="closeTab(' + JSON.stringify(id) + ', event)" title="Close tab">&times;</span>'
  NOTE: JSON.stringify(id) — not esc(id). esc() does HTML escaping only, not JS string escaping.

selectTab(id) (modified):
  + if (closedTabs[id]) { delete closedTabs[id]; saveClosedTabs(); }
  (existing logic continues unchanged — dashboard card clicks reopen closed tabs for free)

applyUpdate() (modified):
  isNew auto-select guard:  && !closedTabs[key]
  Deletion cleanup:  if (closedTabs[existingKey]) { delete closedTabs[existingKey]; saveClosedTabs(); }
```

## Implementation Plan

### Phase 1: CSS — close button styling (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `.tab-close` and `.tab-close:hover` CSS rules adjacent to the existing `.tab` styles (~line 2201):
  ```css
  .tab-close { margin-left: 4px; opacity: 0.45; font-size: 0.85rem; line-height: 1; padding: 1px 3px; border-radius: 3px; cursor: pointer; flex-shrink: 0; }
  .tab-close:hover { opacity: 1; background: rgba(128,128,128,0.25); }
  ```
- [ ] The existing `.tab` uses `display:flex; align-items:center; gap:6px` — no flex change needed; the close button fits naturally as a flex child

---

### Phase 2: JS state — init, `saveClosedTabs()`, `closeTab()` (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] After the `_storedTab` / `selectedId` initialization (~line 2759), add `closedTabs` init:
  ```javascript
  var _closedTabsRaw;
  try { _closedTabsRaw = localStorage.getItem('attractor-closed-tabs'); } catch(e){}
  var closedTabs = {};
  if (_closedTabsRaw) { try { var _cta = JSON.parse(_closedTabsRaw); if (Array.isArray(_cta)) _cta.forEach(function(id){ closedTabs[id] = true; }); } catch(e){} }
  if (_storedTab && closedTabs[_storedTab]) { selectedId = DASHBOARD_TAB_ID; try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){} }
  ```
- [ ] Add `saveClosedTabs()` helper at the start of the `// ── Tabs ──` section (before `renderTabs`):
  ```javascript
  function saveClosedTabs() {
    try { localStorage.setItem('attractor-closed-tabs', JSON.stringify(Object.keys(closedTabs))); } catch(e){}
  }
  ```
- [ ] Add `closeTab(id, event)` immediately after `saveClosedTabs()`:
  ```javascript
  function closeTab(id, event) {
    event.stopPropagation();
    closedTabs[id] = true;
    saveClosedTabs();
    delete logRenderedCount[id]; delete graphSigFor[id]; delete graphRenderGen[id];
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

### Phase 3: `renderTabs()` — filter closed tabs and add × button (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Extend the `visibleIds` filter to also exclude closed tabs:
  ```javascript
  // Before:
  var visibleIds = ids.filter(function(id) {
    return !pipelines[id].state.archived || id === selectedId;
  });
  // After:
  var visibleIds = ids.filter(function(id) {
    return (!pipelines[id].state.archived || id === selectedId) && !closedTabs[id];
  });
  ```
- [ ] Add the × span after the status badge in the tab HTML generation loop:
  ```javascript
  // Before:
  html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
       +  esc(name)
       +  ' <span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
       +  '</div>';
  // After:
  html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
       +  esc(name)
       +  ' <span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
       +  ' <span class="tab-close" onclick="closeTab(' + JSON.stringify(id) + ', event)" title="Close tab">&times;</span>'
       +  '</div>';
  ```
  **Important**: `JSON.stringify(id)` produces a correctly quoted JS string literal.
  `esc(id)` only does HTML entity escaping and is NOT safe for JavaScript string contexts.

---

### Phase 4: `selectTab()` — remove from closedTabs on select (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] At the top of `selectTab(id)`, before the existing `localStorage.setItem` call, add:
  ```javascript
  if (closedTabs[id]) { delete closedTabs[id]; saveClosedTabs(); }
  ```
  The existing Dashboard card onclick (`onclick="selectTab(key)"`) already calls `selectTab`,
  so reopening a closed pipeline by clicking its card works with no additional wiring.

---

### Phase 5: `applyUpdate()` — guard auto-open, prune on delete (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `closedTabs` guard to the `isNew` auto-select line:
  ```javascript
  // Before:
  if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null) selectedId = key;
  // After:
  if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null && !closedTabs[key]) selectedId = key;
  ```
- [ ] In the deletion loop, also prune orphaned `closedTabs` entries:
  ```javascript
  if (!incoming[existingKey]) {
    delete pipelines[existingKey];
    if (closedTabs[existingKey]) { delete closedTabs[existingKey]; saveClosedTabs(); }
    if (selectedId === existingKey) { selectedId = DASHBOARD_TAB_ID; panelBuiltFor = null; }
  }
  ```

---

### Phase 6: Tests (~15%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify

**Tasks:**
- [ ] Add markup-presence assertions to the existing browser API test (follow the pattern of existing `GET /` body checks):
  - [ ] `GET /` body contains `closeTab` (JS function present)
  - [ ] `GET /` body contains `tab-close` (CSS class present)
  - [ ] `GET /` body contains `attractor-closed-tabs` (localStorage key present)
  - [ ] `GET /` body contains `saveClosedTabs` (helper function present)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `.tab-close` CSS; `closedTabs` init; `saveClosedTabs()` + `closeTab(id,event)` JS; modify `renderTabs()`, `selectTab()`, `applyUpdate()` |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | 4 markup-presence assertions for close tab affordances |

## Definition of Done

### Close Button
- [ ] Each pipeline tab in the Monitor view has an × close button
- [ ] Dashboard tab (⊞ Dashboard) does NOT have a close button
- [ ] Clicking × removes the tab from the tab bar
- [ ] Clicking × does NOT archive or delete the pipeline
- [ ] Clicking × calls `event.stopPropagation()` — does not trigger `selectTab()`
- [ ] `closeTab(id, event)` uses `JSON.stringify(id)` in the onclick attribute (not `esc(id)`)

### Persistence
- [ ] `closedTabs` written to `localStorage['attractor-closed-tabs']` as JSON array
- [ ] Closed tabs absent from tab bar after a page refresh
- [ ] If previously selected tab was closed, Dashboard is shown and its fallback is persisted to `localStorage['attractor-selected-tab']`

### Dashboard Visibility
- [ ] Closed pipelines still appear as cards on the Dashboard
- [ ] Clicking a dashboard card reopens the tab and navigates to it
- [ ] Reopening removes the ID from `closedTabs` and persists the updated set

### State Consistency
- [ ] New pipelines (never explicitly closed) still auto-open in the tab bar
- [ ] New runs for a previously closed family stay closed until user explicitly reopens
- [ ] Server-deleted pipelines have their `closedTabs` entry pruned
- [ ] Closing the currently active tab transitions to Dashboard
- [ ] `closeTab()` deletes `logRenderedCount[id]`, `graphSigFor[id]`, `graphRenderGen[id]`
- [ ] All `localStorage` calls wrapped in `try/catch` — graceful degradation in private browsing

### Tests
- [ ] 4 markup-presence assertions added to `WebMonitorServerBrowserApiTest.kt` and pass
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| × click propagates to `selectTab()`, reopening the tab being closed | High | Medium | `event.stopPropagation()` in `closeTab`; event passed explicitly as `closeTab(id, event)` |
| `esc(id)` used instead of `JSON.stringify(id)` in onclick — XSS or corrupted handler | Medium | Medium | Sprint plan, phase doc, and DoD all specify `JSON.stringify`; enforced in code review |
| `closedTabs` localStorage entry grows as pipelines are deleted | Low | Low | `applyUpdate()` deletion loop prunes `closedTabs[existingKey]` + calls `saveClosedTabs()` |
| `selectedId` points to a closed tab after page refresh | Medium | Low | Init code checks `closedTabs[_storedTab]` and writes corrected `DASHBOARD_TAB_ID` back to localStorage |
| New pipeline for a closed family auto-opens (regression) | Low | Low | `applyUpdate()` `isNew` guard includes `&& !closedTabs[key]` |
| `localStorage` unavailable (private browsing, quota exceeded) | Low | Low | All `localStorage` calls in `try/catch`; tabs closeable for the session even if not persisted |

## Security Considerations

- All `closedTabs` state is local to the browser; no data is sent to the server.
- `JSON.stringify(id)` is used when embedding pipeline IDs into inline onclick attributes — NOT `esc()`, which only HTML-escapes and does not prevent JS string injection through a crafted pipeline ID.
- `localStorage` stores only pipeline family IDs (opaque strings); no sensitive data.

## Dependencies

- Sprint 015 (completed) — current codebase as integration target
- No external dependencies

## Open Questions

1. Should closing a tab in one browser window be reflected in other open windows for the same Attractor server? Proposed: **defer** — requires a `storage` event listener or SSE coordination; out of scope.
2. Should there be a "Close all" button in the tab bar? Proposed: **defer** — the per-tab × is sufficient for MVP.
