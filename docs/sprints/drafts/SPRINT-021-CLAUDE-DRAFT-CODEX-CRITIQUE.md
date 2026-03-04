# Critique: SPRINT-021-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is directionally strong: it keeps the core architecture layered (`WorkspaceGit` ->
REST -> monitor UI), references the right files, and proposes concrete UI behavior on the project
panel.

The main issues are scope pressure and a few correctness/operability details that would make v1
harder to ship safely than necessary.

## High-Priority Fixes

1. **V1 scope is too broad due to diff API + diff viewer**
- Draft adds both `GET /api/v1/projects/{id}/git/diff` and an inline diff renderer.
- Intent asks first for what should be surfaced on the project tab; diff is explicitly an open
  question and the highest-complexity option.
- Why this matters: adding diff multiplies parsing/security/perf work (hash validation, output
  truncation, rendering large payloads) and can delay delivery of the core summary experience.
- Recommended correction:
  - Keep v1 to summary + recent commits only.
  - Defer diff to follow-up sprint once summary endpoint/UI is stable and adopted.

2. **`loadGitInfo(id)` inside `updatePanel()` will over-fetch heavily**
- `updatePanel()` runs very frequently during SSE updates; placing network fetch there can trigger
  repeated requests while a run is active.
- Why this matters: request churn, race conditions, and stale-over-new render ordering become likely
  under rapid state updates.
- Recommended correction:
  - Fetch on explicit triggers only: tab open, manual refresh click, and terminal transition.
  - Cache last git payload per project and render from cache during normal `updatePanel()` cycles.

3. **Response semantics conflate environment capability with repo state**
- Draft DoD states `info()` should return `available=false` when `.git` does not exist.
- That collapses two different conditions: `git binary unavailable` vs `workspace repo missing`.
- Why this matters: UI cannot render accurate degraded states (e.g., “git installed but no repo
  yet”).
- Recommended correction:
  - Separate fields: `available` (git executable present), `repoExists` (workspace has `.git`).
  - Keep 200 responses for known projects and represent state in payload.

## Medium-Priority Improvements

1. **Hidden git bar on unavailable state conflicts with graceful degradation goal**
- Use case says hide the bar entirely when `available=false`.
- Better UX is to keep a visible Git section with explicit status text (`Git unavailable`), so the
  absence of data is explained instead of disappearing silently.

2. **800ms terminal refresh delay is brittle and likely unnecessary**
- Draft assumes commit may lag SSE update and hardcodes `setTimeout(..., 800)`.
- In current runner flow, commit is invoked in terminal event handling before `onUpdate()`.
- Prefer immediate fetch on terminal transition; if stale once, retry once instead of fixed delay.

3. **`GET /git/diff` 404 behavior tied to blank `logsRoot` is too strict**
- For known project IDs, blank workspace state should usually return a normal empty/degraded
  payload, not not-found semantics.
- Keep 404 for unknown project only; use 200 with empty/degraded git data otherwise.

## What’s Strong

- Correctly targets current integration points (`WorkspaceGit.kt`, `RestApiRouter.kt`,
  `WebMonitorServer.kt`).
- Good emphasis on no new dependencies and `ProcessBuilder` parity with existing code.
- Useful UI placement thought process (compact summary with expandable details).
- Test plan is concrete and maps to existing test files.

## Bottom Line

This draft is close, but should be narrowed for a reliable sprint: ship summary + recent commits via
one endpoint and a lightweight Git panel first. Removing diff from v1 and tightening refresh/state
semantics will materially reduce risk while still fully addressing the sprint intent.
