# Sprint 020: Workspace Git Versioning for Project Families

## Overview

Attractor already persists all run metadata in SQLite, but filesystem artifacts are still split across
workspace directories when users iterate. The core issue is naming continuity: reruns preserve the
same workspace, while `/iterations` currently creates a new run with a fresh random display name,
which produces sibling directories under `logs/` for what users perceive as one project.

This sprint introduces a workspace-level git history and enforces one canonical workspace directory
per project family display name. On first workspace use, Attractor initializes a git repo in that
directory. On terminal run events, Attractor commits workspace changes when there is an actual diff.
This creates an inspectable local history per project (`git log`, `git diff`) without changing
runtime behavior when `git` is unavailable.

Implementation is intentionally narrow:
1. Add a `WorkspaceGit` utility that shells out via `ProcessBuilder` (no new dependencies).
2. Hook init/commit into `ProjectRunner` lifecycle.
3. Fix `handleCreateIteration()` to pass `displayNameOverride = entry.displayName` so iterations
   keep writing to the parent workspace rather than creating siblings.

## Use Cases

1. **Single workspace continuity**: A user creates project `story-writer`; all reruns and
   iterations write into `logs/story-writer/`.
2. **Run-level audit trail**: After several iterations, `git log` in the workspace shows one commit
   per terminal run with status-aware messages.
3. **Meaningful diffs**: Users inspect exactly what changed between iterations via `git diff`.
4. **No-op on unchanged workspace**: If no files changed, no commit is created.
5. **Graceful degradation**: Systems without `git` continue running normally with no crashes.
6. **Identity independence**: Commits succeed without requiring global git config.

## Architecture

### New Utility

`src/main/kotlin/attractor/workspace/WorkspaceGit.kt`

Responsibilities:
1. Determine git availability once (`git --version`) and cache result.
2. Initialize repo in a workspace directory if needed.
3. Configure local identity (`user.name`, `user.email`) in that repo.
4. Stage and commit only when there are changes.
5. Never throw to callers; failures are absorbed as no-op behavior.

Proposed shape:

```kotlin
object WorkspaceGit {
    fun initIfNeeded(logsRoot: String)
    fun commitIfChanged(logsRoot: String, message: String)
}
```

Implementation notes:
1. Use `ProcessBuilder(...).directory(File(logsRoot)).redirectErrorStream(true)` with `waitFor()`.
2. `initIfNeeded` should no-op when directory is missing or git unavailable.
3. Use either local config (`git config user.name/user.email`) or `--author`; local config is
   preferred for compatibility with future manual commits in the same workspace.
4. `commitIfChanged` flow:
   - `git add -A`
   - detect staged/working changes using porcelain status
   - commit only when status is non-empty
5. Make command failure non-fatal (log warning-level signal where practical).

### Runner Integration

`src/main/kotlin/attractor/web/ProjectRunner.kt`

1. After resolving and persisting `logsRoot`, call `WorkspaceGit.initIfNeeded(logsRoot)`.
2. On terminal events (`ProjectCompleted`, `ProjectFailed`, `ProjectCancelled`, `ProjectPaused`),
   call `WorkspaceGit.commitIfChanged(logsRoot, message)` after run state/log persistence updates.
3. Keep commit hooks out of non-terminal events to preserve one logical commit per run outcome.

### Iteration Continuity Fix

`src/main/kotlin/attractor/web/RestApiRouter.kt`

`handleCreateIteration()` currently calls `ProjectRunner.submit(...)` without a
`displayNameOverride`, allowing name regeneration and new workspace directories. This sprint
passes the parent display name:

```kotlin
displayNameOverride = entry.displayName
```

That makes `safeName` derivation stable for iterations of the same project family.

### Data Flow

```text
POST /api/v1/projects/{id}/iterations
  -> RestApiRouter.handleCreateIteration()
  -> ProjectRunner.submit(..., displayNameOverride = entry.displayName)
  -> ProjectRunner.runProject()
     -> logsRoot resolved/reused
     -> WorkspaceGit.initIfNeeded(logsRoot)
     -> Engine writes artifacts under logsRoot
     -> terminal event
        -> store status/log/finishedAt updates
        -> WorkspaceGit.commitIfChanged(logsRoot, terminal-message)
```

## Implementation Plan

### Phase 1: Add workspace git utility (~30%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` (Create)

**Tasks:**
- [ ] Create `WorkspaceGit` object with `initIfNeeded` and `commitIfChanged`.
- [ ] Add a cached git-availability probe.
- [ ] Implement idempotent repo init for existing/non-existing `.git` state.
- [ ] Configure local identity (`Attractor`, `attractor@localhost`) in workspace config.
- [ ] Implement conditional commit logic to avoid empty commits.
- [ ] Ensure all failures are non-fatal and do not propagate.

