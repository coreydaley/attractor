# Sprint 020: Workspace Git Versioning

## Overview

Every time Attractor runs a project, the engine writes stage outputs, logs, and artifacts into a
workspace directory. Today, a rerun (`/rerun`) correctly reuses the same workspace, but an
iteration (`/iterations`) calls `ProjectRunner.submit()` without a `displayNameOverride`, causing
a new random display name to be generated → a new sibling directory. Users end up with
`logs/clever-badger`, `logs/happy-fox`, `logs/swift-crane` — all belonging to the same project
family, scattered across the filesystem.

This sprint fixes that root cause and layers git versioning on top. Each project gets a single
canonical workspace directory. On first use, `git init` is called in that directory. After each
run reaches a terminal state (completed, failed, cancelled, or paused), if the workspace has
uncommitted changes, a `git commit` is recorded with a descriptive message. The git history
becomes the audit trail: each commit is one run, the diff shows exactly what changed between
iterations.

The sprint also renames the workspace path prefix from `logs/` to `workspace/`, consistent with
the project-centric language introduced in Sprint 019. An idempotent DB migration updates existing
`logs_root` values for any runs that already used the old prefix.

All git operations shell out via `ProcessBuilder` — no new Gradle dependencies. If `git` is not
available on PATH, all operations silently no-op. The change touches five files: a new
`WorkspaceGit` utility, `ProjectRunner.kt`, `RestApiRouter.kt`, `SqliteRunStore.kt`, and a new
test file.

## Use Cases

1. **Single workspace continuity**: A user creates project `story-writer`. All reruns and
   iterations write their outputs to `workspace/story-writer/`. No sibling directories.
2. **Git history as run log**: After 4 iterations, `git log workspace/story-writer/` shows 4
   commits, each describing the run's terminal status.
3. **Meaningful diffs**: `git diff HEAD~1 HEAD -- workspace/story-writer/stage2/` shows exactly
   what changed in stage 2 between iterations.
4. **No-op on unchanged workspace**: A run is cancelled before writing any files. `WorkspaceGit`
   detects no staged changes and skips the commit — no empty commits.
5. **Graceful degradation**: A server without `git` on PATH creates and runs projects normally; no
   errors are thrown.
6. **Identity independence**: Commits succeed without requiring a global git config.
7. **Legacy path migration**: A user who has existing runs stored with `logsRoot = "logs/foo"`
   upgrades the binary; the DB migration updates those records to `workspace/foo` automatically.

## Architecture

```
New file:
  src/main/kotlin/attractor/workspace/WorkspaceGit.kt

  object WorkspaceGit {
      private val gitAvailable: Boolean by lazy { ... }   // cached git --version probe

      fun init(dir: String)
          - No-op if !gitAvailable or dir does not exist or already has .git
          - Runs: git init
          - Runs: git config user.name "Attractor"
          - Runs: git config user.email "attractor@localhost"

      fun commitIfChanged(dir: String, message: String)
          - Calls init(dir) first (lazy guard for first-run race)
          - No-op if !gitAvailable or dir has no .git
          - Runs: git add -A
          - Runs: git status --porcelain (checks for changes)
          - If non-empty: git commit -m "$message"
          - All process calls wrapped in runCatching (non-fatal)
  }

Modified:
  src/main/kotlin/attractor/web/ProjectRunner.kt

  In runProject() — after logsRoot resolved and persisted (~line 131):
    registry.setLogsRoot(id, logsRoot)
    registry.get(id)?.state?.logsRoot = logsRoot
    WorkspaceGit.init(logsRoot)          ← NEW

  In terminal event when-block — after each store.update*() call:
    ProjectCompleted  → WorkspaceGit.commitIfChanged(logsRoot, "Run $id completed: N stages")
    ProjectFailed     → WorkspaceGit.commitIfChanged(logsRoot, "Run $id failed: N stages completed")
    ProjectCancelled  → WorkspaceGit.commitIfChanged(logsRoot, "Run $id cancelled")
    ProjectPaused     → WorkspaceGit.commitIfChanged(logsRoot, "Run $id paused: checkpoint saved")

  Default logsRoot path:
    BEFORE: "logs/$safeName"
    AFTER:  "workspace/$safeName"

Modified:
  src/main/kotlin/attractor/web/RestApiRouter.kt

  handleCreateIteration() — add displayNameOverride:
    BEFORE:
      val newId = ProjectRunner.submit(
          dotSource = dotSource, fileName = fileName,
          options = entry.options, registry = registry, store = store,
          originalPrompt = originalPrompt, familyId = entry.familyId, onUpdate = onUpdate
      )
    AFTER:
      val newId = ProjectRunner.submit(
          dotSource = dotSource, fileName = fileName,
          options = entry.options, registry = registry, store = store,
          originalPrompt = originalPrompt, familyId = entry.familyId,
          displayNameOverride = entry.displayName,    ← NEW
          onUpdate = onUpdate
      )

  handleDeleteProject() — update restart dir cleanup pattern:
    BEFORE: lrFile.parentFile?.listFiles { f -> f.name.startsWith(lrFile.name + "-restart-") }
    AFTER:  same logic (no change needed; the parent dir changes but the pattern is relative)

Modified:
  src/main/kotlin/attractor/db/SqliteRunStore.kt

  Add in init() block after existing Sprint 019 migrations:
    // Migration: rename logs/ workspace prefix → workspace/ (idempotent)
    runCatching {
        conn.createStatement().use { stmt ->
            stmt.execute("UPDATE project_runs SET logs_root = 'workspace' || SUBSTR(logs_root, 5) WHERE logs_root LIKE 'logs/%'")
        }
    }

Data flow (post-fix):
  POST /api/v1/projects/{id}/iterations
    → handleCreateIteration() — displayNameOverride = entry.displayName
    → ProjectRunner.submit(..., displayNameOverride = "story-writer")
    → runProject()
        → logsRoot = "workspace/story-writer"
        → WorkspaceGit.init("workspace/story-writer")
        → Engine writes to workspace/story-writer/
        → ProjectCompleted event
            → store.updateStatus / updateLog / updateFinishedAt
            → WorkspaceGit.commitIfChanged("workspace/story-writer", "Run run-X completed: 4 stages")
```

