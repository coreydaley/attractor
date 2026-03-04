# Sprint 021 Merge Notes

## Claude Draft Strengths

- Thorough JS function breakdown with concrete code snippets
- Good security analysis (XSS via `esc()`, hash validation for diff endpoint)
- Detailed CSS specification
- Clear per-function test coverage mapping
- `timeAgo()` helper is a nice UX touch for last-commit display

## Codex Draft Strengths

- Cleaner `summary()` single-method API instead of separate `info()` + `log()` (less surface area)
- `available` vs `repoExists` separation in response is more accurate architecture
- Includes `trackedFiles` field — useful diagnostic info
- Manual refresh button in card header — good UX pattern
- Visible "Git card" with explicit empty/unavailable states is better than silently hiding
- Includes `docs/api/rest-v1.md` update in scope
- More conservative scope: summary-focused, no separate log/diff endpoints

## Valid Critiques Accepted

1. **Diff endpoint removed**: User explicitly confirmed log-only for v1. Diff endpoint and viewer
   removed from scope entirely.

2. **`loadGitInfo()` moved out of `updatePanel()`**: Codex is right that `updatePanel()` fires
   frequently during SSE updates. Git fetch should happen only on explicit triggers:
   - Tab build (`buildPanel()`)
   - Manual refresh button click
   - Terminal-state transition detection in `applyUpdate()`
   Cache last git payload per `selectedId` to avoid stale-render during normal updates.

3. **`available` vs `repoExists` separated**: Codex's response shape is better. Use both fields.

4. **Visible Git card in all states**: Rather than hiding `#gitBar` entirely when unavailable,
   show an explicit status. For "git unavailable" show a muted message; for "no commits yet" show
   a different message. The card header always renders, body shows appropriate state.

5. **800ms delay reduced / made conditional**: Codex notes the commit happens before `onUpdate()`.
   Use a shorter 500ms delay as a safety buffer (git subprocess timing), not 800ms.
   Keep it as an explicit design choice with a comment.

6. **`docs/api/rest-v1.md` update**: Add to scope.

## Critiques Rejected

- **Codex UI placement (full card below Version History)**: User confirmed the compact clickable
  git bar approach during interview. Codex's card-first approach is slightly heavier. **Decision**:
  keep the clickable summary bar that expands inline (like Version History pattern), but ensure
  the unavailable/empty states are clearly visible text rather than hidden.

- **Drop the expandable pattern**: Codex's draft doesn't show a collapsible; it implies always-
  visible content. The user picked the collapsible bar. **Decision**: keep collapsible, but show
  a visible collapsed state with summary text even when expanded=false.

## Interview Refinements Applied

- **Log only, no diff**: Confirmed. Diff endpoint + viewer removed from all phases.
- **REST API endpoint included**: Confirmed. `GET /api/v1/projects/{id}/git` in scope.
- **Git bar + collapsible log placement**: Confirmed. Left panel, between prompt and stages.

## Final Decisions

1. Single `summary(dir, recentLimit=5)` method on `WorkspaceGit` returning `GitSummary` data class
2. `GitSummary` includes: `available`, `repoExists`, `branch`, `commitCount`, `lastCommit`
   (nullable `GitCommit`), `dirty`, `trackedFiles`, `recent: List<GitCommit>`
3. `GitCommit`: `hash`, `shortHash`, `subject`, `date`
4. One REST endpoint: `GET /api/v1/projects/{id}/git` returning `GitSummary` as JSON
5. UI: collapsible git bar in left panel (below prompt/description, above stages card)
6. Git info loaded in `buildPanel()` on tab open + manual refresh + terminal SSE trigger
7. Terminal trigger uses 500ms delay (git subprocess buffer)
8. Visible state in all conditions: "Git unavailable", "No commits yet", summary when available
9. Include `docs/api/rest-v1.md` documentation update
10. Test coverage: `WorkspaceGitTest` (query methods), `RestApiRouterTest` (endpoint),
    `WebMonitorServerBrowserApiTest` (markup presence)
