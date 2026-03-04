# Sprint 021: Project Git History Panel

## Overview

Sprint 020 gave every project workspace a git repository — a commit is recorded after each
terminal run event. The audit trail exists but is invisible in the UI. Users must drop to a shell
and `git log workspace/{name}/workspace/` to see what changed across iterations.

This sprint surfaces that information directly in the project detail tab. A collapsible **Git**
bar appears in the left panel (between the prompt/description block and the Stages card),
showing at a glance: branch name, commit count, and the last commit message with a relative
time. Clicking the bar expands an inline commit log table listing the five most recent commits
with their short hash, subject, and date.

Data is served by a new `GET /api/v1/projects/{id}/git` REST endpoint — the same JSON feed
powers both the UI and any external CLI/automation consumers. The endpoint handles all degraded
states cleanly: git not on PATH, workspace repo not yet initialized, or no commits yet. The UI
renders explicit status text in each case rather than silently hiding the section.

All git reads use `ProcessBuilder` (same pattern as `WorkspaceGit`'s existing write methods) —
no new Gradle dependencies. A single new `summary()` method is added to `WorkspaceGit`,
returning a `GitSummary` data class that bundles all needed fields in one call.

## Use Cases

1. **At-a-glance git summary**: A user opens the project tab for `story-writer`. Below the prompt
   block they see a compact bar: `⎇ main  •  4 commits  •  last: "Run run-001 completed: 3 stages"
   (2 min ago)`. They immediately know this workspace has 4 iterations committed.

2. **Expand commit log**: The user clicks the bar. It expands to show a table of the 5 most recent
   commits: short hash, run message, and date — one row per completed/failed/cancelled run.

3. **No commits yet**: A project that just started its first run shows `⎇ main  •  0 commits  •
   no history yet` (repo initialized by Sprint 020 but no terminal event yet).

4. **Git unavailable**: On a server without `git` in PATH, the bar shows `Git unavailable — workspace
   history requires git on PATH`. The section is always visible; the state is explained.

5. **Auto-refresh after run completes**: The user watches a run complete in real time. 500ms after
   the terminal SSE event, the git bar updates to reflect the new commit — no page reload needed.

6. **Manual refresh**: A refresh button in the bar header lets the user re-fetch git info on demand.

7. **CLI / automation access**: `curl /api/v1/projects/{id}/git` returns the same JSON payload,
   including commit count, branch, recent commit list, and dirty flag.

## Architecture

```
WorkspaceGit (extended — one new method)
  ┌──────────────────────────────────────────────────────────────┐
  │ data class GitCommit(hash, shortHash, subject, date)         │
  │ data class GitSummary(                                       │
  │   available: Boolean,      // git binary on PATH            │
  │   repoExists: Boolean,     // workspace/.git present        │
  │   branch: String,          // git branch --show-current     │
  │   commitCount: Int,        // git rev-list --count HEAD     │
  │   lastCommit: GitCommit?,  // git log -1                    │
  │   dirty: Boolean,          // git status --porcelain != ""  │
  │   trackedFiles: Int,       // git ls-files | wc -l          │
  │   recent: List<GitCommit>  // git log --format= -N          │
  │ )                                                            │
  │                                                              │
  │ fun summary(dir: String, recentLimit: Int = 5): GitSummary  │
  │   all via ProcessBuilder, all in runCatching → safe defaults │
  └──────────────────────────────────────────────────────────────┘

REST API (RestApiRouter — one new route)
  GET /api/v1/projects/{id}/git
    → registry.get(id) → 404 if not found
    → workspaceDir = "${entry.logsRoot}/workspace"
    → WorkspaceGit.summary(workspaceDir)
    → JSON response (200 always for known ids)

    Response shape:
    {
      "available": true,
      "repoExists": true,
      "branch": "main",
      "commitCount": 4,
      "lastCommit": {
        "hash": "a1b2c3d4e5f6...",
        "shortHash": "a1b2c3d",
        "subject": "Run run-001 completed: 3 stages",
        "date": "2026-03-04 14:22:10 -0800"
      },
      "dirty": false,
      "trackedFiles": 12,
      "recent": [
        {"hash":"...","shortHash":"a1b2c3d","subject":"Run run-001 completed: 3 stages","date":"..."},
        ...
      ]
    }

    Degraded states:
      git binary absent → { "available": false, "repoExists": false, commitCount: 0, recent: [] }
      .git missing      → { "available": true,  "repoExists": false, commitCount: 0, recent: [] }
      no commits        → { "available": true,  "repoExists": true,  commitCount: 0, lastCommit: null, recent: [] }

UI (WebMonitorServer — project detail tab)

  buildPanel() — add git section DOM between #projectDesc and .card (Stages):
    '<button class="git-bar" id="gitBar" onclick="toggleGitPanel()">'
    +  '<span id="gitBarSummary">Loading git info...</span>'
    +  '<span class="git-bar-right">'
    +    '<button class="git-refresh-btn" id="gitRefreshBtn" onclick="event.stopPropagation();refreshGitInfo()" title="Refresh git info">&#8635;</button>'
    +    '<span class="git-bar-chevron" id="gitBarChevron">&#9654;</span>'
    +  '</span>'
    + '</button>'
    + '<div class="git-panel" id="gitPanel" style="display:none;">'
    +   '<table class="git-log-table" id="gitLogTable"></table>'
    + '</div>'

  Data flow:
    buildPanel(id)
      → loadGitInfo(id)           ← fetch on tab open

    applyUpdate() terminal transition detection
      → if (key === selectedId) setTimeout(() => loadGitInfo(key), 500)
                                  ← fetch 500ms after terminal SSE event

    refreshGitInfo()              ← manual refresh button

    loadGitInfo(id):
      fetch('/api/v1/projects/' + id + '/git')
      → renderGitBar(data)

    renderGitBar(data):
      if !data.available:
        text = "⎇ Git unavailable — workspace history requires git on PATH"
      else if !data.repoExists:
        text = "⎇ Git repo not initialized"
      else if data.commitCount === 0:
        text = "⎇ " + (data.branch || "main") + "  •  0 commits  •  no history yet"
      else:
        text = "⎇ " + branch + "  •  N commits  •  last: \"...\" (X ago)"
      set #gitBarSummary
      if gitPanelExpanded: renderGitLog(data.recent)

    toggleGitPanel():
      toggle #gitPanel visibility + chevron rotation
      if newly expanded && window._gitData: renderGitLog(window._gitData.recent)

    renderGitLog(commits):
      build <tbody> rows: shortHash | subject | date
      set #gitLogTable.innerHTML

  Reset in buildPanel():
    gitPanelExpanded = false; window._gitData = null;
```

## Implementation Plan

### Phase 1: `WorkspaceGit.summary()` method (~25%)

**Files:**
- `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` — Modify

**Tasks:**
- [ ] Add `data class GitCommit(val hash: String, val shortHash: String, val subject: String, val date: String)` at package/companion scope
- [ ] Add `data class GitSummary(...)` with all fields listed in Architecture section
- [ ] Add `fun summary(dir: String, recentLimit: Int = 5): GitSummary`:
  - Return `GitSummary(available=false, repoExists=false, ...)` (all defaults) if `!gitAvailable`
  - Return `GitSummary(available=true, repoExists=false, ...)` if `.git` not present in `dir`
  - Run `git branch --show-current` → `branch` (default `""` if fails)
  - Run `git rev-list --count HEAD` → `commitCount` (parse as Int, default `0`; zero if repo
    has no commits — "fatal: bad default revision" → catch, return 0)
  - Run `git log -1 --format=%H%x09%h%x09%s%x09%ai` → parse tab-separated fields → `lastCommit`
    (null if no commits or parse fails)
  - Run `git status --porcelain` → `dirty = output.trim().isNotEmpty()`
  - Run `git ls-files | wc -l` → `trackedFiles` (parse trimmed Int, default 0)
    Note: use `ProcessBuilder("git", "ls-files")` piped via `inputStream`, count lines in Kotlin
    (do NOT rely on shell pipe — use `inputStream.bufferedReader().lines().count()`)
  - Run `git log --format=%H%x09%h%x09%s%x09%ai -N` → split on newlines → parse → `recent`
  - All subprocess calls use `.directory(File(dir))`, `.redirectErrorStream(true)`, `.waitFor(10, TimeUnit.SECONDS)`
  - Entire method wrapped in outer `runCatching` returning `GitSummary(available=true, repoExists=true)` on any unexpected error
  - Use tab `\t` separator (0x09) in format strings to avoid collision with subjects containing `|`

---

### Phase 2: REST endpoint (`GET /api/v1/projects/{id}/git`) (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] Add route handler matching `GET /api/v1/projects/{id}/git` (before the existing fallthrough
  to `handleGetProjectFiles` — be careful with prefix ordering)
