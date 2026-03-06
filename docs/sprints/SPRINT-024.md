# Sprint 024: Documentation Audit and Update

## Overview

Twenty-three sprints of feature development have produced a set of documentation drift points across `README.md`, `docs-site/content/`, and `docs/api/rest-v1.md`. The most significant gaps come from three sprints: Sprint 021 added a Git History Panel and a new REST endpoint that were never reflected in the user-facing docs; Sprint 022 removed the in-app `/docs` endpoint entirely, but the README still describes it as a built-in four-tab window; and the recent system-tool UI rename from `dot` to `graphviz` was never propagated to the written documentation. A chore upgrade to Java 25 was also not reflected in the Docker docs. Additionally, `docs/api/rest-v1.md` duplicates the Hugo REST API reference with a wrong default port (8080 vs 7070), and the user has decided to delete it in favour of the Hugo site as the single canonical REST reference.

This sprint is **documentation-only**. No code changes. Every edit is verified against `RestApiRouter.kt`, `WebMonitorServer.kt`, `CLAUDE.md`, and `Dockerfile.base` before being written. The deliverable is a fully accurate documentation set that any new user can read with confidence.

## Use Cases

1. **New user reads README**: They find correct system requirements (Java 25, graphviz), an accurate endpoint count, no stale reference to an in-app docs window, and a clear pointer to the Hugo docs site.

2. **User explores the web UI**: They open the web-app docs and discover the Git History Panel — its collapsed/expanded views, degraded states, auto-refresh behaviour, and the `git` PATH requirement.

3. **User asks "what DOT shapes can I use?"**: The dot-format docs now list all supported node shapes, including parallel fan-out, fan-in, tool, and stack manager loop types.

4. **User views Docker docs**: "Java 25 JRE" matches the actual `eclipse-temurin:25-jre-noble` base image.

5. **Contributor checks REST reference**: `docs/api/rest-v1.md` is gone; a `docs/api/README.md` stub (or the README itself) directs them to the Hugo site. No more port-mismatch confusion.

## Architecture

This sprint treats code as the authoritative truth and updates docs to match it.

**Sources of truth verified against:**
- REST endpoints: `src/main/kotlin/attractor/web/RestApiRouter.kt`
- Web UI behaviour and terminology: `src/main/kotlin/attractor/web/WebMonitorServer.kt`
- DOT node shape → handler mapping: `CLAUDE.md` architecture section
- Docker runtime Java version: `Dockerfile.base` (`FROM eclipse-temurin:25-jre-noble`)

**Documentation surfaces changed:**

```
README.md
  ├── Remove stale in-app /docs paragraph and API table row
  ├── Remove all references to docs/api/rest-v1.md (redirected to Hugo site)
  ├── Fix system tools: dot → graphviz
  ├── Fix endpoint count: 35 → 37
  ├── Fix JDK option: "Path to JDK 21" → "Path to JDK 25"
  └── Fix project structure: remove /docs endpoint mention

docs-site/content/web-app.md
  ├── Fix system tools: dot → graphviz
  └── Add Git History Panel section

docs-site/content/dot-format.md
  └── Add 4 missing node types (component, tripleoctagon, parallelogram, house)

docs-site/content/docker.md
  └── Fix Java version: "Java 21 JRE" → "Java 25 JRE"

docs/api/rest-v1.md
  └── Delete file entirely
```

## Implementation Plan

### Phase 1: Pre-edit audit (~5%)

**Tasks:**
- [ ] Grep `README.md`, `docs-site/content/*.md` for: `/docs`, `8080`, `\bdot\b` (as tool name), `JDK 21`, `Java 21`, `35 endpoint`, `rest-v1.md`
- [ ] Verify REST endpoint count from `RestApiRouter.kt` (expected: 37)
- [ ] Verify `Dockerfile.base` Java version (expected: `eclipse-temurin:25-jre-noble`)
- [ ] Confirm `/docs` route absent from `WebMonitorServer.kt` and `RestApiRouter.kt` (removed in Sprint 022)

---

### Phase 2: `README.md` updates (~30%)

**Files:**
- `README.md` — Modify

**Tasks:**

- [ ] **Remove stale in-app docs paragraph** — Delete the paragraph near the bottom of the Database Configuration section that reads "Click **Docs** in the navigation bar to open the built-in documentation window...organized into four tabs...served directly by the Attractor server with no external dependencies." This route was removed in Sprint 022.

- [ ] **Remove `/docs` from the Web API table** — Delete the row `| \`GET\` | \`/docs\` | Built-in documentation window (four tabs) |`. This route returns 404.