## Implementation Plan

### Phase 1: `WorkspaceGit` utility object (~25%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` — Create

**Tasks:**
- [ ] Create package directory `src/main/kotlin/attractor/workspace/`
- [ ] Write `object WorkspaceGit` with:
  - [ ] `private val gitAvailable: Boolean by lazy` — runs `git --version` via `ProcessBuilder`,
    returns `false` on any exception
  - [ ] `fun init(dir: String)`:
    - no-op if `!gitAvailable`
    - no-op if `java.io.File(dir)` is not a directory
    - skip `git init` if `.git` already exists (idempotent)
    - run `git init`, `git config user.name "Attractor"`,
      `git config user.email "attractor@localhost"` — each with `ProcessBuilder`, `.directory()`,
      `.redirectErrorStream(true)`, `.waitFor()`
    - all wrapped in `runCatching`
  - [ ] `fun commitIfChanged(dir: String, message: String)`:
    - calls `init(dir)` first (guard for first-run timing: workspace may now exist)
    - no-op if `!gitAvailable` or `.git` does not exist
    - runs `git add -A` → `git status --porcelain` → if non-empty: `git commit -m "$message"`
    - all wrapped in `runCatching`
- [ ] All `ProcessBuilder` calls use `.redirectErrorStream(true)` and `.waitFor()` — no dangling
  processes

---

### Phase 2: Wire git into `ProjectRunner` and rename path prefix (~30%)

**Files:**
- `src/main/kotlin/attractor/web/ProjectRunner.kt` — Modify

**Tasks:**
- [ ] Add `import attractor.workspace.WorkspaceGit`
- [ ] Change default logsRoot derivation from `"logs/$safeName"` → `"workspace/$safeName"`
- [ ] After `registry.setLogsRoot(id, logsRoot)` and `registry.get(id)?.state?.logsRoot = logsRoot`,
  call `WorkspaceGit.init(logsRoot)`
- [ ] In terminal event `when` block, add `WorkspaceGit.commitIfChanged(logsRoot, msg)` after
  each `store.updateStatus/updateLog/updateFinishedAt` chain:
  - `ProjectCompleted`: message = `"Run $id completed: ${state.stages.count { it.status == "completed" }} stages"`
  - `ProjectFailed`:    message = `"Run $id failed: ${state.stages.count { it.status == "completed" }} stages completed"`
  - `ProjectCancelled`: message = `"Run $id cancelled"`
  - `ProjectPaused`:    message = `"Run $id paused: checkpoint saved"`
- [ ] The `logsRoot` local val is already in scope throughout `runProject()` — no refactoring needed
- [ ] Verify that both the `catch(InterruptedException)` and `catch(Exception)` branches include
  `WorkspaceGit.commitIfChanged(logsRoot, ...)` for their terminal events (failed/cancelled)

