# Critique: SPRINT-020-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is strong on intent alignment and scope containment. It identifies the real root cause
for sibling workspaces (`handleCreateIteration` missing `displayNameOverride`) and keeps
implementation focused on a small set of files.

The key gaps are correctness details in runner naming/flow references and a few execution-safety
assumptions around git initialization timing and test strategy.

## High-Priority Fixes

1. **Runner naming/flow references are stale in multiple places**
- Draft text repeatedly says "currently `PipelineRunner.kt`" and refers to `runPipeline()`.
- Current code is already post-019: file is `ProjectRunner.kt` and function is `runProject()`.
- Why this matters: planning docs should be implementation-ready against current tree.
- Required correction:
  - Replace stale references with current symbols (`ProjectRunner.kt`, `runProject()`).

2. **"Two commit call sites" is incorrect for drafted behavior**
- Overview says "two commit call sites in `ProjectRunner`".
- The plan itself commits on four terminal branches: completed/failed/cancelled/paused.
- Why this matters: mismatch between summary and implementation can cause under-scoped delivery.
- Required correction:
  - Update summary language to "terminal-event call sites" or explicitly "four terminal branches".

3. **Init timing can miss first-run commits when workspace directory does not yet exist**
- Draft `init()` no-ops if dir is absent and assumes init can happen on next run.
- But sprint intent calls for git init on first workspace use; first run may produce changes that
  then cannot be committed if `.git` was never initialized before commit.
- Required correction:
  - Ensure repo exists before terminal commit attempt (either create dir in init path, or
    re-attempt init inside `commitIfChanged` when `.git` is missing but directory exists).

## Medium-Priority Improvements

1. **Test guidance mixes frameworks (`assumeTrue`)**
- Draft suggests `assumeTrue` in Kotest tests.
- Current test suite is Kotest `FunSpec`; use Kotest assumptions/conditional test logic instead of
  JUnit-style helpers.

2. **One rationale line is technically inaccurate**
- Draft says commit should come after `store.updateLog` "so final log content is written to disk
  before git stages it".
- `store.updateLog` writes DB state, not workspace artifact files.
- Better rationale: keep persistence ordering deterministic for run terminal state handling.

3. **`WebMonitorServerBrowserApiTest` mention is unnecessary noise**
- The draft already recognizes `WorkspaceGit` is server-only.
- Optional symbol-presence assertions in browser markup are not meaningful for this sprint’s core
  behavior and can be dropped from required scope.

## What’s Strong

- Correct root-cause fix for iteration workspace drift.
- Good separation of utility (`WorkspaceGit`) from runner/router integration points.
- Appropriate constraints: no new dependencies, `ProcessBuilder` only, graceful no-op without git.
- Clear DoD around no-empty-commit behavior and terminal event coverage.

## Bottom Line

This draft is close to implementation-ready. Fixing stale naming references, correcting commit-call
scope language, and tightening first-run init/commit behavior will remove the primary execution
risks.