- [ ] **Remove "built-in Docs window" from API section** — In the sentence ending "...see `docs/api/rest-v1.md` or open the built-in **Docs** window from the web UI", remove the built-in window reference and remove the `rest-v1.md` link. Replace with a plain sentence directing readers to the Hugo site.

- [ ] **Remove all references to `docs/api/rest-v1.md`** — The file will be deleted. Update any link or mention (`[docs/api/rest-v1.md](docs/api/rest-v1.md)`) to point to `https://coreydaley.github.io/attractor/` or the relevant docs-site section.

- [ ] **Fix system tools warning** — Change "`dot`" → "`graphviz`" in the sentence about missing required tools triggering a warning banner.

- [ ] **Fix REST API endpoint count** — The CLAUDE.md architecture section says "37 endpoints"; update README text from "35-endpoint" / "35 endpoints" to "37 endpoints" consistently.

- [ ] **Fix JDK option description** — In the Make Options table, "Path to JDK 21" → "Path to JDK 25".

- [ ] **Fix project structure description** — Remove "/docs endpoint" from the `WebMonitorServer.kt` line in the project structure section.

---

### Phase 3: `docs-site/content/web-app.md` updates (~25%)

**Files:**
- `docs-site/content/web-app.md` — Modify

**Tasks:**

- [ ] **Fix system tools: `dot` → `graphviz`** — In the System Tools section, change "Required tools (`java`, `git`, `dot`) must be present" to use `graphviz` to match the Settings UI.

- [ ] **Add Git History Panel section** — Add a new `## Git History Panel` section (or a subsection under Monitoring) documenting:
  - **Location**: appears in the project detail tab, between the prompt/description block and the Stages card
  - **Collapsed view**: shows branch name, commit count, and last commit message with relative time (e.g., `⎇ main  •  4 commits  •  last: "Run run-001 completed: 3 stages" (2 min ago)`)
  - **Expanded view**: click the bar to expand a commit log table — short hash, subject, and time-ago per row. Click again to collapse. A chevron (▶/▼) indicates state.
  - **Degraded states**:
    - `git` not on PATH: "⎇ Git unavailable — workspace history requires git on PATH"
    - Repo not yet initialized: "⎇ Git repo not initialized"
    - No commits yet: "⎇ main  •  0 commits  •  no history yet"
  - **Auto-refresh**: panel updates automatically 500ms after the project reaches a terminal state (completed/failed/cancelled/paused)
  - **Manual refresh**: click the ↻ button in the bar header to refresh on demand
  - **Requirement**: the `git` binary must be available on the server's PATH (shown in Settings → System Tools)

---

### Phase 4: `docs-site/content/dot-format.md` updates (~15%)

**Files:**
- `docs-site/content/dot-format.md` — Modify

**Tasks:**

- [ ] **Add 4 missing node types to the Node Types table**:

  | Shape / Type | Role | Description |
  |---|---|---|
  | `shape=component` | **Parallel Fan-out** | Marks this node explicitly as a parallel fan-out point. All outgoing edges run their target nodes concurrently. Equivalent to an implicit fan-out from a regular node with multiple edges, but makes the intent explicit in the graph. |
  | `shape=tripleoctagon` | **Parallel Fan-in** | Waits for all concurrent branches from a fan-out to complete before proceeding. Use as the merge/join point after a `component` fan-out node. |
  | `shape=parallelogram` | **Tool Node** *(advanced)* | Executes a deterministic tool or script stage rather than dispatching an LLM prompt. The `prompt` attribute still describes the tool invocation. |
  | `shape=house` | **Stack Manager Loop** *(advanced)* | Manages a loop that delegates sub-tasks to a stack-based manager agent. Useful for iterative or recursive workflow patterns. |

  Keep the existing "Multiple outgoing edges" row — it explains the implicit fan-out behaviour. The explicit `component`/`tripleoctagon` shapes make intent unambiguous in the DOT source.

---

### Phase 5: `docs-site/content/docker.md` update (~5%)

**Files:**
- `docs-site/content/docker.md` — Modify

**Tasks:**

- [ ] **Fix Java version** — The Overview section currently says "containing the Java 21 JRE". Change to "Java 25 JRE" to match the actual `FROM eclipse-temurin:25-jre-noble` in `Dockerfile.base`.

---

### Phase 6: Delete `docs/api/rest-v1.md` (~5%)

**Files:**
- `docs/api/rest-v1.md` — Delete

