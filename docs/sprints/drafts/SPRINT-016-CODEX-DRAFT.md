# Sprint 016: Closeable Pipeline Tabs

## Overview

The Monitor view has a horizontal tab bar with one tab per pipeline family. Over time, users accumulate many historical runs and the tab bar becomes crowded. Today the only way to remove a tab is to archive or delete the underlying pipeline, which is too heavy-handed for simple UI cleanup.

This sprint adds a lightweight “close tab” interaction: every pipeline tab gets an × button that hides it from the tab bar without affecting the pipeline data. The pipeline remains visible as a dashboard card. Clicking the dashboard card reopens the tab (removes it from the closed set) and selects it.

Closed tabs must persist across refreshes. The web UI already uses `localStorage` for `attractor-selected-tab` and `attractor-theme`; this sprint adds a small, bounded key (`attractor-closed-tabs`) that stores the closed family IDs. This is purely client-side state management inside `WebMonitorServer.kt`: no new server routes, no new DB fields, and no new Gradle dependencies.

## Use Cases

1. **Declutter without deleting**: A user closes old pipeline tabs after reviewing results, keeping the bar focused on active runs.
2. **Keep dashboard discoverability**: Even when a tab is closed, the run still appears as a dashboard card for quick re-opening.
3. **Persistence across refresh**: A user closes several tabs, refreshes the page, and the same tabs remain hidden.
4. **Reopen on demand**: A user clicks the dashboard card for a closed family to reopen the tab and immediately view it.
5. **Safe behavior for new runs**: A newly created family auto-opens as a tab (unless the family was explicitly closed before).

## Architecture

### Data model (client-side only)

- `closedTabs`: an in-memory set/map keyed by the tab key (familyId) with `true` values
- `localStorage["attractor-closed-tabs"]`: JSON array of closed tab keys (family IDs)

### Core flows

```
renderTabs()
  ids = Object.keys(pipelines)                  // already family-grouped keys
  visible = ids
    .filter(notArchived || selected)
    .filter(notClosed)                          // NEW
  render "Dashboard" tab (no close button)
  render each tab:
    [name] [status badge] [× close button]     // NEW

closeTab(tabId, event)
  event.stopPropagation()
  closedTabs[tabId] = true
  persistClosedTabs()
  if (selectedId == tabId) select Dashboard
  renderTabs(); renderMain()

selectTab(tabId)
  if closedTabs[tabId] exists:
    delete closedTabs[tabId]                    // NEW: reopen on select
    persistClosedTabs()
  localStorage["attractor-selected-tab"] = tabId
  renderTabs(); renderMain()

applyUpdate()
  group by familyId -> pipelines[familyId]
  auto-open new family only if NOT closed       // NEW
  prune closedTabs entries for deleted families  // NEW
```

## Implementation Plan

### Phase 1: CSS — close affordance styling (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `.tab-close` CSS adjacent to existing `.tab` styles:
  - visible but subtle by default (muted color)
  - hover state
  - `margin-left: auto` (or equivalent) so the × sits at the end of the tab row
  - accessible hit area (padding)

---

### Phase 2: JS state — `closedTabs` + persistence helpers (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add module-scope `closedTabs` initialization from `localStorage["attractor-closed-tabs"]` (JSON array).
- [ ] Add `persistClosedTabs()` helper that writes a compact JSON array of keys.
- [ ] If the persisted `selectedId` is currently closed, fall back to Dashboard on load and persist that selection.

---

### Phase 3: Tabs UI — render × button and wire up close/reopen behavior (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Update `renderTabs()` to:
  - filter out closed tabs (except never hide Dashboard; and selected tab should never remain closed)
  - render an × button for each pipeline tab (not for the Dashboard tab)
  - ensure the × click does not select the tab (`event.stopPropagation()`)
- [ ] Add `closeTab(id, event)` function:
  - idempotent (closing an already-closed tab is safe)
  - if closing the active tab: switch to Dashboard
  - update `localStorage["attractor-closed-tabs"]`
- [ ] Update `selectTab(id)` to:
  - remove `id` from `closedTabs` before selecting (reopen-on-select)
  - persist the updated closed set
- [ ] Ensure the dashboard cards’ existing `onclick="selectTab(id)"` reopens closed tabs by virtue of the `selectTab` change.

---

