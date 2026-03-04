# Sprint 021: Git Info Panel on Project Tab

## Overview

Sprint 020 gave every project workspace a git repository — each completed run leaves a commit.
That history now exists but is invisible to the user. This sprint surfaces it.

The approach is **three-layer**: (1) `WorkspaceGit` gains read-only query methods that shell out
the same way the existing write methods do; (2) a new REST endpoint
`GET /api/v1/projects/{id}/git` returns a JSON summary; (3) the project detail tab gains a
collapsible **Git** section in the left panel, showing commit count, last commit message/date,
recent run log, and — crucially — a per-commit diff viewer so users can see exactly what changed
between iterations.

The display is intentionally compact for the initial view (a single "git bar" row beneath the
`#pMeta` metadata line) with a collapsible detail panel that expands inline for users who want
the full history. This follows the existing "Version History" collapsible pattern already present
in `buildPanel()`.

Auto-refresh is triggered by the SSE `applyUpdate()` handler when a project transitions to a
terminal state — the same place the completion flash fires. This means git info stays current
without polling.

## Use Cases

1. **At-a-glance git summary**: A user opens the project tab for `story-writer`. Beneath the
   elapsed/run-ID metadata row they see: `⎇ main  •  4 commits  •  last: "Run run-001 completed:
   3 stages" (2 min ago)`. They immediately know the workspace has 4 iterations committed.

2. **Expand full history**: The user clicks the git bar to expand the collapsible Git section.
   A compact log table appears: commit hash, run message, date, with a "diff" link per row.

3. **View diff between iterations**: The user clicks "diff" on commit 3. A side-by-side (unified)
   diff view opens inside the panel, showing what files changed between commit 2 and 3.

4. **No git, no problem**: On a server without `git` in PATH, the git bar is hidden entirely
   (the API endpoint returns `{"available": false}`).

5. **No commits yet**: A brand-new project that has never completed a run shows the git bar with
   `⎇ main  •  0 commits  •  no history yet` (git repo initialized but no commits).

6. **CLI access**: `curl /api/v1/projects/{id}/git` returns the same JSON, letting automation
   scripts check iteration counts or last commit messages.

7. **Auto-refresh**: When the user is watching a run complete in real time, the git bar updates
   within a second of the terminal SSE event, reflecting the new commit.

## Architecture

```
WorkspaceGit (extended — new read methods)
  ┌─────────────────────────────────────────────────────────────────┐
  │ fun info(dir: String): GitInfo                                  │
  │   → { available, branch, commitCount, lastHash, lastMessage,   │
  │        lastDate, isDirty }                                      │
  │   → all via ProcessBuilder, wrapped in runCatching              │
  │                                                                 │
  │ fun log(dir: String, limit: Int = 10): List<GitCommit>          │
  │   → git log --format="%H|%h|%s|%ai" -N                         │
  │   → each line → GitCommit(hash, shortHash, subject, date)       │
  │                                                                 │
  │ fun diff(dir: String, fromHash: String, toHash: String): String │
  │   → git diff fromHash..toHash                                   │
  │   → returns unified diff text (truncated to 64 KB)             │
  └─────────────────────────────────────────────────────────────────┘

REST API (RestApiRouter — new routes)
  GET /api/v1/projects/{id}/git
    → WorkspaceGit.info(workspaceDir) + WorkspaceGit.log(workspaceDir, 10)
    → JSON: { available, branch, commitCount, lastHash, lastMessage, lastDate,
               isDirty, commits: [{hash, shortHash, subject, date}, ...] }

  GET /api/v1/projects/{id}/git/diff?from={hash}&to={hash}
    → WorkspaceGit.diff(workspaceDir, from, to)
    → text/plain response (unified diff)

UI (WebMonitorServer — project detail tab)

  buildPanel() — add below action bar and prompt block:
    '<div class="git-bar" id="gitBar" style="display:none;" onclick="toggleGitPanel()">'
    + '<span id="gitBarSummary"></span>'
    + '<span class="git-bar-chevron" id="gitBarChevron">▶</span>'
    + '</div>'
    + '<div class="git-panel" id="gitPanel" style="display:none;">'
    +   '<div class="git-log-table" id="gitLogTable"></div>'
    +   '<div class="git-diff-view" id="gitDiffView" style="display:none;"></div>'
    + '</div>'

  updatePanel() — after meta row update:
    loadGitInfo(id);

  loadGitInfo(id)
    → fetch('/api/v1/projects/' + id + '/git')
    → on success: renderGitBar(data)
    → on fail: hide git bar

  renderGitBar(data)
    → if !data.available: hide #gitBar
    → else: build summary string, populate #gitBarSummary, show #gitBar
    → if gitPanelExpanded: renderGitLog(data.commits)

  renderGitLog(commits)
    → build table: shortHash | subject | date | [diff▸]
    → each diff button calls loadGitDiff(hash, prevHash)

  loadGitDiff(toHash, fromHash)
    → fetch('/api/v1/projects/' + selectedId + '/git/diff?from=' + fromHash + '&to=' + toHash)
    → render into #gitDiffView as <pre class="git-diff-pre">

  applyUpdate() — add git refresh trigger:
    if (prevSt !== 'completed' && newSt terminal) {
      loadGitInfo(key);   // refresh after commit is written
    }
    (use 800ms timeout — git commit happens async after SSE)

Data flow:
  ProjectRunner.commitIfChanged()        (Sprint 020 — writes commit)
        ↓ SSE terminal event
  applyUpdate() detects terminal state
        ↓ setTimeout 800ms
  loadGitInfo(id) → GET /api/v1/projects/{id}/git
        ↓
  WorkspaceGit.info() + .log()           (new read methods)
        ↓ JSON response
  renderGitBar() + renderGitLog()        (panel update)
```