**Tasks:**

- [ ] Delete the file `docs/api/rest-v1.md`. The Hugo site (`docs-site/content/rest-api.md`) is the canonical REST API reference going forward. README references have already been updated (Phase 2).
- [ ] If `docs/api/` becomes empty, consider adding a brief `docs/api/README.md` stub that redirects readers to `https://coreydaley.github.io/attractor/` — or simply let the directory be removed if nothing else lives there.

---

### Phase 7: Verification (~15%)

**Tasks:**
- [ ] Re-run targeted search: confirm no remaining `localhost:8080`, `\bdot\b` (as tool label), `JDK 21`/`Java 21`, `35 endpoint`, `/docs` (as page route), `rest-v1.md` references in docs
- [ ] Cross-check Git History Panel section against `WebMonitorServer.kt` (`renderGitBar`, `loadGitInfo`, `toggleGitPanel`)
- [ ] Cross-check all 4 new node type descriptions against CLAUDE.md architecture section
- [ ] Confirm `docker.md` now matches `Dockerfile.base` Java version
- [ ] Run `hugo --source docs-site` build check — must exit 0
- [ ] Confirm `docs-site/public/` is NOT committed

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `README.md` | Modify | Remove stale /docs references and rest-v1.md links; fix dot→graphviz, endpoint count, JDK version |
| `docs-site/content/web-app.md` | Modify | Fix dot→graphviz; add Git History Panel section |
| `docs-site/content/dot-format.md` | Modify | Add 4 missing node types |
| `docs-site/content/docker.md` | Modify | Fix Java version (21→25) |
| `docs/api/rest-v1.md` | Delete | Consolidated into Hugo site; file removed |

## Definition of Done

### README.md
- [ ] No reference to `/docs` as a server-served route (in text or API table)
- [ ] No reference to a "built-in documentation window" or "four tabs"
- [ ] No link or mention of `docs/api/rest-v1.md`
- [ ] System tools warning says `graphviz` not `dot`
- [ ] REST API endpoint count says 37
- [ ] Make Options JDK description says "Path to JDK 25"
- [ ] Project structure `WebMonitorServer.kt` description does not mention `/docs endpoint`

### docs-site/content/web-app.md
- [ ] System Tools section says `graphviz` not `dot`
- [ ] Git History Panel section present and covers: collapsed view, expanded commit log, 3 degraded states, auto-refresh behaviour, manual refresh button, `git` PATH requirement

### docs-site/content/dot-format.md
- [ ] Node Types table includes `component`, `tripleoctagon`, `parallelogram`, `house` with correct descriptions

### docs-site/content/docker.md
- [ ] Overview says "Java 25 JRE" matching `Dockerfile.base`

### docs/api/rest-v1.md
- [ ] File deleted

### Quality
- [ ] No code changes made
- [ ] `hugo --source docs-site` exits 0
- [ ] `docs-site/public/` not committed
- [ ] No new broken Markdown links introduced

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missed stale reference after editing | Low | Low | Explicit re-grep in Phase 7 before declaring done |
| `rest-v1.md` deletion breaks a CI script or external link | Low | Medium | Check `.github/workflows/` and code for any hard-coded references to the path before deleting |
| Node type descriptions inaccurate for advanced types | Low | Medium | Cross-check each shape against CLAUDE.md handler registry mapping |
| `docs-site/public/` accidentally committed | Very Low | Medium | Gitignored; only content/ files are modified |
| Hugo build fails after content changes | Low | Low | Run build check locally before marking done |

## Security Considerations

- Documentation-only sprint; no code, no credentials, no attack surface changes.
- Verify that all `curl` example commands in Hugo pages use placeholder values, not real keys.

## Dependencies

- Sprints 021, 022, 023 all completed (the features being documented are shipped)
- Hugo installed locally for build verification (optional)

## Open Questions

1. **`docs/api/` directory**: After `rest-v1.md` is deleted, the directory will still contain other files (e.g., `openapi.yaml`/`openapi.json` in `src/main/resources/api/`). Confirm what remains in `docs/api/` after deletion and decide whether a stub `README.md` is needed.

2. **Legacy SPA-internal routes** (`/api/projects`, `/api/run`, etc.): Left intentionally undocumented. They are an implementation detail of the browser dashboard, not a public API.

3. **Endpoint count maintenance**: When new endpoints are added in future sprints, the README count ("37 endpoints") will drift again. A future sprint should consider auto-generating this count or noting "see REST API docs for the current list" to avoid the maintenance burden.
