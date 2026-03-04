# Sprint 020: Workspace Git Versioning

## Overview

Every time Attractor runs a project, the engine writes stage outputs, logs, and artifacts into a
workspace directory under `logs/$safeName`. Today, when a user iterates on a project (refining the
DOT graph and re-running), a new random display name is generated for the iteration → a new sibling
directory is created. Users end up with `logs/clever-badger`, `logs/happy-fox`, `logs/swift-crane`
— all belonging to the same conceptual project, scattered across the filesystem.

This sprint fixes the root cause and adds a git versioning layer on top. Each project gets a single
canonical workspace directory, named after the project's display name. On first use, `git init` is
called in that directory. After each run reaches a terminal state (completed, failed, cancelled, or
paused), if the workspace has uncommitted changes, a `git commit` is recorded with a descriptive
message. The git history becomes the natural audit trail: each commit is one run, the diff shows
exactly what changed between iterations.

All git operations are shelled out via `ProcessBuilder` — no new Gradle dependencies. If `git` is
not available on PATH, all operations silently no-op. The change is confined to a new
`WorkspaceGit` utility object, a one-line fix in `RestApiRouter.handleCreateIteration()`, and
two commit call sites in `ProjectRunner`.

## Use Cases

1. **Single directory per project**: A user creates a project named "story-writer". All runs,
   reruns, and iterations write their outputs to `logs/story-writer/`. No sibling directories.
2. **Git history as run log**: After 3 iterations, `git log logs/story-writer/` shows 3 commits,
   each timestamped to its run's completion time, with a message like
   `"Run run-1234-1 completed: 4 stages"`.
3. **Diff between iterations**: `git diff HEAD~1 HEAD -- logs/story-writer/stage2/` shows exactly
   what the second iteration changed in stage 2's output.
4. **No changes, no commit**: A run is cancelled before writing any files. `WorkspaceGit` detects
   no staged changes and skips the commit — no empty commits.
5. **Git not installed**: A user on a minimal Docker image without `git` creates and runs projects
   normally; no errors are thrown, no warnings crash the server.
6. **Idempotent init**: `git init` is called every time a workspace is first assigned to a run.
   Since `git init` on an existing repo is safe, this is always a no-op after the first call.

## Architecture