### Data Structures (Kotlin)

```kotlin
// WorkspaceGit.kt additions
data class GitInfo(
    val available: Boolean,
    val branch: String = "",
    val commitCount: Int = 0,
    val lastHash: String = "",
    val lastMessage: String = "",
    val lastDate: String = "",
    val isDirty: Boolean = false
)

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val date: String
)
```

### CSS additions (WebMonitorServer.kt)

```css
.git-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; margin: 4px 0 8px;
  background: var(--surface2); border: 1px solid var(--border);
  border-radius: 6px; cursor: pointer; font-size: 0.82rem;
  color: var(--text-muted); user-select: none;
}
.git-bar:hover { border-color: var(--accent); color: var(--text); }
.git-bar-chevron { margin-left: auto; transition: transform 0.2s; }
.git-bar.open .git-bar-chevron { transform: rotate(90deg); }
.git-panel { padding: 8px 0 4px; }
.git-log-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.git-log-table td { padding: 3px 8px; border-bottom: 1px solid var(--border); }
.git-log-table td:first-child { font-family: monospace; color: var(--text-muted); }
.git-diff-pre {
  font-size: 0.75rem; font-family: monospace; overflow-x: auto;
  background: var(--surface2); padding: 8px; border-radius: 4px;
  max-height: 400px; overflow-y: auto; white-space: pre;
}
.git-diff-pre .diff-add { color: #3fb950; }
.git-diff-pre .diff-del { color: #f85149; }
.git-diff-pre .diff-hunk { color: #79c0ff; }
```

## Implementation Plan

### Phase 1: `WorkspaceGit` read methods (~20%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` — Modify

**Tasks:**
- [ ] Add `data class GitInfo(...)` and `data class GitCommit(...)` to the file (or companion
  object scope)
- [ ] Add `fun info(dir: String): GitInfo`:
  - returns `GitInfo(available=false)` if `!gitAvailable` or `.git` not present
  - runs `git branch --show-current` → `branch`
  - runs `git rev-list --count HEAD` → `commitCount` (Int, default 0 if fails or no commits)
  - runs `git log -1 --format=%H|%h|%s|%ai` → parse last commit fields
  - runs `git status --porcelain` → `isDirty = output.isNotEmpty()`
  - all in `runCatching`, returns `GitInfo(available=true, ...)` with defaults on parse error
- [ ] Add `fun log(dir: String, limit: Int = 10): List<GitCommit>`:
  - returns `emptyList()` if `!gitAvailable` or `.git` not present
  - runs `git log --format=%H|%h|%s|%ai -N` where N = limit
  - splits output on newlines, each line split on `|`, maps to `GitCommit`
  - wrapped in `runCatching`, returns `emptyList()` on any error
- [ ] Add `fun diff(dir: String, fromHash: String, toHash: String): String`:
  - returns `""` if `!gitAvailable` or `.git` not present or either hash is blank
  - runs `git diff fromHash..toHash`
  - reads output, truncates to 65536 chars
  - wrapped in `runCatching`, returns `""` on error

---