- [ ] Implement `handleGetProjectGit(exchange, id)`:
  - `registry.get(id)` → 404 JSON error if not found (same pattern as other handlers)
  - `val workspaceDir = "${entry.logsRoot.trimEnd('/')}/workspace"`
  - `val git = WorkspaceGit.summary(workspaceDir)`
  - Build JSON response manually (no new dependencies — string interpolation + escaping):
    ```kotlin
    val lastCommitJson = if (git.lastCommit != null) """
      {"hash":${jsonStr(git.lastCommit.hash)},"shortHash":${jsonStr(git.lastCommit.shortHash)},
       "subject":${jsonStr(git.lastCommit.subject)},"date":${jsonStr(git.lastCommit.date)}}
    """.trimIndent() else "null"
    val recentJson = git.recent.joinToString(",") { c ->
      """{"hash":${jsonStr(c.hash)},"shortHash":${jsonStr(c.shortHash)},
          "subject":${jsonStr(c.subject)},"date":${jsonStr(c.date)}}"""
    }
    val body = """{"available":${git.available},"repoExists":${git.repoExists},
      "branch":${jsonStr(git.branch)},"commitCount":${git.commitCount},
      "lastCommit":$lastCommitJson,"dirty":${git.dirty},
      "trackedFiles":${git.trackedFiles},"recent":[$recentJson]}"""
    ```
  - Where `jsonStr(s: String)` is a local helper escaping `\`, `"`, and control chars
  - Content-Type: `application/json`, status `200`
  - Return `200` with degraded payload even when `logsRoot` is blank (entry found but no workspace
    yet → `summary("")` returns `repoExists=false`)
  - Return `404` only when project `id` is not found in registry