---

### Phase 2: Wire git lifecycle into runner (~30%)

**Files:**
- `src/main/kotlin/attractor/web/ProjectRunner.kt` (Modify)

**Tasks:**
- [ ] Import and call `WorkspaceGit.initIfNeeded(logsRoot)` after logsRoot assignment.
- [ ] Add commit calls for terminal events only.
- [ ] Build deterministic commit messages including run id and status.
- [ ] Keep existing status/log persistence order intact before commit call.
- [ ] Verify exception paths continue to persist state and remain stable.

---

### Phase 3: Fix iteration workspace drift (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` (Modify)

**Tasks:**
- [ ] In `handleCreateIteration()`, pass `displayNameOverride = entry.displayName`.
- [ ] Confirm family linkage remains `familyId = entry.familyId`.
- [ ] Confirm new-project creation path remains unchanged (still generates new names).

---

### Phase 4: Tests for git utility and iteration behavior (~30%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` (Create)
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` (Modify)
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` (Modify, optional presence checks only)

**Tasks:**
- [ ] Add `WorkspaceGitTest` with temp-dir scenarios:
  - [ ] init no-op on missing directory
  - [ ] init creates `.git` when git available
  - [ ] init idempotent on repeated calls
  - [ ] commit no-op without repo
  - [ ] commit created when file changes
  - [ ] no second commit when no further changes
- [ ] Guard git-dependent assertions when git is unavailable in environment.
- [ ] Add/expand router test to assert iteration creation preserves display-name lineage intent
  (indirectly ensuring workspace continuity path).
- [ ] Optional: add browser markup-presence assertion for `WorkspaceGit` symbol only if reflected in
  server-rendered code (likely not needed; server-only utility).

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Create | Centralized git init/commit behavior for workspaces |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Initialize git at workspace setup and commit on terminal events |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Ensure iterations reuse parent display name/workspace lineage |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Create | Unit coverage for git init/commit behavior and no-op paths |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Verify iteration request handling remains correct with continuity fix |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify (optional) | Keep sprint marker/assertion coverage pattern consistent if desired |

## Definition of Done

### Workspace Behavior
- [ ] Iterations no longer create sibling workspace directories for the same project lineage.
- [ ] Project family runs consistently resolve to one canonical `logs/$safeName` path.
- [ ] Rerun behavior remains unchanged and continues reusing existing logsRoot.

### Git Integration
- [ ] `WorkspaceGit.initIfNeeded` initializes repository on first workspace use.
- [ ] Terminal run events trigger `commitIfChanged` checks.
- [ ] No commit is made when there are no changes.
- [ ] Commit author identity does not depend on global git config.
- [ ] Missing `git` binary causes no crashes and no failed run lifecycle.

### Quality
- [ ] New tests for `WorkspaceGit` pass locally/CI.
- [ ] Existing web/router/state tests remain green.
- [ ] Build passes with no new Gradle dependencies.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Commit hook fires before final logs are persisted | Medium | Medium | Place commit call after `store.updateLog` in terminal-event branches |
| Git command hangs or leaks processes | Low | High | Always consume process output (redirect stream) and `waitFor()` each command |
| Local git config write fails in restricted workspace | Low | Low | Fallback to no-op commit path and preserve run completion behavior |
| Two distinct projects share same display name and workspace | Medium | Medium | Accept for this sprint per intent; document as known behavior |
| Iteration still forks workspace due to blank displayName edge case | Low | Medium | Use persisted `entry.displayName`; fallback path still deterministic through current safeName logic |

## Security Considerations

- Shelling out is limited to static `git` subcommands with controlled arguments.
- No untrusted user input is interpolated into shell command strings.
- All operations are confined to resolved workspace directories already used by engine output.
- Existing artifact path traversal protections are unchanged.

## Dependencies

- Sprint 019 naming migration should be complete or treated as baseline for this sprint.
- No new library dependencies; relies on system `git` presence when available.
- Existing `ProjectRunner` terminal event flow remains the integration anchor.

## Open Questions

1. Should commit-on-terminal include only `completed`, or all terminal states (`failed`,
   `cancelled`, `paused`) as drafted in intent context?
2. Preferred commit message format: minimal (`Run <id> completed`) vs richer metadata (stage count,
   duration).
3. Should display-name collisions across unrelated projects be addressed now (e.g., suffixing), or
   explicitly deferred?
4. Should we log a user-visible warning when `git` is unavailable, or keep behavior fully silent?