### Phase 2: REST API endpoint (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] Add route handler for `GET /api/v1/projects/{id}/git` (no sub-path):
  - look up `registry.get(id)` → 404 if not found
  - compute `workspaceDir = "${entry.logsRoot}/workspace"` (same pattern as ProjectRunner)
  - call `WorkspaceGit.info(workspaceDir)` and `WorkspaceGit.log(workspaceDir, 10)`
  - serialize to JSON manually (same pattern as other endpoints — no gson/jackson):
    ```
    {"available":true,"branch":"main","commitCount":4,"lastHash":"abc1234",
     "lastMessage":"Run run-001 completed: 3 stages","lastDate":"2026-03-04 ...",
     "isDirty":false,"commits":[{"hash":"...","shortHash":"...","subject":"...","date":"..."},...]}
    ```
  - Content-Type: `application/json`
- [ ] Add route handler for `GET /api/v1/projects/{id}/git/diff`:
  - parse `from` and `to` query params
  - validate both are non-blank alphanumeric/hex strings (prevent path injection)
  - call `WorkspaceGit.diff(workspaceDir, from, to)`
  - respond with `text/plain; charset=utf-8`, body = diff string (may be empty)
- [ ] Wire both handlers into the existing routing `when` block (path-prefix matching pattern)
- [ ] Ensure both handlers return 404 if `entry.logsRoot` is blank or entry not found

---

### Phase 3: UI — Git bar and panel in project detail tab (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**

#### CSS (add near `.version-history` styles)
- [ ] Add `.git-bar`, `.git-bar:hover`, `.git-bar-chevron`, `.git-bar.open .git-bar-chevron`
- [ ] Add `.git-panel`, `.git-log-table`, `.git-log-table td`, `.git-log-table td:first-child`
- [ ] Add `.git-diff-pre`, `.diff-add`, `.diff-del`, `.diff-hunk`

#### `buildPanel()` — add git DOM structure
- [ ] After the `#projectDesc` div and before the stages card div, add:
  ```javascript
  + '<div class="git-bar" id="gitBar" style="display:none;" onclick="toggleGitPanel()">'
  +   '<span id="gitBarSummary" class="git-bar-summary"></span>'
  +   '<span class="git-bar-chevron" id="gitBarChevron">&#9654;</span>'
  + '</div>'
  + '<div class="git-panel" id="gitPanel" style="display:none;">'
  +   '<table class="git-log-table" id="gitLogTable"></table>'
  +   '<div class="git-diff-view" id="gitDiffView" style="display:none;"></div>'
  + '</div>'
  ```

#### `updatePanel()` — trigger git load
- [ ] After the project description update block, add:
  ```javascript
  loadGitInfo(id);
  ```

#### New JS functions (add in `// ── Git ──` section)
- [ ] `var gitPanelExpanded = false;` module-scope var (reset in `buildPanel()`)
- [ ] `function loadGitInfo(id)`:
  - `fetch('/api/v1/projects/' + encodeURIComponent(id) + '/git')`
  - `.then(function(r){ return r.ok ? r.json() : null; })`
  - `.then(function(data){ if (data) renderGitBar(id, data); })`
  - `.catch(function(){})`  // silent fail
- [ ] `function renderGitBar(id, data)`:
  - if `!data.available`: hide `#gitBar`; return
  - build summary: `'⎇ ' + esc(data.branch || 'main') + '  •  ' + data.commitCount + ' commit' + (data.commitCount !== 1 ? 's' : '')`
  - if `data.commitCount > 0`: append `'  •  last: "' + esc(data.lastMessage.substring(0,60)) + '" (' + timeAgo(data.lastDate) + ')'`
  - else: append `'  •  no history yet'`
  - set `#gitBarSummary.innerHTML`, show `#gitBar`
  - store commits on window: `window._gitCommits = data.commits || []`
  - if `gitPanelExpanded`: `renderGitLog(data.commits)`
- [ ] `function timeAgo(isoDate)`:
  - parses ISO date string, computes seconds since, returns "N min ago", "N hr ago", "N days ago"
- [ ] `function toggleGitPanel()`:
  - `gitPanelExpanded = !gitPanelExpanded`
  - toggle `#gitBar.open` class and `#gitPanel` display
  - if `gitPanelExpanded && window._gitCommits`: `renderGitLog(window._gitCommits)`
  - update chevron rotation via class
- [ ] `function renderGitLog(commits)`:
  - build `<tbody>` rows: `<tr><td>{shortHash}</td><td>{subject}</td><td>{date}</td><td>[diff]</td></tr>`
  - diff link: `<a href="#" onclick="loadGitDiff('{hash}','{prevHash}');return false;">diff▸</a>`
    where `prevHash` = hash of next commit in list (or `''` for oldest)
  - set `#gitLogTable.innerHTML`