```
New file:
  src/main/kotlin/attractor/workspace/WorkspaceGit.kt

  object WorkspaceGit {
      private val GIT_AUTHOR = "Attractor <attractor@localhost>"

      /** Returns true if `git` is available on PATH. Cached after first check. */
      private val gitAvailable: Boolean by lazy {
          runCatching {
              ProcessBuilder("git", "--version")
                  .redirectErrorStream(true)
                  .start().waitFor() == 0
          }.getOrDefault(false)
      }

      /**
       * Initializes a git repository in [dir] if not already initialized.
       * Also writes a minimal local git config (user.name, user.email) so commits
       * work without a global git identity.
       * No-op if git is unavailable or [dir] does not exist.
       */
      fun init(dir: String) {
          if (!gitAvailable) return
          val f = java.io.File(dir)
          if (!f.isDirectory) return
          if (java.io.File(f, ".git").exists()) return   // already initialized
          runCatching {
              ProcessBuilder("git", "init")
                  .directory(f)
                  .redirectErrorStream(true)
                  .start().waitFor()
              // Set local identity so commits work without a global git config
              ProcessBuilder("git", "config", "user.name", "Attractor")
                  .directory(f).redirectErrorStream(true).start().waitFor()
              ProcessBuilder("git", "config", "user.email", "attractor@localhost")
                  .directory(f).redirectErrorStream(true).start().waitFor()
          }
      }

      /**
       * Stages all changes and commits if anything is staged.
       * No-op if git is unavailable, [dir] has no .git, or there are no changes.
       */
      fun commitIfChanged(dir: String, message: String) {
          if (!gitAvailable) return
          val f = java.io.File(dir)
          if (!java.io.File(f, ".git").exists()) return
          runCatching {
              ProcessBuilder("git", "add", "-A")
                  .directory(f).redirectErrorStream(true).start().waitFor()
              // Check if there is anything staged
              val statusProc = ProcessBuilder("git", "status", "--porcelain")
                  .directory(f).redirectErrorStream(true).start()
              val statusOut = statusProc.inputStream.bufferedReader().readText().trim()
              statusProc.waitFor()
              if (statusOut.isEmpty()) return   // nothing to commit
              ProcessBuilder(
                  "git", "commit", "-m", message,
                  "--author", GIT_AUTHOR
              ).directory(f).redirectErrorStream(true).start().waitFor()
          }
      }
  }

Modified file:
  src/main/kotlin/attractor/web/ProjectRunner.kt (post-019 name; currently PipelineRunner.kt)

  Callsite 1 — after logsRoot is resolved (~line 131):
    registry.setLogsRoot(id, logsRoot)
    registry.get(id)?.state?.logsRoot = logsRoot
    WorkspaceGit.init(logsRoot)   // NEW: initialize git on first use

  Callsite 2 — in the terminal event when block (~lines 168-179):
    is ProjectEvent.ProjectCompleted -> {
        store.updateStatus(id, "completed"); store.updateLog(...); store.updateFinishedAt(...)
        WorkspaceGit.commitIfChanged(logsRoot, "Run $id completed: ${state.stages.count { it.status == "completed" }} stages")
    }
    is ProjectEvent.ProjectFailed -> {
        store.updateStatus(id, "failed"); store.updateLog(...); store.updateFinishedAt(...)
        // (failure report check)
        WorkspaceGit.commitIfChanged(logsRoot, "Run $id failed: ${state.stages.count { it.status == "completed" }} stages completed")
    }
    is ProjectEvent.ProjectCancelled -> {
        store.updateStatus(id, "cancelled"); store.updateLog(...); store.updateFinishedAt(...)
        WorkspaceGit.commitIfChanged(logsRoot, "Run $id cancelled")
    }
    is ProjectEvent.ProjectPaused -> {
        store.updateStatus(id, "paused"); store.updateLog(...); store.updateFinishedAt(...)
        WorkspaceGit.commitIfChanged(logsRoot, "Run $id paused: checkpoint saved")
    }

Modified file:
  src/main/kotlin/attractor/web/RestApiRouter.kt

  handleCreateIteration() — pass displayNameOverride so iterations reuse the parent's workspace:
    BEFORE:
      val newId = ProjectRunner.submit(
          dotSource = dotSource,
          fileName = fileName,
          options = entry.options,
          registry = registry,
          store = store,
          originalPrompt = originalPrompt,
          familyId = entry.familyId,
          onUpdate = onUpdate
      )
    AFTER:
      val newId = ProjectRunner.submit(
          dotSource = dotSource,
          fileName = fileName,
          options = entry.options,
          registry = registry,
          store = store,
          originalPrompt = originalPrompt,
          familyId = entry.familyId,
          displayNameOverride = entry.displayName,   // NEW: reuse parent workspace
          onUpdate = onUpdate
      )

Data flow:
  ProjectRunner.submit()
    → NameGenerator.generate() used only when displayNameOverride is blank
    → runPipeline()
        → logsRoot = registry.get(id)?.logsRoot?.takeIf { it.isNotBlank() } ?: "logs/$safeName"
        → WorkspaceGit.init(logsRoot)          ← new
        → engine runs, writes files to logsRoot
        → on terminal event: WorkspaceGit.commitIfChanged(logsRoot, msg)   ← new
```

## Implementation Plan

### Phase 1: `WorkspaceGit` utility object (~30%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` — Create