### Phase 4: Update loop — auto-open rules + pruning deleted entries (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Update `applyUpdate()` “auto-open new family” logic to skip keys present in `closedTabs`.
- [ ] Prune `closedTabs` entries when a family disappears from the server snapshot (deleted runs) to prevent unbounded growth.

---

### Phase 5: Tests — markup presence regression guard (~10%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify

**Tasks:**
- [ ] Add a new test that `GET /` contains:
  - `.tab-close` (CSS hook)
  - `closeTab(` (JS function)
  - `attractor-closed-tabs` (localStorage key)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `.tab-close` CSS, `closedTabs` localStorage state, `closeTab()` JS, update `renderTabs()`, `selectTab()`, and `applyUpdate()` |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | Markup-presence test ensuring closeable tab affordances exist |

## Definition of Done

- [ ] Every non-Dashboard pipeline tab shows a visible × close button.
- [ ] Clicking × hides the tab from the tab bar without archiving/deleting the pipeline.
- [ ] Closed tabs persist across refresh via `localStorage["attractor-closed-tabs"]`.
- [ ] Closing the active tab switches selection to Dashboard.
- [ ] Closed pipelines remain visible as dashboard cards.
- [ ] Clicking a dashboard card for a closed family reopens the tab and selects it.
- [ ] If `localStorage["attractor-selected-tab"]` points at a closed tab, the UI falls back to Dashboard on refresh.
- [ ] New pipeline families still auto-open as tabs unless explicitly closed previously.
- [ ] Deleted families are pruned from `closedTabs` to prevent localStorage growth.
- [ ] All tests pass.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Close button accidentally selects tab | Medium | Low | Ensure `event.stopPropagation()` in `closeTab` handler; keep handler signature `closeTab(id,event)` |
| Corrupted `localStorage` JSON breaks UI | Low | Medium | Wrap parse in `try/catch` and fall back to empty set |
| Confusion about “close” vs “archive” | Medium | Low | Keep archive/unarchive/delete controls in the pipeline panel unchanged; close only affects tab bar |
| `closedTabs` grows forever | Low | Low | Prune entries for deleted families in `applyUpdate()` |

## Security Considerations

- The feature stores only pipeline family IDs in `localStorage`; no secrets are introduced.
- Ensure tab rendering continues to escape pipeline names (`esc(name)`) and avoids injecting untrusted content into handler attributes.

## Dependencies

- None (pure client-side changes in `WebMonitorServer.kt`)

## Open Questions

1. Should closing a tab also clear per-tab caches like `logRenderedCount`, `graphSigFor`, and `graphRenderGen`, or is it acceptable to leave them (they’ll be overwritten on next open)?
2. When a new run starts for a family whose tab is closed, should it remain closed until a user reopens it, or should active/running runs force-reopen the tab?
3. Should the × button appear on archived tabs (currently visually muted), or should archived tabs be non-closeable?
| Migration safety across DBs | Medium | High | Prefer additive migrations; ignore “already exists”; document required schema version |
| H2 compatibility gaps | Medium | Medium | Keep tests focused on supported syntax; optionally add an opt-in Testcontainers profile later |
| Credential leakage in logs | Low | High | Redact passwords; provide safe connection description helpers |
| Jar size increases | Certain | Low/Medium | Accept as tradeoff; document; optionally offer “no-drivers” build later |

## Security Considerations

- Never log `ATTRACTOR_DB_PASSWORD` or password components from URLs.
- Prefer prepared statements everywhere (no string-interpolated SQL values).
- Document TLS/SSL configuration via JDBC params (e.g. `sslmode=require`) and warn about plaintext connections.
- Treat DB connection failures as startup-fatal with actionable error output (avoid running partially initialized).

## Dependencies

- New runtime dependencies: MySQL and PostgreSQL JDBC drivers.
- Builds currently bundle `runtimeClasspath` into the JAR; confirm the new drivers are included in both server and CLI distributions as intended.

## Open Questions

1. Should we accept simplified non-JDBC URLs (`postgres://...`) or only raw JDBC URLs?
2. For piecewise config, should we allow user/pass overrides even when `ATTRACTOR_DB_URL` is set?
3. What is the desired migration policy for non-SQLite backends (best-effort additive vs. strict schema versioning)?
4. Do we want an optional Testcontainers-based test profile for true MySQL/Postgres validation, or is H2 compatibility mode sufficient for this sprint?
