# Sprint 021 Intent: Expose Git Information on Project Tab

## Seed

> we should expose the git information for a project to the users, can you give me some options of what would look good on the project's tab when viewing it?

## Context

Sprint 020 introduced `WorkspaceGit` — a utility object that initializes a git repository in each
project's workspace directory and commits after every terminal run event. The git history is the
audit trail for a project's evolution across reruns and iterations.

Currently, this git history is invisible to users in the UI. The project detail tab (the panel that
appears when you click a project tab in the Monitor view) shows: title, status badge, run ID,
elapsed time, original prompt, stages list, version history section, and a graph/live log panel.
No git information is surfaced anywhere.

The seed asks: **what git information should be shown on the project's detail tab, and how should
it look?**

## Recent Sprint Context

- **Sprint 018**: Added completion flash animation and fixed progress bar for completed projects.
  Introduced `dashPipelineData()` (later renamed) shared helper. Pattern: small, targeted JS/CSS
  changes in `WebMonitorServer.kt`.

- **Sprint 019**: Renamed "pipeline" → "project" across all system layers (UI, API, CLI, DB).
  Purely mechanical; established `ProjectRunner`, `ProjectRegistry`, `ProjectState`, `ProjectEntry`
  as the canonical type names.

- **Sprint 020**: Introduced `WorkspaceGit` object — git init on workspace creation, git commit on
  all four terminal events. Each project gets a single canonical workspace at
  `workspace/{safeName}/workspace/`. DB migration for `logs/` → `workspace/` prefix. Key insight:
  git commit messages are structured as `"Run {id} completed: N stages"`, etc.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` — has `init()` and `commitIfChanged()`;
  could gain `log()`, `status()`, `diff()` query methods
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — `buildPanel()` line ~3195, `updatePanel()`
  line ~3304; `#pMeta` div holds metadata; `#pTitle` / `#pStatusBadge` in panel-header
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — existing artifact/file endpoints pattern;
  a new `/api/v1/projects/{id}/git` endpoint (or sub-routes) would fit here
- `src/main/kotlin/attractor/web/ProjectRegistry.kt` — `ProjectEntry` has `logsRoot`; could add
  git metadata fields that get refreshed after each commit
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` — existing test file to extend

## Constraints

- No new Gradle dependencies (use `ProcessBuilder` for git queries, same as existing)
- All git ops must silently no-op when git is unavailable on PATH
- `WorkspaceGit` is an `object` — stateless utility; new query methods follow the same pattern
- Tests: Kotest `FunSpec`, temp dirs via `Files.createTempDirectory()`
- No CLAUDE.md in repo — follow patterns from existing sprints
- The workspace git dir is at `{logsRoot}/workspace/` (subdirectory, not logsRoot itself)

## Git Information Available via CLI

From a workspace git repo, useful data includes:

| Data | Command | Use |
|------|---------|-----|
| Commit count | `git rev-list --count HEAD` | "N commits" = N completed runs |
| Recent log | `git log --oneline -N` | Show run history with messages |
| Last commit hash | `git rev-parse --short HEAD` | Link/identify latest state |
| Last commit date | `git log -1 --format=%ci` | When last run committed |
| Current branch | `git branch --show-current` | Usually "main" or "master" |
| Dirty status | `git status --porcelain` | Uncommitted changes (run in-progress?) |
| File count | `git ls-files \| wc -l` | Files tracked in workspace |

## Success Criteria

- Users can see at-a-glance git information directly on the project detail tab without any extra
  clicks
- The information is fresh (fetched/updated after each terminal run event)
- The display degrades gracefully when git is unavailable or no commits exist yet
- Implementation fits the existing visual language (dark GitHub-inspired theme, same CSS patterns)
- The REST API gains a git info endpoint so CLI/external tools can also access this data

## Verification Strategy

- Reference: no external spec; correctness = git CLI output matches displayed values
- Testing: `WorkspaceGitTest.kt` extended with query method tests (using real temp git repos);
  `RestApiRouterTest.kt` for the new endpoint; `WebMonitorServerBrowserApiTest.kt` for markup
  presence assertions
- Edge cases: no commits yet, git not on PATH, blank logsRoot, workspace dir doesn't exist

## Uncertainty Assessment

- **Correctness uncertainty**: Low — straightforward git CLI queries via ProcessBuilder
- **Scope uncertainty**: Medium — many options exist for what to show; need to pick the right set
- **Architecture uncertainty**: Low — clear pattern from WorkspaceGit and RestApiRouter

## Open Questions

1. **What information to show?** Options range from minimal (commit count + last commit message) to
   rich (inline log table, diff preview, file tree). What level of detail is right for v1?

2. **Where to place it?** Options: (a) inline in the `#pMeta` row, (b) a new collapsible "Git"
   section below the stages card, (c) a third right-panel tab alongside "Graph" and "Live Log",
   (d) a new "Git" card in the left panel.

3. **Update trigger?** Should git info auto-refresh via SSE when a project completes, or is
   on-demand (fetch on tab open + refresh button) sufficient for v1?

4. **API endpoint shape?** Single `/git` endpoint returning a JSON object, or multiple sub-routes
   (`/git/log`, `/git/status`, `/git/diff/{commit}`)?

5. **Diff exposure?** Should users be able to see what changed between iterations (git diff)?
   This is potentially the most valuable feature but also the most complex.
