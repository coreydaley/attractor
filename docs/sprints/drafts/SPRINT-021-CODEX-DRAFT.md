# Sprint 021: Surface Project Git History in the Monitor Tab

## Overview

Sprint 020 created a useful audit trail (`WorkspaceGit`) but left it invisible in the UI and API.
Users can only discover git state by opening the workspace on disk and running CLI commands. That
breaks the project-level mental model in the Monitor view, where people expect each project tab to
show current status, history, and diagnostics without leaving the app.

This sprint adds first-class git visibility to the project detail experience and to REST API v1.
The v1 scope is intentionally focused: show at-a-glance repository status (commit count, branch,
last commit hash/date, dirty/clean, tracked file count) plus a short recent commit list. We will
not add diff browsing yet. The objective is fast observability with low implementation risk and no
new dependencies.

The design decision for placement is: add a dedicated **Git** card in the left panel (below
Version History), visible by default with clear empty/unavailable states. This satisfies the seed
request (good options on the project tab) while preserving Graph and Live Log behavior in the right
panel. Data is sourced from a new `/api/v1/projects/{id}/git` endpoint and refreshed on tab open,
manual refresh, and terminal-state transitions.

## Use Cases

1. **At-a-glance run lineage**: A user opens a project tab and immediately sees `18 commits`, last commit `a1b2c3d` from `2026-03-04 14:22`, and a clean workspace.
2. **Recent history inspection**: A user checks the last 5 commit subjects to understand what each iteration/run changed without dropping to shell.
3. **Run-in-progress signal**: During an active run, the card can show `Dirty` when uncommitted workspace changes exist.
4. **Graceful no-git environments**: On hosts without `git` in PATH, the card shows `Git unavailable` instead of errors.
5. **Pre-first-commit behavior**: New projects show a valid empty state (`No commits yet`) with branch/commit fields omitted.
6. **API consumer access**: CLI or external tooling reads the same git metadata through REST without scraping UI HTML.

## Architecture

### API Contract

Add one endpoint:

- `GET /api/v1/projects/{id}/git`

Response shape (proposed):

```json
{
  "available": true,
  "repoExists": true,
  "branch": "main",
  "commitCount": 18,
  "lastCommit": {
    "hash": "a1b2c3d",
    "date": "2026-03-04 14:22:10 -0800",
    "subject": "Run run-1700000000000-8 completed: 4 stages"
  },
  "dirty": false,
  "trackedFiles": 42,
  "recent": [
    {"hash":"a1b2c3d","subject":"Run ..."},
    {"hash":"d4e5f6a","subject":"Run ..."}
  ]
}
```

Degraded responses:
- git missing: `{ "available": false, "repoExists": false, ... }`
- no workspace/repo: `{ "available": true, "repoExists": false, ... }`
- repo with zero commits: `commitCount = 0`, `lastCommit = null`, `recent = []`

### WorkspaceGit Query Surface

Extend `WorkspaceGit` with non-throwing read methods (all no-op safe):

- `fun summary(dir: String, recentLimit: Int = 5): GitSummary`

Where `GitSummary` includes:
- availability/repo existence flags
- branch
- commit count
- last commit tuple (hash/date/subject)
- dirty flag
- tracked file count
- recent commit list

Implementation pattern mirrors existing utility behavior:
- Use `ProcessBuilder`
- `redirectErrorStream(true)`
- timeouts via `waitFor(...)`
- absorb failures and return degraded summary instead of throwing

### UI Placement and Data Flow

In `WebMonitorServer.kt` monitor panel JS:

1. Add Git card scaffold in `buildPanel()` under Version History.
2. Add `fetchProjectGit(id)` that calls `/api/v1/projects/{id}/git`.
3. Render summary badges + recent commit list in `updatePanel()` (or dedicated renderer called by it).
4. Refresh triggers:
   - tab selection/build
   - manual refresh button in card header
   - after project status transitions to terminal

Data flow:

```text
Project tab selected / status updated
  -> browser GET /api/v1/projects/{id}/git
  -> RestApiRouter.handleGetProjectGit()
  -> WorkspaceGit.summary(logsRoot + "/workspace")
  -> JSON payload
  -> Git card render (summary + recent commits + empty/unavailable state)
```

### Scope Cut for v1

Explicitly deferred:
- commit-to-commit diff viewer
- file-level change browser
- separate `/git/log` or `/git/diff` endpoints
- SSE-specific git event type (use polling-on-trigger for now)

## Implementation Plan

### Phase 1: Extend `WorkspaceGit` query capabilities (~30%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` - Modify