- [ ] Wire into the routing `when` block at the correct position

---

### Phase 3: UI — Git bar and panel (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**

#### CSS — add near `.version-history` styles
- [ ] `.git-bar`:
  ```css
  display:flex;align-items:center;justify-content:space-between;gap:8px;
  width:100%;padding:8px 12px;margin:0 0 8px;box-sizing:border-box;
  background:var(--surface2);border:1px solid var(--border);border-radius:6px;
  cursor:pointer;font-size:0.82rem;color:var(--text-muted);
  text-align:left;user-select:none;
  ```
- [ ] `.git-bar:hover { border-color:var(--accent); color:var(--text); }`
- [ ] `.git-bar.open { border-color:var(--accent); }`
- [ ] `.git-bar-right { display:flex;align-items:center;gap:6px;flex-shrink:0; }`
- [ ] `.git-bar-chevron { font-size:0.7rem;transition:transform 0.2s; }`
- [ ] `.git-bar.open .git-bar-chevron { transform:rotate(90deg); }`
- [ ] `.git-refresh-btn`:
  ```css
  background:none;border:none;color:var(--text-muted);cursor:pointer;
  font-size:0.85rem;padding:0 2px;line-height:1;
  ```
- [ ] `.git-refresh-btn:hover { color:var(--accent); }`
- [ ] `.git-panel { padding:0 0 8px; }`
- [ ] `.git-log-table { width:100%;border-collapse:collapse;font-size:0.79rem; }`
- [ ] `.git-log-table td { padding:4px 8px;border-bottom:1px solid var(--border);vertical-align:top; }`
- [ ] `.git-log-table td:first-child { font-family:monospace;color:var(--text-muted);white-space:nowrap; }`
- [ ] `.git-log-table td:last-child { color:var(--text-muted);white-space:nowrap;font-size:0.75rem; }`

#### `buildPanel()` — add git DOM structure
- [ ] After `#projectDesc` div, before the stages `.card` div, insert:
  ```javascript
  + '<button class="git-bar" id="gitBar" onclick="toggleGitPanel()">'
  +   '<span id="gitBarSummary" style="flex:1;">Loading git info\u2026</span>'
  +   '<span class="git-bar-right">'
  +     '<button class="git-refresh-btn" id="gitRefreshBtn" onclick="event.stopPropagation();refreshGitInfo()" title="Refresh git info">\u21bb</button>'
  +     '<span class="git-bar-chevron" id="gitBarChevron">\u25b6</span>'
  +   '</span>'
  + '</button>'
  + '<div class="git-panel" id="gitPanel" style="display:none;">'
  +   '<table class="git-log-table"><tbody id="gitLogTable"></tbody></table>'
  + '</div>'
  ```
