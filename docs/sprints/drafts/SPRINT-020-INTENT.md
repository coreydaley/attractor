# Sprint 020 Intent: Workspace Git Versioning

## Seed

in each projects workspace directory, we should initialize a git repository, and after each
completed run a git commit should occur if anything changed, a commit should also occur if we
iterate on the project and the run completes and there are changes. We should use this git workflow
instead of creating new sibling directories, each project should only consist of one directory with
the same name as the project and all work should be completed within that directory and follow the
git workflow previously described.

## Context

- Sprint 019 (in_progress) renames "Pipeline" → "Project" throughout the codebase; Sprint 020
  builds on the stable post-019 state.
- Each project run currently creates a workspace at `logs/$safeName` (safeName = sanitized display
  name). A **rerun** (`/rerun` endpoint) reuses the same `logsRoot`; an **iteration**
  (`/iterations` endpoint) calls `ProjectRunner.submit()` with no `displayNameOverride`, so a new
  random display name is generated → a new sibling directory (`logs/$newRandomName`).
- The seed wants: one canonical workspace per project, named after the project, with a git
  repository tracking the full history across reruns and iterations.
- No new Gradle dependencies — git operations must be shelled out via `ProcessBuilder`.

## Recent Sprint Context

- **Sprint 017**: Dashboard layout toggle (card/list); introduced `dashPipelineData()` shared
  helper; purely client-side.
- **Sprint 018**: Fixed progress bar 100%/stage count clarity on pipeline completion; added
  completion flash animation; introduced `prevStatuses` tracking in JS.
- **Sprint 019** (in_progress): Mechanical rename "Pipeline" → "Project" — file renames, API path
  changes, DB migration, CLI rename, SSE contract update.

## Relevant Codebase Areas

| File | Role |
|------|------|
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Handles execution lifecycle; where logsRoot is set and terminal events fire — git init + commit hooks belong here |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | `handleCreateIteration()` passes no `displayNameOverride` — the sibling-directory root cause |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | `setLogsRoot()` updates in-memory + DB; `delete()` returns logsRoot for cleanup |
| `src/main/kotlin/attractor/web/PipelineState.kt` | State model; `logsRoot` field |
| `src/main/kotlin/attractor/engine/Engine.kt` | Writes stage outputs under `logsRoot/$nodeId/` |

Note: post-Sprint-019, `PipelineRunner` → `ProjectRunner`, `PipelineRegistry` → `ProjectRegistry`,
etc. This sprint's implementation should use the post-019 names.

## Constraints

- Must follow project conventions (no CLAUDE.md in repo — infer from existing sprint patterns)
- No new Gradle dependencies — use `ProcessBuilder` / `Runtime.exec()` for git CLI
- Must degrade gracefully when `git` is not available on PATH
- Git commit author identity must not require global git config (use `--author` flag or local
  `user.name`/`user.email` config in the workspace `.git/config`)
- Sprint 019 must be complete (or this sprint drafted on top of its post-019 naming)
- Test using Kotest `FunSpec`, no network calls, temp dirs via `Files.createTempDirectory()`

## Success Criteria

1. Each project has exactly one workspace directory: `logs/$safeName` where `safeName` is derived
   from the project's canonical display name (family-stable, not per-run random)
2. On first use of a workspace, `git init` is run in that directory
3. After each terminal run event (completed, failed, cancelled, paused), if the workspace has
   uncommitted changes, `git commit` is performed with a descriptive message
4. Iterations (`/iterations` endpoint) reuse the parent project's workspace directory (same
   `displayNameOverride`) rather than creating siblings
5. Behavior is unchanged if `git` is not available on PATH (no crashes, just no git commits)

## Verification Strategy

- No reference implementation — standard git behavior is the spec
- Edge cases:
  - `git` not on PATH → graceful no-op (log warning, continue)
  - workspace already has uncommitted changes from a prior run → `git add -A` + `git commit`
  - workspace has no changes (e.g. run failed before writing anything) → no empty commit
  - workspace directory does not exist yet at commit time → no-op
  - `.git` already initialized → `git init` is idempotent (safe to re-run)
  - two projects with same display name → share directory, share git history (acceptable)
  - git commit identity: use `attractor <attractor@localhost>` as author so no global config needed
- Testing approach: unit tests with temp directories; mock/stub git invocations or use real git
  in temp dirs (git is available in CI); markup-presence assertions for `WorkspaceGit` symbol
  in `WebMonitorServerBrowserApiTest` or dedicated `WorkspaceGitTest`

## Uncertainty Assessment

- Correctness uncertainty: **Low** — well-understood domain (git CLI, file I/O)
- Scope uncertainty: **Medium** — "iteration reuses same directory" requires change to
  `handleCreateIteration()`; need to clarify whether `failed`/`cancelled` runs should also commit
- Architecture uncertainty: **Low** — new `WorkspaceGit` utility object; two call sites in
  `ProjectRunner`; one fix in `RestApiRouter`

## Open Questions

1. Should `failed` and `cancelled` runs also trigger a git commit? (The seed says "completed run"
   but iteration commits are also mentioned — clarify scope of terminal events.)
2. Should the workspace root path prefix be `logs/` (current) or a different configurable path
   like `workspace/`? The seed says "named after the project" but doesn't specify parent dir.
3. Should the git commit message include the run ID, status, stage count, or a human-readable
   summary from the run's display data?
4. When `handleCreateIteration` passes `displayNameOverride`, should it use the parent run's
   `displayName` or the family's first-run display name? (They may differ if the parent was
   itself an iteration with a different name.)
