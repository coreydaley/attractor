# Sprint 020 Merge Notes

## Claude Draft Strengths
- Correct root-cause diagnosis: `handleCreateIteration` omits `displayNameOverride`, creating
  sibling directories on each iteration
- Strong detail on `WorkspaceGit` API: `init()`, `commitIfChanged()`, `ProcessBuilder` pattern,
  local git identity config
- Explicit DoD criteria covering no-empty-commit, all terminal states, graceful no-op
- Appropriate use of `runCatching` for all subprocess paths

## Codex Draft Strengths
- Cleaner function naming: `initIfNeeded` vs `init` makes the idempotent semantics clearer
- Explicitly recommends local config (`git config user.name/email`) over `--author` flag for
  forward-compatibility
- Cleaner data-flow diagram showing the post-fix iteration path end-to-end
- Correctly identifies that `WebMonitorServerBrowserApiTest` symbol-presence checks are noise

## Valid Critiques Accepted

1. **Stale file names in Claude draft**: Current code IS post-019 — `ProjectRunner.kt` with
   `runProject()`. Sprint doc must use current names. ✓ Fixed in final document.

2. **"Two commit call sites" is misleading**: There are four terminal branches (completed/failed/
   cancelled/paused), each with its own commit call. ✓ Fixed in final document.

3. **Init timing gap**: `init()` no-ops when directory doesn't exist. But `runProject()` creates
   the directory DURING the run (the engine writes to it). So by the time terminal event fires,
   the directory WILL exist. However, `init()` is called BEFORE the engine runs (after logsRoot
   is assigned). At that point the directory may not exist yet. Fix: call `init()` inside
   `commitIfChanged()` as well (lazy init at commit time), or create the directory in `init()`.
   Best approach: `commitIfChanged` calls `init()` internally as a guard — if `.git` is absent
   but dir exists, init there. ✓ Fixed in final document.

4. **Test framework**: Use Kotest native `assume` / `!condition` guards, not JUnit `assumeTrue`.
   ✓ Fixed in final document.

5. **`store.updateLog` rationale**: `store.updateLog` writes to DB, not workspace files. The reason
   for ordering: commit after log persistence ensures DB status is consistent before git snapshot.
   Not a filesystem ordering concern. ✓ Fixed in final document.

## Critiques Rejected

- Codex suggested removing `WebMonitorServerBrowserApiTest` optional assertions. Accepted (they
  were already marked optional in Claude draft). No browser-markup assertions needed.

## Interview Refinements Applied

1. **All four terminal states commit**: completed, failed, cancelled, paused — all four get
   `WorkspaceGit.commitIfChanged()`. ✓ Already in both drafts; confirmed.

2. **Rename `logs/` → `workspace/`**: Update `ProjectRunner.runProject()` default logsRoot from
   `"logs/$safeName"` to `"workspace/$safeName"`. Requires:
   - DB migration: `UPDATE project_runs SET logs_root = REPLACE(logs_root, 'logs/', 'workspace/')`
     WHERE `logs_root LIKE 'logs/%'` — applied at startup in `SqliteRunStore.init()`
   - `WebMonitorServer.kt`: Any hardcoded `logs/` references in UI/JS/artifact paths
   - `RestApiRouter.kt`: Any logsRoot-based path in delete/cleanup code
   ✓ Included in final document as a dedicated phase.

3. **Include DB migration in this sprint**: Migration for `logs/` → `workspace/` in logsRoot
   column. Standard idempotent try/catch pattern consistent with Sprint 019 DB migration.

## Final Decisions

- `WorkspaceGit` function names: `init(dir)` + `commitIfChanged(dir, message)` (Claude's names;
  slightly simpler. The idempotent semantics are made clear in the implementation, not name.)
- Init calls `git init` only when `.git` is absent; `commitIfChanged` also calls `init()` as
  guard before staging, so first-run commits are always safe
- Commit message format: `"Run $id completed: ${stageCount} stages"` / `"Run $id failed: ..."` /
  `"Run $id cancelled"` / `"Run $id paused: checkpoint saved"` (stage count where available)
- Workspace path prefix: `workspace/$safeName`
- `logsRoot` DB column migration: idempotent `UPDATE … WHERE logs_root LIKE 'logs/%'`
- DB migration runs at `SqliteRunStore.init()` startup, after existing Sprint 019 migrations