- [ ] At the top of `buildPanel()`, add:
  ```javascript
  gitPanelExpanded = false;
  window._gitData = null;
  ```
- [ ] At the end of `buildPanel()`, add:
  ```javascript
  loadGitInfo(id);
  ```

#### New module-scope var
- [ ] `var gitPanelExpanded = false;` near other module-scope vars

#### New JS functions (add in a `// ── Git ──` section)
- [ ] `function loadGitInfo(id)`:
  ```javascript
  function loadGitInfo(id) {
    if (!id || id === DASHBOARD_TAB_ID) return;
    fetch('/api/v1/projects/' + encodeURIComponent(id) + '/git')
      .then(function(r) { return r.ok ? r.json() : null; })
      .then(function(data) { if (data && id === selectedId) { window._gitData = data; renderGitBar(data); } })
      .catch(function() { /* silent */ });
  }
  ```
- [ ] `function refreshGitInfo()`:
  ```javascript
  function refreshGitInfo() {
    var id = currentRunId ? currentRunId() : selectedId;
    if (id) loadGitInfo(id);
  }
  ```
  Note: use the same `currentRunId()` helper used elsewhere in the panel to get the active run ID
- [ ] `function renderGitBar(data)`:
  - `if (!data.available)` → set `#gitBarSummary` to muted text `'⎇ Git unavailable — workspace history requires git on PATH'`
  - `else if (!data.repoExists)` → `'⎇ Git repo not initialized'`
  - `else if (data.commitCount === 0)` → `'\u2387 ' + esc(data.branch || 'main') + '  \u2022  0 commits  \u2022  no history yet'`
  - else build: `'\u2387 ' + esc(data.branch) + '  \u2022  ' + data.commitCount + ' commit' + (data.commitCount !== 1 ? 's' : '') + '  \u2022  last: \u201c' + esc((data.lastCommit.subject || '').substring(0, 55)) + (data.lastCommit.subject.length > 55 ? '\u2026' : '') + '\u201d (' + timeAgo(data.lastCommit.date) + ')'`
  - Set `document.getElementById('gitBarSummary').textContent` (use textContent — no innerHTML for git data)
  - If `gitPanelExpanded` and `data.recent`: `renderGitLog(data.recent)`
- [ ] `function timeAgo(isoDate)`:
  - Parse `new Date(isoDate)`, compute seconds difference, return:
    - `< 60s` → `'just now'`
    - `< 3600s` → `N + ' min ago'`
    - `< 86400s` → `N + ' hr ago'`
    - else → `N + ' day' + (N !== 1 ? 's' : '') + ' ago'`
  - Return `''` on any error
- [ ] `function toggleGitPanel()`:
  ```javascript
  function toggleGitPanel() {
    gitPanelExpanded = !gitPanelExpanded;
    var bar = document.getElementById('gitBar');
    var panel = document.getElementById('gitPanel');
    if (bar) { if (gitPanelExpanded) bar.classList.add('open'); else bar.classList.remove('open'); }
    if (panel) panel.style.display = gitPanelExpanded ? '' : 'none';
    if (gitPanelExpanded && window._gitData && window._gitData.recent) {
      renderGitLog(window._gitData.recent);
    }
  }
  ```
- [ ] `function renderGitLog(commits)`:
  ```javascript
  function renderGitLog(commits) {
    var tb = document.getElementById('gitLogTable');
    if (!tb) return;
    if (!commits || commits.length === 0) {
      tb.innerHTML = '<tr><td colspan="3" style="color:var(--text-muted);font-style:italic;">No commits yet.</td></tr>';
      return;
    }
    var html = '';
    for (var i = 0; i < commits.length; i++) {
      var c = commits[i];
      html += '<tr>'
        + '<td>' + esc(c.shortHash) + '</td>'
        + '<td>' + esc(c.subject) + '</td>'
        + '<td>' + esc(timeAgo(c.date)) + '</td>'
        + '</tr>';
    }
    tb.innerHTML = html;
  }
  ```

