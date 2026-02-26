# Sprint 006 Intent: Pipeline History Navigation

## Seed

on the pipeline tabs i need a way to browse the pipeline iterations and pipeline runs histories and be able to view them

## Context

Sprint 005 added a Version History accordion beneath each pipeline panel, listing all runs in a `pipeline_family_id` group. Each card shows version number, name, timestamp, status badge, prompt snippet, and two action buttons: [Artifacts] (opens artifact modal) and [Restore] (enters iterate mode).

However, there is **no way to navigate to a past version as a full panel view**. You can see a list of history entries but cannot open them. Additionally, the tab bar only shows runs that were submitted in the current server session — after a restart, older family members visible in the accordion are not reachable as interactive panels.

This sprint bridges the gap: every entry in Version History becomes fully navigable and interactive, and a version navigator makes it obvious what version you're viewing and lets you move between them quickly.

## Recent Sprint Context

- **Sprint 003**: Export/Import — round-trip zip of run state; `RunStore.getById()` already exists
- **Sprint 004**: Dashboard tab — always-visible overview of all active pipelines; tab bar pattern established
- **Sprint 005**: Version History accordion + Artifact modal; `pipeline_family_id` in DB; `/api/pipeline-family` endpoint returns all family members with status/dotSource/prompt

## Relevant Codebase Areas

- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — all embedded HTML/CSS/JS + server endpoints; primary file for this sprint
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` — in-memory registry; `hydrateEntry()` used for startup + import hydration; needs to support on-demand hydration
- `src/main/kotlin/attractor/db/RunStore.kt` — `getById()` and `getByFamilyId()` already exist
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — `submit()` / `resubmit()` — not needed for viewing (read-only feature)

### Key existing functions to build on
- `renderVersionHistory(currentRunId, members)` — renders accordion cards; needs [View] button added
- `selectTab(id)` — switches to a tab; will be called by [View]
- `buildPanel(id)` — builds DOM scaffold; currently called only for SSE-discovered runs
- `PipelineRegistry.hydrateEntry()` — existing helper for reconstructing PipelineEntry from StoredRun
- `PipelineRegistry.loadFromDB()` — loads all runs at startup
- `applyUpdate(data)` — merges a pipeline update into `pipelines{}` client-side map

## Constraints

- Must follow project conventions in CLAUDE.md
- Must integrate with existing architecture (no new Gradle dependencies)
- Build system: Java 21 + native Gradle 8.7
- All UI changes are embedded in `WebMonitorServer.kt`; no separate files
- Past runs loaded for viewing must be read-only (no re-running from ghost entries)
- Path traversal security already enforced in `/api/run-artifact-file` — must remain

## Success Criteria

1. Every version card in the Version History accordion has a [View] button
2. Clicking [View] opens that run as a full interactive panel (stages, logs, graph, artifacts)
3. Works after server restart — family members present in DB but not in the current session tab bar can still be navigated to
4. A version navigator (breadcrumb or prev/next) appears on each pipeline panel when the run belongs to a family, showing which version you're on
5. Navigating between versions via the navigator updates both the panel and the URL/state correctly

## Verification Strategy

- Manual: Submit a pipeline, iterate it twice. Version History shows 3 cards. Click [View] on v1 — panel switches to v1 with its stages/logs/DOT. Click [View] on v3 — panel switches back.
- Manual: Restart server. Open a pipeline that has 3 family members. Version History shows all 3. Click [View] on v1 — loads from DB without error.
- Manual: Version navigator shows "v1 of 3" with prev/next — clicking cycles through family members in chronological order.
- Spec: No regressions in existing Export, Import, Archive, Dashboard, Artifacts, Restore flows.

## Uncertainty Assessment

- Correctness uncertainty: **Low** — well-understood domain; extends existing patterns
- Scope uncertainty: **Medium** — multiple sub-features (View button, DB hydration on demand, version navigator); need to decide how much to include
- Architecture uncertainty: **Medium** — loading a run from DB on-demand (when not in the in-memory registry) requires a new code path; want to do it cleanly

## Open Questions

1. Should the [View] tab for a historical run be ephemeral (auto-removed when you navigate away) or persistent (stays in tab bar)?
2. Should the version navigator appear in the panel header, as a separate right-panel tab ("History"), or both?
3. If a family member run is not in the in-memory registry (post-restart), do we load just its metadata (read-only ghost), or fully hydrate it so all existing UI features work?
4. Should navigating to a historical run via [View] scroll to / auto-expand the Version History accordion?