- [ ] `function loadGitDiff(toHash, fromHash)`:
  - if `fromHash` is blank: show `#gitDiffView` with "First commit — no previous to diff"
  - else: `fetch('/api/v1/projects/' + encodeURIComponent(currentRunId()) + '/git/diff?from=' + fromHash + '&to=' + toHash)`
  - on success: `renderGitDiff(text)`; on fail: show error in `#gitDiffView`
- [ ] `function renderGitDiff(text)`:
  - split diff into lines, apply per-line color class (`diff-add`, `diff-del`, `diff-hunk`)
  - wrap in `<pre class="git-diff-pre">...</pre>`
  - set `#gitDiffView.innerHTML`, show `#gitDiffView`

#### `applyUpdate()` — refresh git info on terminal transition
- [ ] In the terminal-transition detection block (where completion flash fires), add:
  ```javascript
  if (prevSt !== undefined && newSt && newSt !== prevSt &&
      (newSt === 'completed' || newSt === 'failed' || newSt === 'cancelled' || newSt === 'paused')) {
    if (key === selectedId) {
      setTimeout(function() { loadGitInfo(key); }, 800);
    }
  }
  ```
  (800ms delay allows the server-side git commit to complete before we query)

#### Reset state in `buildPanel()`
- [ ] Set `gitPanelExpanded = false` and `window._gitCommits = null` at top of `buildPanel()`

---

### Phase 4: Tests (~25%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` — Modify
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify

**Tasks:**

#### `WorkspaceGitTest.kt`
- [ ] `info() returns available=false when git not initialized` — temp dir without .git
- [ ] `info() returns correct commitCount after commits` — init repo, write file, commit,
  call `info()`, assert `commitCount == 1`, `branch` non-blank, `lastMessage` non-blank
- [ ] `info() isDirty=true when uncommitted files exist` — after init, write a file without
  committing, call `info()`, assert `isDirty == true`
- [ ] `log() returns empty list when no commits` — init repo, call `log()`, assert empty list
- [ ] `log() returns correct entries after commits` — init, write 2 files in sequence with
  commits, call `log(2)`, assert 2 entries with expected subjects
- [ ] `diff() returns empty string for first commit` — init, commit one file, call
  `diff(dir, "", firstHash)`, assert `""` returned (blank fromHash → no-op)
- [ ] `diff() returns non-empty string between two commits` — init, 2 commits, call
  `diff(dir, hash1, hash2)`, assert output contains "diff --git"

#### `RestApiRouterTest.kt`
- [ ] `GET /api/v1/projects/{id}/git returns 404 for unknown id`
- [ ] `GET /api/v1/projects/{id}/git returns 200 with application/json`
- [ ] Response body contains `"available"` key (may be true or false depending on git availability)
- [ ] `GET /api/v1/projects/{id}/git/diff returns 400 when from/to missing` (or 200 with empty string — decide during impl)

#### `WebMonitorServerBrowserApiTest.kt`
- [ ] `GET /` body contains `loadGitInfo` (JS function present)
- [ ] `GET /` body contains `renderGitBar` (JS function present)
- [ ] `GET /` body contains `toggleGitPanel` (JS function present)
- [ ] `GET /` body contains `git-bar` (CSS class present)
- [ ] `GET /` body contains `gitPanel` (DOM id present)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Modify | Add `GitInfo`/`GitCommit` data classes + `info()`, `log()`, `diff()` read methods |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Add `GET /api/v1/projects/{id}/git` and `.../git/diff` route handlers |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add git bar CSS + DOM structure in `buildPanel()`; `loadGitInfo()`/`renderGitBar()`/`renderGitLog()`/`loadGitDiff()`/`renderGitDiff()`/`toggleGitPanel()` JS; auto-refresh in `applyUpdate()` |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Modify | 7 new read-method tests |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | 4 new git endpoint tests |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | 5 markup-presence assertions |

## Definition of Done

### WorkspaceGit Read Methods
- [ ] `info()` returns `GitInfo(available=false)` when `.git` does not exist or git unavailable
- [ ] `info()` returns correct `commitCount`, `branch`, `lastHash`, `lastMessage`, `lastDate`
  when commits exist
