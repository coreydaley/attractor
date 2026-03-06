# Sprint 024: Documentation Audit and Update

## Overview

Twenty-three sprints of feature development have accumulated a set of documentation drift points across `README.md`, `docs-site/content/`, and `docs/api/rest-v1.md`. The most significant gaps stem from three specific sprints: Sprint 021 (Git History Panel — a new UI feature and REST endpoint never documented in the web-app or dot-format guides), Sprint 022 (Hugo Docs Microsite — the in-app `/docs` endpoint was removed, but the README still describes it as if it exists), and the recent system-tool rename from `dot` to `graphviz`. Several smaller inconsistencies round out the list: a wrong default port in `rest-v1.md` (8080 vs 7070), an outdated endpoint count in the README, a stale "Path to JDK 21" option note, and missing advanced node types in the DOT format guide.

This sprint is purely documentation. No code changes. The deliverable is a fully accurate, internally consistent documentation set that matches what is actually deployed and running. Every change is verified against the source code in `RestApiRouter.kt`, `WebMonitorServer.kt`, and `CLAUDE.md` before being written.

The result is a documentation baseline that any new user can read with confidence, any contributor can extend without discovering surprises, and that the Hugo site can be rebuilt and deployed without edits.

## Use Cases

1. **New user reads README**: They encounter accurate information about how to view docs (via the external Hugo site, not a built-in window), correct system requirements (Java 25, graphviz), and an accurate REST API endpoint count.

2. **User explores the web UI**: They open the web-app docs and find the Git History Panel section — they learn about the collapsible git bar, how to expand the commit log, and when it auto-refreshes.

3. **Developer writes a REST client**: They reference `docs/api/rest-v1.md` and get correct `localhost:7070` example URLs and the correct endpoint count.

4. **User asks "what DOT shapes can I use?"**: The dot-format docs now list all supported node shapes, including the parallel fan-out, fan-in, tool, and stack manager types.

5. **Contributor checks the system tools warning**: Both README and docs-site correctly say `graphviz` (matching the Settings UI).

## Architecture

This sprint is documentation-only. No architectural changes. Files modified:

```
README.md
  ├── Remove stale in-app /docs paragraph and /docs API table row
  ├── Fix system tools warning: dot → graphviz
  ├── Fix REST API endpoint count: 35 → 37
  ├── Fix JDK option description: JDK 21 → JDK 25
  ├── Fix project structure: remove /docs endpoint mention
  └── Fix "built-in Docs window" reference

docs-site/content/web-app.md
  ├── Fix system tools: dot → graphviz
  └── Add Git History Panel section

docs-site/content/dot-format.md
  └── Add missing node types (component, tripleoctagon, parallelogram, house)

docs/api/rest-v1.md
  ├── Fix default port: 8080 → 7070 (1 description + 37 curl examples)
  └── Verify git endpoint documentation matches implementation
```

## Implementation Plan

### Phase 1: README.md fixes (~30%)

**Files:**
- `README.md` — Modify

**Tasks:**

- [ ] **Remove stale in-app docs paragraph** — Delete the paragraph at the bottom of the Database Configuration section (currently: "Click **Docs** in the navigation bar to open the built-in documentation window...") which incorrectly describes a four-tab in-app window that was removed in Sprint 022.

- [ ] **Remove `/docs` from the API table** — Delete the row `| \`GET\` | \`/docs\` | Built-in documentation window (four tabs) |` from the Web API selected endpoints table. This route returns 404 since Sprint 022.

- [ ] **Fix "built-in Docs window" reference** — In the final sentence of the Web API section ("For the complete endpoint listing... see `docs/api/rest-v1.md` or open the built-in **Docs** window from the web UI"), remove or replace the built-in Docs window reference. Change to: "For the complete endpoint listing with request/response shapes and `curl` examples, see [`docs/api/rest-v1.md`](docs/api/rest-v1.md)."

- [ ] **Fix REST API endpoint count** — Change "37 endpoints" (line 301) if needed — the CLAUDE.md architecture note says "37 endpoints" which is correct. Also fix "35 endpoints" in the project structure section (line 501) to match.

- [ ] **Fix system tools warning** — Change `dot` → `graphviz` in the sentence "Missing required tools (`java`, `git`, `dot`) trigger a warning banner..." to match the UI.

- [ ] **Fix JDK option description** — In the Make Options table, "Path to JDK 21" → "Path to JDK 25".

- [ ] **Fix WebMonitorServer.kt description in project structure** — Remove ", /docs endpoint" from the comment that says "HTTP server, dashboard SPA, /docs endpoint".

---

### Phase 2: docs-site/content/web-app.md (~30%)

**Files:**
- `docs-site/content/web-app.md` — Modify

**Tasks:**

- [ ] **Fix system tools: dot → graphviz** — Line 200 reads "Required tools (`java`, `git`, `dot`) must be present for core features to work" — change `dot` to `graphviz` to match the Settings UI.

- [ ] **Add Git History Panel section** — Add a new `## Git History Panel` section (or subsection under Monitoring) that documents:
  - What it is: a collapsible bar that appears in the project detail tab between the description block and the Stages card
  - What it shows when collapsed: branch name, commit count, and last commit message with relative time
  - How to interact: click the bar to expand/collapse a commit log table (short hash, subject, time-ago per row)
  - States: unavailable (no `git` on PATH), repo not initialized (no commits yet), normal view
  - Auto-refresh: the panel updates automatically 500ms after a project reaches a terminal state (completed/failed/cancelled/paused)
  - Manual refresh: a refresh button (↻) in the bar header re-fetches on demand
  - Note: requires `git` to be installed and present on the server's PATH