**Tasks:**
- [ ] Create `src/main/kotlin/attractor/workspace/` directory
- [ ] Write `WorkspaceGit.kt` with `object WorkspaceGit`:
  - [ ] `private val gitAvailable: Boolean by lazy { ... }` — checks `git --version` via
    `ProcessBuilder`, defaults `false` on exception
  - [ ] `fun init(dir: String)` — runs `git init` + sets local `user.name`/`user.email` if `.git`
    does not exist; no-op if unavailable or dir doesn't exist
  - [ ] `fun commitIfChanged(dir: String, message: String)` — runs `git add -A`, checks
    `git status --porcelain`, commits with `--author` if output non-empty; all wrapped in
    `runCatching`
- [ ] All `ProcessBuilder` calls use `.redirectErrorStream(true)` and `.waitFor()` — no dangling
  processes, no thread leak

---

### Phase 2: Fix iteration sibling-directory root cause (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] In `handleCreateIteration()`, add `displayNameOverride = entry.displayName` to the
  `ProjectRunner.submit()` call
- [ ] Verify that `entry.displayName` is the parent run's canonical display name (set during
  `register()` and persisted in DB)
- [ ] Confirm no other `ProjectRunner.submit()` call sites need the same treatment
  (e.g., `handleCreateProject()` — those are new projects, correctly get a new name)

---

### Phase 3: Hook git into `ProjectRunner` (~30%)

**Files:**
- `src/main/kotlin/attractor/web/ProjectRunner.kt` (post-019; currently `PipelineRunner.kt`) — Modify

**Tasks:**
- [ ] Add `import attractor.workspace.WorkspaceGit`
- [ ] After `registry.setLogsRoot(id, logsRoot)` and `registry.get(id)?.state?.logsRoot = logsRoot`,
  call `WorkspaceGit.init(logsRoot)`
- [ ] In the terminal event `when` block, after each `store.update*()` call, add
  `WorkspaceGit.commitIfChanged(logsRoot, msg)`:
  - `ProjectCompleted`: `"Run $id completed: ${state.stages.count { it.status == "completed" }} stages"`
  - `ProjectFailed`:    `"Run $id failed: ${state.stages.count { it.status == "completed" }} stages completed"`
  - `ProjectCancelled`: `"Run $id cancelled"`
  - `ProjectPaused`:    `"Run $id paused: checkpoint saved"`
- [ ] `logsRoot` is already a local `val` in `runPipeline()` scope — available at all call sites
- [ ] The `commitIfChanged` call must come AFTER `store.updateLog(id, ...)` so the final log
  content is written to disk before git stages it

---

### Phase 4: Tests (~25%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` — Create
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` or
  `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify (symbol presence only)

**Tasks:**
- [ ] Create `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` with `FunSpec`:
  - [ ] `init() on nonexistent dir is a no-op` — pass a non-existent path; no exception thrown
  - [ ] `init() creates .git directory` — create temp dir, call `init()`, assert `.git` exists
    (only if git available on PATH; skip with `assumeTrue` otherwise)
  - [ ] `init() is idempotent` — call `init()` twice on same temp dir; no error, `.git` still exists
  - [ ] `commitIfChanged() with no .git is a no-op` — no exception thrown
  - [ ] `commitIfChanged() with no changes creates no commit` — init a fresh git repo in temp dir,
    call `commitIfChanged()`; assert `git log` shows no commits (zero exit or empty output)
  - [ ] `commitIfChanged() stages and commits new file` — init repo, write a file, call
    `commitIfChanged()`; assert `git log --oneline` shows 1 commit
  - [ ] `commitIfChanged() does not commit when nothing changed after first commit` — after one
    commit, call `commitIfChanged()` again; still only 1 commit
- [ ] Add markup-presence assertions to `RestApiRouterTest.kt` or `WebMonitorServerBrowserApiTest.kt`:
  - [ ] (No JS symbols to check — WorkspaceGit is server-side only; confirm `handleCreateIteration`
    test in RestApiRouterTest passes with the `displayNameOverride` change)

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Create | Git init + conditional commit utility |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Call `WorkspaceGit.init()` on workspace setup; call `WorkspaceGit.commitIfChanged()` on terminal events |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Pass `displayNameOverride = entry.displayName` in `handleCreateIteration()` |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Create | 7 unit tests for WorkspaceGit |