#### `applyUpdate()` — trigger git refresh on terminal transition
- [ ] In the existing terminal-transition detection block (where `flashDashCard` fires), add:
  ```javascript
  if (prevSt !== undefined && newSt && newSt !== prevSt &&
      (newSt === 'completed' || newSt === 'failed' || newSt === 'cancelled' || newSt === 'paused')) {
    if (key === selectedId) {
      var _refreshId = key;
      setTimeout(function() { loadGitInfo(_refreshId); }, 500);
    }
  }
  ```

---

### Phase 4: Documentation update (~5%)

**Files:**
- `docs/api/rest-v1.md` — Modify

**Tasks:**
- [ ] Add section for `GET /api/v1/projects/{id}/git` with:
  - Description, request format, response shape (all fields with types and meanings)
  - Degraded state examples (git unavailable, no repo, no commits)
  - Note that `404` is returned only for unknown project IDs; known IDs always return 200

---

### Phase 5: Tests (~15%)

**Files:**
- `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` — Modify
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify

**Tasks:**

#### `WorkspaceGitTest.kt`
- [ ] `summary() returns available=false when git not available` — mock unavailable state or
  pass a non-directory path; assert `available == false` (use existing `gitOnPath` guard pattern)
- [ ] `summary() returns repoExists=false on dir without .git` — create temp dir (no init);
  assert `available == true`, `repoExists == false`, `commitCount == 0`
- [ ] `summary() returns commitCount=0 on initialized repo with no commits` — call `WorkspaceGit.init(dir)`, then `summary(dir)`; assert `repoExists == true`, `commitCount == 0`, `lastCommit == null`, `recent.isEmpty()`
- [ ] `summary() returns correct values after one commit` — init, write file, `commitIfChanged(dir, "test: first run")`, call `summary(dir)`; assert `commitCount == 1`, `lastCommit != null`, `lastCommit.subject == "test: first run"`, `recent.size == 1`
- [ ] `summary() returns recent list capped at recentLimit` — init, make 7 commits, call `summary(dir, 5)`; assert `recent.size == 5`, `commitCount == 7`
- [ ] `summary() dirty=true when uncommitted files present` — init, write file without committing; assert `dirty == true`
- [ ] `summary() dirty=false after commit` — init, write file, commit; assert `dirty == false`

#### `RestApiRouterTest.kt`
- [ ] `GET /api/v1/projects/{id}/git returns 404 for unknown id`
- [ ] `GET /api/v1/projects/{id}/git returns 200 with application/json for known id`
- [ ] Response body contains `"available"` key
- [ ] Response body contains `"commitCount"` key
- [ ] Response body contains `"recent"` key

#### `WebMonitorServerBrowserApiTest.kt`
- [ ] `GET /` body contains `loadGitInfo`
- [ ] `GET /` body contains `renderGitBar`
- [ ] `GET /` body contains `toggleGitPanel`
- [ ] `GET /` body contains `git-bar` (CSS class)
- [ ] `GET /` body contains `gitPanel` (DOM id)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/workspace/WorkspaceGit.kt` | Modify | Add `GitCommit`/`GitSummary` data classes + `summary()` read method |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Add `GET /api/v1/projects/{id}/git` route handler |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Git bar CSS + DOM in `buildPanel()`; `loadGitInfo()`, `renderGitBar()`, `renderGitLog()`, `toggleGitPanel()`, `timeAgo()` JS functions; terminal-state refresh in `applyUpdate()` |
| `docs/api/rest-v1.md` | Modify | Document new git endpoint |
| `src/test/kotlin/attractor/workspace/WorkspaceGitTest.kt` | Modify | 7 new `summary()` tests |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | 5 new git endpoint tests |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | 5 markup-presence assertions |

## Definition of Done

### WorkspaceGit
- [ ] `GitCommit` and `GitSummary` data classes defined
- [ ] `summary()` returns `available=false` when git binary is absent
- [ ] `summary()` returns `repoExists=false` when `.git` does not exist in workspace dir
- [ ] `summary()` returns `commitCount=0`, `lastCommit=null`, `recent=[]` on a fresh repo with no commits
- [ ] `summary()` returns correct `commitCount`, `lastCommit`, `recent`, `dirty`, `trackedFiles` after commits
- [ ] `recent` list is capped at `recentLimit` (default 5), ordered newest-first
- [ ] Uses tab-separator (`%x09`) in `git log --format` to avoid pipe-in-subject collisions
- [ ] All subprocess calls are no-op safe: no exceptions leak from `summary()`
- [ ] `trackedFiles` counted via Kotlin line-count on `git ls-files` output (no shell pipe)

### REST API
- [ ] `GET /api/v1/projects/{id}/git` returns `404` for unknown project ID
- [ ] Returns `200 application/json` for known project IDs (even if `logsRoot` is blank)
- [ ] Response includes all fields: `available`, `repoExists`, `branch`, `commitCount`,
  `lastCommit` (object or null), `dirty`, `trackedFiles`, `recent` (array)
- [ ] JSON values for git data are properly escaped (no injection via commit subjects)

### UI
- [ ] Git bar appears on project detail tab for all projects
- [ ] Shows `⎇ Git unavailable — ...` when `available=false`
- [ ] Shows `⎇ Git repo not initialized` when `repoExists=false`
- [ ] Shows `⎇ {branch} • 0 commits • no history yet` when `commitCount=0`
- [ ] Shows branch, commit count, last commit message (≤55 chars + ellipsis), time-ago when commits exist
- [ ] Clicking bar toggles chevron rotation and shows/hides `#gitPanel`
- [ ] Expanded panel renders commit log table: short hash, subject, time-ago per row
- [ ] Manual refresh button re-fetches without reloading page
- [ ] Git info auto-refreshes 500ms after terminal SSE event for the active project
- [ ] `gitPanelExpanded` resets to `false` on tab switch (`buildPanel()` reset)
- [ ] All git text rendered with `esc()` (no XSS via commit subjects)
- [ ] `gitBarSummary` uses `.textContent` not `.innerHTML`