---

### Phase 3: docs-site/content/dot-format.md (~20%)

**Files:**
- `docs-site/content/dot-format.md` — Modify

**Tasks:**

- [ ] **Add missing node types** — The Node Types table currently documents 6 entries. Add 4 more to cover the full `HandlerRegistry` mapping from CLAUDE.md:

  | Shape / Type | Role | Description |
  |---|---|---|
  | `shape=component` | **Parallel Fan-out** | When a node has multiple outgoing edges, all targets run concurrently. Use `shape=component` to make this explicit. |
  | `shape=tripleoctagon` | **Parallel Fan-in** | Waits for all concurrent branches to complete before continuing. Use as the merge point after a `component` fan-out. |
  | `shape=parallelogram` | **Tool Node** | Executes a deterministic tool or script rather than an LLM call. |
  | `shape=house` | **Stack Manager Loop** | Manages a loop that delegates sub-tasks to a stack-based manager agent. |

  Note: the existing "Multiple outgoing edges → Parallel Fan-out" row under `shape=box` can stay — it explains the implicit behavior. The explicit `component`/`tripleoctagon` shapes make the intent unambiguous in the DOT source.

---

### Phase 4: docs/api/rest-v1.md port fix (~20%)

**Files:**
- `docs/api/rest-v1.md` — Modify

**Tasks:**

- [ ] **Fix default port in description** — Change "Default port: `8080`" to "Default port: `7070`".

- [ ] **Fix all curl examples** — Replace all `localhost:8080` with `localhost:7070` (37 occurrences). Use a sed-equivalent edit (replace_all).

- [ ] **Verify git endpoint docs** — Read the existing git endpoint documentation (section 37) and cross-reference with the actual `RestApiRouter.kt` implementation. Confirm fields, status codes, and examples are accurate. The sprint 021 plan documents the exact response shape — compare against what's in `rest-v1.md`.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `README.md` | Modify | Remove stale /docs references; fix dot→graphviz; fix counts and JDK version |
| `docs-site/content/web-app.md` | Modify | Fix dot→graphviz; add Git History Panel section |
| `docs-site/content/dot-format.md` | Modify | Add 4 missing node type rows |
| `docs/api/rest-v1.md` | Modify | Fix default port 8080→7070 (38 occurrences total) |

## Definition of Done

### README.md
- [ ] No reference to `/docs` as a server-served route (in text or API table)
- [ ] No reference to a "built-in documentation window" or "four tabs"
- [ ] System tools warning says `graphviz` not `dot`
- [ ] REST API endpoint count says 37 (not 35)
- [ ] Make Options JDK description says "JDK 25" not "JDK 21"
- [ ] Project structure `WebMonitorServer.kt` description does not mention `/docs endpoint`

### docs-site/content/web-app.md
- [ ] System Tools section says `graphviz` not `dot`
- [ ] Git History Panel documented with: collapsed view, expanded commit log, states (unavailable/no repo/no commits/normal), auto-refresh behavior, manual refresh button, `git` PATH requirement

### docs-site/content/dot-format.md
- [ ] Node Types table includes `component`, `tripleoctagon`, `parallelogram`, `house` entries with correct descriptions

### docs/api/rest-v1.md
- [ ] Default port is `7070` in description
- [ ] All `curl` example URLs use `localhost:7070`
- [ ] Git endpoint (#37) documentation matches the actual `WorkspaceGit.GitSummary` response shape

### Quality
- [ ] No new code changes
- [ ] Hugo site builds without errors (if verifiable locally)
- [ ] No broken Markdown links introduced
- [ ] All changes are accurate with respect to actual source code

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missed stale reference in README | Low | Low | Systematic grep for `8080`, `/docs`, `dot\b`, `JDK 21`, `35 endpoint` before marking done |
| Node type descriptions inaccurate for advanced types | Low | Medium | Cross-check each shape against `HandlerRegistry` mappings in CLAUDE.md; keep descriptions brief |
| Git panel section is over-specific about internal implementation | Low | Low | Document from user perspective only; no internal code details |
| rest-v1.md port fix misses some occurrences | Low | Medium | Use replace_all to catch all 38 instances |
| docs-site/public/ accidentally committed | Very Low | Medium | Gitignored; only editing content/ files |

## Security Considerations

- Documentation-only sprint; no code, no credentials, no attack surface changes.
- Verify that example `curl` commands do not include any real API keys or sensitive values (they don't — all are placeholders or env var references).

## Dependencies

- No code dependencies
- Sprint 021, 022, 023 all completed (the features being documented are already shipped)
- Hugo installed locally is optional for local verification but not required for the PR

## Open Questions

1. **Legacy non-versioned routes** (`/api/projects`, `/api/run`, etc.) — these are browser-facing internal APIs not documented anywhere. Proposal: leave undocumented, as they are an implementation detail of the SPA. Agreed from context.

2. **`docs/api/rest-v1.md` vs `docs-site/content/rest-api.md` duplication** — two separate REST references exist. `rest-v1.md` is a developer/machine reference; the Hugo page is user-facing. Proposal: keep both for now, note as a follow-up consolidation task.

3. **Advanced node types documentation depth** — `parallelogram` (tool) and `house` (stack manager loop) are advanced features. For now, add them to the table with brief descriptions and note "advanced" to set expectations without over-documenting.