## Definition of Done

### Workspace Consolidation
- [ ] `handleCreateIteration()` passes `displayNameOverride = entry.displayName` to
  `ProjectRunner.submit()`; new iterations use the same display name as the parent → same
  `safeName` → same `logsRoot` directory
- [ ] No new sibling directories created for iterations of an existing project

### Git Initialization
- [ ] `WorkspaceGit.init(logsRoot)` is called once per run, immediately after `logsRoot` is
  assigned in `runPipeline()`
- [ ] `git init` and local identity config are only run when `.git` does not exist
- [ ] If `git` is not on PATH, `init()` is a silent no-op (no exception propagated)
- [ ] If `logsRoot` directory does not exist at init time, no-op (engine will create it later;
  init is called again for the next run)

### Git Commits
- [ ] `WorkspaceGit.commitIfChanged()` is called after each of:
  - `ProjectCompleted`, `ProjectFailed`, `ProjectCancelled`, `ProjectPaused`
- [ ] Commit message includes run ID and terminal status
- [ ] No commit is created if `git status --porcelain` reports no changes
- [ ] Commit uses `--author "Attractor <attractor@localhost>"` — does not require global git config
- [ ] All git subprocess calls use `ProcessBuilder`, `.redirectErrorStream(true)`, `.waitFor()`
- [ ] No unhandled exceptions propagate from `WorkspaceGit` — all wrapped in `runCatching`

### Tests
- [ ] 7 unit tests in `WorkspaceGitTest` pass
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `git` subprocess blocks indefinitely | Low | High | Add `.waitFor(timeout, TimeUnit.SECONDS)` with a reasonable limit (e.g., 30s); kill on timeout |
| Two concurrent runs targeting the same logsRoot (same display name, parallel submissions) | Low | Medium | `git add -A` + `git commit` is atomic from filesystem perspective; worst case: interleaved commits. Acceptable for v1. |
| `git commit` fails silently (e.g., git config issue) | Medium | Low | `commitIfChanged` wraps in `runCatching`; failure is silent. Log on debug if needed. |
| Iteration with different node IDs leaves orphan stage dirs from prior run | Low | Low | Old stage dirs remain in workspace; git tracks them. Not a functional problem — git history is the record. |
| `git init` creates `.git` in wrong directory (e.g., a shared `logs/` parent) | Low | High | `init()` only called with the specific `logsRoot` value, not parent dirs. |
| Renamed project files from Sprint 019 cause import issues | Low | Medium | Sprint 020 must be implemented after Sprint 019 is complete; use post-019 type names. |

## Security Considerations

- `logsRoot` is a server-controlled path derived from `safeName = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-")`. Not user-supplied directly to git commands — injection risk is low.
- `ProcessBuilder` is used with explicit argument arrays (not shell strings), so no shell injection is possible.
- Commit message includes run ID (server-generated) and stage count (integer) — no user-controlled strings in commit messages.
- The `.git` directory created in the workspace is local to the server's filesystem; no remote push is configured.

## Dependencies

- Sprint 019 (must be complete) — this sprint uses post-019 type names (`ProjectRunner`,
  `ProjectEvent`, etc.)
- No external dependencies; no new Gradle dependencies.

## Open Questions

1. Should `failed` and `cancelled` runs commit? Draft says yes (provides audit trail of
   unsuccessful runs). Confirm with user.
2. Should `paused` state commit? Draft says yes (checkpoint snapshot). Confirm with user.
3. Workspace path prefix: keep `logs/` or rename to `workspace/`? Draft keeps `logs/` to minimize
   scope — the path is configurable only via the `logsRoot` logic already in `ProjectRunner`.
4. When iterating, should we use `entry.displayName` (parent run's name) or look up the family's
   first-run display name? Draft uses `entry.displayName` — simplest correct approach.
5. Should `WorkspaceGit.gitAvailable` be exposed as a server health/info endpoint so users know
   if git tracking is active?
