# Critique: SPRINT-016-CLAUDE-DRAFT

## Overall Assessment

Claude’s draft is well-aligned with `docs/sprints/drafts/SPRINT-016-INTENT.md`: it stays purely client-side, uses the existing `localStorage` pattern, calls out the key functions to touch (`renderTabs()`, `selectTab()`, `applyUpdate()`), and includes a pruning story for deleted pipelines. The phases are actionable and the Definition of Done matches the intent’s success criteria closely.

The main issues are small but important implementation details: correct “tab key” semantics (family IDs, not per-run IDs), safe embedding of IDs into inline `onclick`, and making the “selected tab is closed” fallback durable by persisting the fallback selection.

## What’s Strong

- Clear mapping from intent → concrete code touchpoints in `src/main/kotlin/attractor/web/WebMonitorServer.kt`.
- Correct choice of persistence mechanism (`localStorage["attractor-closed-tabs"]`) and representation (JSON array).
- Good handling of the tricky UX edge case (closing the active tab → switch to Dashboard).
- Good hygiene callout: prune closed entries when a family disappears from the server snapshot.
- DoD and Risks/Mitigations sections are specific and testable.

## High-Priority Fixes / Clarifications

1. **Tab keys are pipeline family IDs (not “every pipeline run”)**
   - The UI already groups `pipelines` by `familyId` and uses the family key as the “tab ID” in `applyUpdate()`.
   - The plan’s wording “tab for every pipeline run” is misleading and could cause someone to store/close by run ID instead of the family key.
   - Recommendation: consistently refer to the closed set entries as “tab keys / family IDs”.

2. **Do not use `esc(id)` when embedding IDs into JavaScript string literals**
   - `esc()` is HTML escaping (`&`, `<`, `>`), not JavaScript escaping. It does not protect against quotes and can also mutate IDs.
   - In `renderTabs()`, the safe pattern is `JSON.stringify(id)` (or pass via `closeTab(id, event)` with `id` already in a closure; though this codebase uses inline handlers heavily).
   - Recommendation: replace `closeTab('\'' + esc(id) + '\')`-style concatenation with `closeTab(' + JSON.stringify(id) + ', event)` (and correspondingly accept `event` in `closeTab`).

3. **Persist the “selected tab is closed” fallback**
   - The draft sets `selectedId = DASHBOARD_TAB_ID` when `_storedTab` is closed, but it doesn’t also update `localStorage["attractor-selected-tab"]`.
   - This can leave `localStorage` perpetually pointing at a closed tab, making state harder to reason about.
   - Recommendation: when falling back to Dashboard on load, also write `attractor-selected-tab = "__dashboard__"`.

4. **Prefer `closeTab(id, event)` over relying on the global `event`**
   - Inline handlers often expose `event`, but being explicit avoids browser quirks and makes the constraint (“must not trigger `selectTab()`”) easier to verify.
   - Recommendation: `onclick="closeTab(<id>, event)"` and have `closeTab` call `event.stopPropagation()`.

## Medium-Priority Suggestions

- **CSS placement references**: the draft references a specific rule (`.tab.archived-tab.active`) and approximate line numbers; that rule may not exist. Better to anchor the change near the existing `.tab-bar` / `.tab` styles.
- **Testing scope**: creating `CloseableTabsTest.kt` is fine, but the repo already has `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` which is a natural home for a simple “markup presence” guard. Either is acceptable—just keep to the existing pattern and avoid proliferating tiny test files unless there’s a reason.
- **Auto-open semantics**: the draft’s `isNew` guard is correct; consider explicitly noting that “new run for an already-known closed family” should remain closed (even though it’s naturally handled because it won’t be `isNew`).

## Bottom Line

This is a solid sprint draft that matches the intent and is likely straightforward to implement. Tightening the ID handling (`JSON.stringify` instead of `esc`), persisting the dashboard fallback selection, and being explicit about family-key semantics will prevent subtle bugs and keep the implementation consistent with existing `WebMonitorServer` patterns.
