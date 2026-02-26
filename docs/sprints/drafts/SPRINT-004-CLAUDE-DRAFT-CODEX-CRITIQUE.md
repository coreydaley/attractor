# Critique: SPRINT-004-CLAUDE-DRAFT

## Overall Assessment

Claude’s draft is close to the intent and correctly scopes work to `WebMonitorServer.kt` with a dashboard sentinel model. The structure is solid, but there are a few correctness and fidelity gaps that would cause implementation bugs or UX drift if followed literally.

## High-Priority Issues

1. **Sentinel typo in tab click handler would break dashboard selection**
- In Phase 2 snippet, Dashboard tab uses `selectTab('__dashboard')` (missing trailing `__`).
- Elsewhere the draft consistently uses `'__dashboard__'`.
- Recommendation: standardize one constant (`DASHBOARD_TAB_ID`) and use it everywhere in generated HTML/JS.

2. **`selectTab()` plan introduces competing render paths**
- Phase 3 says `selectTab('__dashboard__')` should call `renderDashboard()` directly, while also adding a `renderMain()` dashboard branch.
- Two rendering entry points for the same UI will drift and complicate timer lifecycle.
- Recommendation: keep one routing source of truth in `renderMain()`; `selectTab()` should just mutate state + call `renderTabs()` and `renderMain()`.

3. **Elapsed-timer algorithm is underspecified for paused runs and existing state fields**
- Proposed `tickDashboardElapsed()` updates from `data-started-at` only, but current elapsed logic already depends on full state (`startedAt` + optional `finishedAt`).
- This can over-tick paused runs if `finishedAt` is present/expected.
- Recommendation: compute elapsed from pipeline state via existing `elapsed(state)` helper rather than a started-at-only formatter.

## Medium-Priority Issues

1. **Default-selection behavior is presented as decided but is still an intent-level open question**
- Draft hard-commits to auto-navigating to the first new pipeline (`if isNew && selectedId === '__dashboard__'`).
- Intent explicitly lists this behavior as unresolved.
- Recommendation: keep this as an explicit sprint decision point in Open Questions or pick a default and call out the tradeoff clearly.

2. **Dashboard stage fallback includes `retrying`, which may not represent “currently executing”**
- Intent asks for current stage; retrying fallback can show stale stage context.
- Recommendation: prefer `running` stage only; for paused/no-running state use explicit labels (`Paused`, `Waiting`).

3. **Build command convention is copied from prior docs but may not match repo defaults**
- Draft uses hardcoded `JAVA_HOME` + deep Gradle cache path.
- Recommendation: consider standardizing DoD build checks to wrapper commands (`./gradlew jar`) unless repo policy requires the explicit path.

## Low-Priority Notes

1. The draft’s CSS and card layout details are practical and likely implementable with low risk.
2. Timer cleanup awareness is good; just consolidate lifecycle control in one render path.
3. Security notes and scope boundary (client-only, one file) are accurate.

## What Claude’s Draft Does Well

1. Correctly aligns with the sprint seed: static left-pinned Dashboard tab and card-based active-run summary.
2. Targets the exact JS seams that matter (`renderTabs`, `selectTab`, `renderMain`, `applyUpdate`, timer handling).
3. Includes strong DoD bullets tied to observable behavior.
4. Keeps scope constrained and avoids unnecessary backend changes.

## Recommended Revision Direction

1. Introduce `const DASHBOARD_TAB_ID = '__dashboard__'` and remove all literal drift.
2. Route dashboard rendering exclusively through `renderMain()`.
3. Reuse `elapsed(state)` for dashboard counters to match existing elapsed semantics.
4. Resolve or explicitly preserve the open UX decision on auto-navigation from Dashboard to new pipeline tabs.
5. Simplify stage fallback to avoid showing misleading “current stage” values.