---

### Phase 3: Fix iteration workspace drift in `RestApiRouter` (~10%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] In `handleCreateIteration()`, add `displayNameOverride = entry.displayName` to
  `ProjectRunner.submit()` call
- [ ] Verify `familyId = entry.familyId` remains unchanged (family linkage preserved)
- [ ] Confirm `handleCreateProject()` and `handleUploadDot()` (new project paths) do NOT receive
  `displayNameOverride` — they should still generate fresh names

---

### Phase 4: DB migration for `logs/` → `workspace/` prefix (~10%)

**Files:**
- `src/main/kotlin/attractor/db/SqliteRunStore.kt` — Modify

**Tasks:**
- [ ] After existing Sprint 019 migration block in `SqliteRunStore.init()`, add:
  ```kotlin
  // Migration: rename logs/ workspace path prefix → workspace/ (idempotent)
  runCatching {
      conn.createStatement().use { stmt ->
          stmt.execute(
              "UPDATE project_runs SET logs_root = 'workspace' || SUBSTR(logs_root, 5) WHERE logs_root LIKE 'logs/%'"
          )
      }
  }
  ```
- [ ] Migration is idempotent: rows already prefixed with `workspace/` do not match `LIKE 'logs/%'`
- [ ] Rows with blank `logs_root` (default `''`) are unaffected
- [ ] Confirm `JdbcRunStore.kt` does not need an equivalent migration (it targets MySQL/Postgres
  which use the same SQL syntax; add there too if it exists)

---

### Phase 5: Tests (~25%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` — Create
- `src/test/kotlin/attractor/db/SqliteRunStoreTest.kt` — Modify

**Tasks:**
- [ ] Create `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` as Kotest `FunSpec`:
  - [ ] `init() on nonexistent dir is a no-op` — pass non-existent path; no exception
  - [ ] `init() creates .git directory` — create temp dir via `Files.createTempDirectory()`,
    call `WorkspaceGit.init(dir.toString())`; assert `File(dir, ".git").exists()`.
    Guard the assertion: `if (!gitOnPath) return` (check via `ProcessBuilder("git", "--version")`)
  - [ ] `init() is idempotent` — call `init()` twice; no error, `.git` still present
  - [ ] `commitIfChanged() with no .git is a no-op` — call on temp dir without init; no exception
  - [ ] `commitIfChanged() with no changes creates no commit` — init repo, call `commitIfChanged()`;
    assert `git log` exits non-zero or outputs nothing (zero commits)
  - [ ] `commitIfChanged() stages and commits a new file` — init repo, write a file, call
    `commitIfChanged(dir, "test commit")`; assert `git log --oneline` shows 1 line
  - [ ] `commitIfChanged() does not double-commit unchanged state` — after one commit, call
    `commitIfChanged()` again; still only 1 commit in `git log`
- [ ] Add to `SqliteRunStoreTest.kt`:
  - [ ] `logs_root migration renames logs/ prefix to workspace/` — create store with legacy path
    `logs/foo` inserted directly via SQL, reopen store, verify `getById()` returns `logsRoot =
    "workspace/foo"`
  - [ ] `logs_root migration is idempotent for workspace/ entries` — insert `workspace/foo`,
    reopen store, verify still `"workspace/foo"` (not double-prefixed)
  - [ ] `logs_root migration leaves blank logsRoot unchanged` — insert `""`, verify still `""`

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Create | Git init + conditional commit utility |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Change path prefix to `workspace/`; call `WorkspaceGit.init()` on setup; call `WorkspaceGit.commitIfChanged()` on all four terminal events |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Add `displayNameOverride = entry.displayName` in `handleCreateIteration()` |
| `src/main/kotlin/attractor/db/SqliteRunStore.kt` | Modify | Idempotent `logs_root` prefix migration |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Create | 7 unit tests for WorkspaceGit |
| `src/test/kotlin/attractor/db/SqliteRunStoreTest.kt` | Modify | 3 migration tests for logs_root prefix |

## Definition of Done

### Workspace Consolidation
- [ ] `handleCreateIteration()` passes `displayNameOverride = entry.displayName`; new iterations
  use the same display name as the parent → same `safeName` → same workspace directory
- [ ] No new sibling directories created for iterations of an existing project
- [ ] Rerun (`/rerun`) continues to reuse the same logsRoot (unchanged behavior)

