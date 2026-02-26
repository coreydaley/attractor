# Sprint 004 Merge Notes

## Claude Draft Strengths

1. Comprehensive CSS plan — `.dashboard-layout`, `.dashboard-grid`, `.dash-card`, `.dash-elapsed`, `.dash-empty` all specified with concrete styling intent.
2. Strong DoD with observable behavioral checkboxes (timer lifecycle, empty state, nav behavior).
3. Correct identification of all JS seams: `renderTabs()`, `selectTab()`, `renderMain()`, `applyUpdate()`, `buildPanel()`.
4. Explicit sentinel `'__dashboard__'` in `selectedId` — clean extension of existing model.
5. Timer lifecycle awareness: mutual clearing between `dashboardTimer` and `elapsedTimer`.

## Codex Draft Strengths

1. **`DASHBOARD_TAB_ID` constant** — using a named constant eliminates literal typo risk across multiple HTML string concatenations. Claude draft had a sentinel typo (`'__dashboard'` missing trailing `__`) in one snippet.
2. **Single rendering source of truth in `renderMain()`** — Codex correctly flags that having `selectTab('__dashboard__')` call `renderDashboard()` directly *AND* adding a `renderMain()` branch creates two competing entry points. Route all dashboard rendering through `renderMain()`.
3. **`elapsed(state)` helper reuse** — Codex correctly flags that `tickDashboardElapsed()` should use the existing `elapsed(state)` function rather than a `data-started-at` only approach. This handles paused pipelines (where `finishedAt` may be set) correctly.
4. **Explicit `startDashboardTimer()` / `stopDashboardTimer()` helpers** — cleaner encapsulation than inline `setInterval`/`clearInterval` scattered across multiple functions.
5. **Stage fallback labels**: `'Paused'` and `'Waiting'` rather than falling back to `'retrying'` stage — avoids misleading "current stage" display for non-running pipelines.

## Valid Critiques Accepted

1. **Sentinel typo risk** — introduce `var DASHBOARD_TAB_ID = '__dashboard__';` constant; replace all string literals in HTML generation and JS logic.
2. **Single rendering entry point** — `selectTab()` mutates `selectedId`, calls `renderTabs()` and `renderMain()`; `renderMain()` branches on `selectedId === DASHBOARD_TAB_ID`; `renderDashboard()` is not called directly from `selectTab()`.
3. **`elapsed(state)` reuse** — `tickDashboardElapsed()` reads elapsed from pipeline `state` (same as detail panel), not from `data-started-at` attribute parsing.
4. **Stage fallback simplification** — remove `'retrying'` from fallback; use `'Paused'` for paused runs, `'Waiting…'` for running-but-no-stage-found.

## Critiques Rejected (with reasoning)

- **Build command convention** — Codex suggests `./gradlew jar`. `MEMORY.md` states `./gradlew` fails with Java 25 as the default JDK; the correct invocation is `export JAVA_HOME=.../openjdk@21/... && ~/.gradle/wrapper/.../gradle-8.7/bin/gradle -p . jar`. Rejected; retained existing DoD build command.

## Interview Refinements Applied

1. **Show all non-archived pipelines** (not just running/paused): Dashboard cards show running, paused, completed, failed, and cancelled pipelines. Elapsed counter only ticks for running/paused.
2. **Auto-navigate on new pipeline**: `if (isNew && selectedId === DASHBOARD_TAB_ID) selectedId = p.id` — matches existing UX of landing on the pipeline you just started.
3. **Dashboard is default landing view**: initialize `selectedId = DASHBOARD_TAB_ID` instead of `null`; page always starts on Dashboard.

## Final Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Sentinel ID | `var DASHBOARD_TAB_ID = '__dashboard__';` | Named constant prevents typos; consistent across JS and HTML |
| Render routing | Exclusively through `renderMain()` | Single source of truth; no duplicate entry points |
| Elapsed strategy | Reuse `elapsed(state)` helper | Handles paused (finishedAt present) correctly |
| Pipelines shown | All non-archived | User interview decision |
| Stage fallback | 'Paused' / 'Waiting…' | Explicit, accurate labels for non-running states |
| Timer helpers | `startDashboardTimer()` / `stopDashboardTimer()` | Encapsulation; easy to audit call sites |
| Auto-nav on submit | Yes: `if (isNew && selectedId === DASHBOARD_TAB_ID)` | Preserve existing "land on new pipeline" UX |
| Default view | Dashboard (`selectedId = DASHBOARD_TAB_ID` initial) | User interview decision |
| Tab ordering | Dashboard always leftmost | Pinned static tab |
| Tab icon | 📊 `&#128202;` | Distinguishes Dashboard from pipeline run tabs |