### Documentation
- [ ] `docs/api/rest-v1.md` documents `GET /api/v1/projects/{id}/git` with full response schema

### Tests
- [ ] 7 `WorkspaceGitTest` additions pass (git-dependent tests guarded with `gitOnPath` check)
- [ ] 5 `RestApiRouterTest` additions pass
- [ ] 5 `WebMonitorServerBrowserApiTest` markup assertions pass
- [ ] All existing tests pass (zero regressions)
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `git ls-files` output counted in Kotlin instead of shell wc — blank output on no-files repo | Low | Low | `lines().count()` on empty output returns 0; safe default |
| `git log --format` tab separator clashes if subject contains a tab | Very Low | Low | Commit messages are server-generated (`"Run {id} completed: N stages"`); no tabs |
| XSS via commit subjects in HTML | Low | High | All git strings go through `esc()` and/or `.textContent`; never raw `.innerHTML` |
| Over-fetch during rapid SSE updates | Low | Medium | `loadGitInfo()` is only called in `buildPanel()`, refresh button, and terminal transition (not `updatePanel()`) |
| 500ms delay too short if git subprocess is slow on loaded server | Low | Low | Git log/status are read-only and fast; 500ms is a buffer for the write commit subprocess |
| Route ordering conflict (new git route captured by existing `/projects/{id}/...` prefix handler) | Low | High | Add git route handler before any existing prefix handler that matches the same segment |
| `workspaceDir` is blank when `logsRoot` is blank — `summary("")` called | Low | Low | `summary()` returns `repoExists=false` for non-directory path; no crash |

## Security Considerations

- All git data inserted into HTML uses `esc()` or `.textContent` — no XSS via commit subjects.
- The git endpoint is read-only. No user-controlled strings reach `ProcessBuilder` command arrays;
  `workspaceDir` is derived from `entry.logsRoot` which is server-set at project creation.
- No diff content is exposed in this sprint — reduces risk of accidentally leaking sensitive file
  content through the API.
- JSON serialization of git strings uses a local `jsonStr()` helper that escapes `\`, `"`, and
  control characters.

## Dependencies

- Sprint 020 (completed) — provides `WorkspaceGit` and per-project workspace git infrastructure
- No new Gradle dependencies

## Open Questions

1. Should `trackedFiles` be included in the UI display or only in the API response? Proposed:
   **API only for v1** — the bar summary is already information-dense; tracked file count can be
   added to the expanded panel in a follow-up.

2. Should the git endpoint also list the `workspacePath` for debugging? Proposed: **no** —
   exposing server filesystem paths is unnecessary; the path is always derivable from `logsRoot`.

3. Diff viewer: deferred to a follow-up sprint as explicitly agreed in the interview phase.