- [ ] `info()` returns `isDirty=true` when unstaged/uncommitted changes are present
- [ ] `log(dir, N)` returns up to N `GitCommit` entries in reverse-chronological order
- [ ] `diff()` returns unified diff text between two commits; returns `""` for blank `fromHash`
- [ ] All methods are no-ops (return empty/false/0) when git unavailable on PATH

### REST API
- [ ] `GET /api/v1/projects/{id}/git` returns `200 application/json` with `available`, `branch`,
  `commitCount`, `lastMessage`, `lastDate`, `isDirty`, `commits` array
- [ ] Returns `404` when project ID not found
- [ ] `GET /api/v1/projects/{id}/git/diff?from={h}&to={h}` returns `200 text/plain` with diff
- [ ] `from`/`to` hashes validated: only hex chars + allowed; returns error on invalid input

### UI
- [ ] Git bar appears on the project detail tab when the project has a workspace with a git repo
- [ ] Git bar is hidden when git is unavailable or the API returns `available=false`
- [ ] Git bar shows: branch, commit count, last commit message (truncated to 60 chars), time ago
- [ ] Clicking the git bar expands the git panel with a commit log table
- [ ] Each row in the log table shows: short hash, subject, date, diff link
- [ ] Clicking "diff▸" on a commit loads and renders the unified diff inline
- [ ] Diff lines are color-coded: green for additions, red for deletions, blue for hunks
- [ ] Git info auto-refreshes 800ms after a terminal SSE event for the active project
- [ ] `gitPanelExpanded` resets to false when switching to a different project tab

### Tests
- [ ] 7 `WorkspaceGitTest` additions pass
- [ ] 4 `RestApiRouterTest` additions pass
- [ ] 5 `WebMonitorServerBrowserApiTest` markup assertions pass
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `git log` output format with `\|` separator clashes with subjects containing `\|` | Low | Medium | Use `--format=%H%x00%h%x00%s%x00%ai` (NUL separator) or `%00` in git format; NUL doesn't appear in subjects |
| Hash validation in diff endpoint is bypassed | Low | High | Use `Regex("[^0-9a-fA-F]")` to reject any non-hex character in from/to; limit length to 40 chars |
| `git diff` on large repos returns multi-MB output | Low | Medium | Truncate response at 65536 bytes before sending; add `Content-Truncated: true` header |
| 800ms delay too short if server is under load | Low | Low | Git commits are local and fast; 800ms is generous; if still stale, user can re-click git bar to re-fetch |
| XSS in commit messages rendered into innerHTML | Medium | High | Use `esc()` helper (already in codebase) on all git data before inserting into HTML |
| `workspaceDir` path — logsRoot may have trailing slash | Low | Low | Use `"${entry.logsRoot.trimEnd('/')}/workspace"` |
| Diff viewer too wide on small screens | Low | Low | `.git-diff-pre { overflow-x: auto }` handles horizontal scroll |

## Security Considerations

- All git data inserted into HTML must go through `esc()` (XSS mitigation).
- The `diff` endpoint accepts `from`/`to` as query params. Both must be validated to be hex strings
  only (git SHA-1/SHA-256 hashes). Any non-hex character → 400 Bad Request. This prevents
  path/command injection via `ProcessBuilder` (even though ProcessBuilder uses arrays, not shell).
- `WorkspaceGit.diff()` uses explicit arg arrays: `["git", "diff", "$fromHash..$toHash"]`. The
  hash validation in the router layer provides defense-in-depth.
- No user-controlled paths reach `ProcessBuilder`; all directories are server-derived from
  `entry.logsRoot` which is set by the server at project creation.
- The git info endpoint is read-only — it does not trigger any writes.

## Dependencies

- Sprint 020 (completed) — provides `WorkspaceGit` and the workspace git infrastructure
- No new Gradle dependencies

## Open Questions

1. Should the diff endpoint use `git diff fromHash..toHash` (two-dot, shows commits between) or
   `git diff fromHash...toHash` (three-dot, shows divergence from merge base)? For a linear
   history of iterations, two-dot is more appropriate.

2. Should the `diff()` method support diffing a single commit (e.g. `HEAD~1..HEAD`) or only
   explicit hashes? Exposing `HEAD~1` style refs risks injection; explicit hashes are safer.

3. When a project has no commits (workspace was just created), should the git bar show at all?
   Proposed: **yes** — show `0 commits • no history yet` so users know the repo is initialized.

4. Should the diff view replace the stages panel or appear below it? Proposed: **below** — inline
   expansion within the git panel keeps all context visible simultaneously.