**Tasks:**
- [ ] Add `GitSummary`, `GitCommit` data classes (or equivalent internal model).
- [ ] Add `summary(dir: String, recentLimit: Int = 5): GitSummary`.
- [ ] Implement git commands for commit count, branch, last commit, dirty status, tracked files, recent log.
- [ ] Handle edge cases: git unavailable, missing dir, no `.git`, no commits.
- [ ] Keep all methods exception-safe and non-fatal.

---

### Phase 2: Add REST endpoint for project git metadata (~20%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` - Modify

**Tasks:**
- [ ] Add route match for `GET /api/v1/projects/{id}/git`.
- [ ] Implement `handleGetProjectGit(ex, id)` with existing project lookup/error conventions.
- [ ] Resolve workspace path as `${entry.logsRoot}/workspace` (per Sprint 020 layout).
- [ ] Serialize `GitSummary` to JSON response, preserving degraded states.
- [ ] Return `404` for unknown project id; `200` with status payload for known id even if git unavailable.

---

### Phase 3: Render Git card in project detail tab (~35%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - Modify

**Tasks:**
- [ ] Add Git card HTML block in `buildPanel()` below Version History.
- [ ] Add CSS styles consistent with existing dashboard theme (cards, badges, muted empty-state text).
- [ ] Add JS fetch function for `/api/v1/projects/{id}/git`.
- [ ] Add rendering logic for: available summary, no-commits, no-repo, git-unavailable.
- [ ] Add manual refresh button to card.
- [ ] Trigger fetch on tab build and on terminal-state transitions.
- [ ] Ensure view-only hydrated tabs still show Git data (read-only safe).

---

### Phase 4: Tests and docs updates (~15%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` - Modify
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` - Modify
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` - Modify
- `docs/api/rest-v1.md` - Modify

**Tasks:**
- [ ] Add `WorkspaceGitTest` coverage for summary edge cases (git missing/no commits/dirty/clean path).
- [ ] Add `RestApiRouterTest` for `/projects/{id}/git` (404 unknown, 200 known, schema keys present).
- [ ] Add browser API test assertion for Git card scaffold/labels in project panel HTML.
- [ ] Document new endpoint in REST API docs.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Modify | Add git read/query capabilities for UI/API consumption |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Expose git summary via `/api/v1/projects/{id}/git` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add Git card UI, fetch logic, rendering states, and refresh control |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Modify | Validate query behavior for normal and degraded git states |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Validate endpoint behavior and response contract |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | Ensure project panel includes Git section scaffolding |
| `docs/api/rest-v1.md` | Modify | Keep public API reference aligned with implementation |

## Definition of Done

- [ ] Project detail tab includes a visible Git section with summary metadata and recent commits.
- [ ] `GET /api/v1/projects/{id}/git` is implemented and documented.
- [ ] UI handles all degraded states cleanly: git unavailable, missing repo, no commits.
- [ ] Git data refreshes on tab open and after terminal-state updates.
- [ ] No new Gradle dependencies are introduced.
- [ ] Existing monitor behavior (Graph, Live Log, stage list, version history) remains intact.
- [ ] New/updated tests pass for workspace git, router endpoint, and monitor panel scaffold.
- [ ] Build/test pipeline passes with no regressions.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Repeated git subprocess calls impact UI responsiveness | Medium | Medium | Keep endpoint payload small, cap recent commits to 5, trigger fetch only on explicit events |
| Inconsistent workspace path resolution (`logsRoot` vs `logsRoot/workspace`) | Medium | High | Centralize path resolution in router helper and cover with tests |
| UI flicker or stale data during rapid status updates | Medium | Low | Render last-known data first; update asynchronously with loading state |
| Empty or malformed git output in edge repos | Low | Medium | Defensive parsing with default null/zero values |
| Test flakiness on machines without git | Medium | Medium | Follow existing `gitOnPath()` guard pattern in git-dependent tests |

## Security Considerations

- Git commands remain fixed argument arrays through `ProcessBuilder`; no shell interpolation.
- Endpoint is read-only and scoped to server-side resolved workspace paths.
- No new file-write paths or user-controlled command arguments are introduced.
- Response excludes raw file diffs/content in v1, reducing accidental sensitive data exposure.

## Dependencies

- Sprint 020 (`WorkspaceGit` + per-project workspace repo lifecycle) must be present.
- Existing Monitor panel architecture in `WebMonitorServer.kt` remains the integration target.
- Existing REST API v1 router conventions in `RestApiRouter.kt` for route shape/error handling.

## Open Questions

1. Should `recent` include timestamp in each list item for UI display, or keep only hash+subject in v1?
2. Should the Git card auto-refresh while running (timer) or only on explicit triggers?
3. Should branch be hidden when detached HEAD is detected, or shown literally?
4. Should the API include a `workspacePath` field for debugging, or keep that internal?