### Path Prefix
- [ ] New runs write to `workspace/$safeName` (not `logs/$safeName`)
- [ ] Existing `logs_root` DB values prefixed with `logs/` are migrated to `workspace/` at startup
- [ ] Migration is idempotent: already-migrated rows and blank rows are unchanged

### Git Initialization
- [ ] `WorkspaceGit.init(logsRoot)` is called after logsRoot is resolved and persisted
- [ ] `git init` runs only once per workspace (`.git` already exists → no-op)
- [ ] Local git identity (`user.name = Attractor`, `user.email = attractor@localhost`) is
  configured in the workspace's `.git/config` — no global git config required
- [ ] `init()` is a silent no-op when `git` is not on PATH or directory doesn't exist

### Git Commits
- [ ] `WorkspaceGit.commitIfChanged()` is called after all four terminal event types:
  `ProjectCompleted`, `ProjectFailed`, `ProjectCancelled`, `ProjectPaused`
- [ ] Commits are placed AFTER `store.updateStatus / updateLog / updateFinishedAt` in each branch
- [ ] No commit created when `git status --porcelain` reports no changes
- [ ] Commit message includes run ID and terminal status
- [ ] `commitIfChanged()` calls `init()` internally as a guard (handles timing where init was
  called before workspace dir existed)
- [ ] All git subprocess calls use `ProcessBuilder`, `.redirectErrorStream(true)`, `.waitFor()`
- [ ] No unhandled exceptions from `WorkspaceGit` — all wrapped in `runCatching`

### Tests
- [ ] 7 unit tests in `WorkspaceGitTest` pass (git-dependent tests guarded if git not on PATH)
- [ ] 3 DB migration tests in `SqliteRunStoreTest` pass
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `git` subprocess blocks indefinitely | Low | High | Each `ProcessBuilder.waitFor()` call can use `waitFor(30, TimeUnit.SECONDS)` timeout; kill process on timeout |
| Two parallel runs targeting same display name → concurrent git writes | Low | Medium | Each run's `commitIfChanged` is sequential within its thread; worst case: interleaved commits in shared history. Acceptable for v1. |
| `logs/` migration breaks existing `WebMonitorServer.kt` UI paths that reference `logsRoot` | Low | Medium | UI paths derive from `entry.logsRoot` (DB value); after migration they read `workspace/foo` which is where new files are written. No hardcoded `logs/` in JS. |
| `git init` creates `.git` inside a symlinked or shared directory | Low | Low | `WorkspaceGit` operates on the literal `logsRoot` path; symlink resolution is out of scope. |
| `displayNameOverride = entry.displayName` is blank for old hydrated entries | Low | Medium | `register()` sets `displayName = displayNameOverride.ifBlank { NameGenerator.generate() }` — so if the override is blank, a new name would still be generated. Hydrated entries load `displayName` from DB; should be non-blank for any run that was named at creation. |
| Renamed path `workspace/` breaks `handleDeleteProject()` restart-dir cleanup | Low | Low | The cleanup uses `lrFile.parentFile` (the `workspace/` dir) which is still correct. |

## Security Considerations

- `logsRoot` is server-controlled (derived from `safeName = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-")`).
  No user-controlled strings are interpolated directly into `ProcessBuilder` command arrays.
- All `ProcessBuilder` calls use explicit argument arrays — no shell string interpolation.
- Commit message uses `"Run $id"` where `id` is server-generated (`run-<timestamp>-<counter>`).
  Stage count is an integer. No user-controlled input in commit messages.
- `.git` directory is local to the workspace; no remote push is configured.
- The DB migration SQL uses a `LIKE 'logs/%'` literal pattern and a `SUBSTR` expression — no
  user-controlled input.
- Existing artifact path traversal protections in `RestApiRouter` are unchanged.

## Dependencies

- Sprint 019 (in_progress / must complete first) — this sprint uses post-019 type names
  (`ProjectRunner`, `ProjectEvent`, `ProjectRegistry`) and the `project_runs` DB schema
- No new Gradle dependencies; relies on system `git` when available

## Open Questions

1. Should we surface git availability status anywhere in the UI or API health endpoint? Proposed:
   **defer** — transparent degradation is sufficient for v1.
2. Should the commit message include stage count for `cancelled` and `paused` runs? Proposed:
   **no** — those states don't have a meaningful "completed stage count" semantics; keep the
   messages minimal.
3. Should `workspace/` subdirectories be `.gitignore`d from the main Attractor repo? Proposed:
   **yes** — add `workspace/` to the top-level `.gitignore` to prevent project artifacts from
   accidentally being committed to the Attractor source repository.
