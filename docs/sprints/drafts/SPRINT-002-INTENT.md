# Sprint 002 Intent: Pipeline Iteration

## Seed

"I want to be able to iterate on a pipeline run, I want to be able to edit or add to my original pipeline description, have it create a new dotfile, and then be able to run that dotfile as a pipeline run."

## Context

The web dashboard already supports:
- Creating pipelines from natural language via the **Create** view (description → LLM → DOT → run)
- Re-running a pipeline in-place (same DOT, new execution, `/api/rerun`)
- Resuming a paused pipeline from a checkpoint

What's missing is the **iterate** loop: take the DOT source from an existing run, describe modifications in natural language, regenerate (or directly edit) the DOT, and launch it as a new run. This closes the inner dev loop: run → observe → tweak → re-run.

## Recent Sprint Context

**Sprint 001 (completed)**: Fixed SSE delivery latency and polling cadence so the UI shows stage transitions in real time. Added shared heartbeat scheduler, virtual thread executor, SSE reconnect with backoff, and `kickPoll()` for immediate poll on submit. Single-file change (`WebMonitorServer.kt`).

## Relevant Codebase Areas

| File | Relevance |
|------|-----------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | All HTTP endpoints + full dashboard HTML/JS (~2100 lines) |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | LLM-backed DOT generation; has `generateStream`, `fixStream` |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | `PipelineEntry` stores `dotSource`; `PipelineRunner.submit()` takes `dotSource` |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | `submit()` is the entry point for launching a new run |

Key observations:
- `PipelineEntry.dotSource` is already persisted to DB and served to the browser in `allPipelinesJson()`
- Browser already has `pipelines[id].dotSource` for every run — no extra round-trip needed
- The Create view already has: description textarea + generated DOT textarea + graph preview + Run button
- `DotGenerator.generateStream()` takes a freeform `prompt`; passing `"Given this DOT:\n{baseDot}\nModify it to: {changes}"` would work but a named method is cleaner
- `/api/generate/stream` already exists; a parallel `/api/iterate/stream` endpoint with `{baseDot, changes}` is the right pattern

## Constraints

- Must follow project conventions in CLAUDE.md (build with Java 21 + native Gradle 8.7)
- Must integrate with existing Create view — reuse its DOT textarea, graph preview, and Run button
- Minimal new CSS — use existing button/modal classes
- No new Gradle dependencies
- Keep the sprint focused: do not add "save prompt" to DB, do not add a prompt-history feature

## Success Criteria

1. A terminal-state pipeline run in the Monitor view has an "Iterate" button alongside Re-run/Archive
2. Clicking "Iterate" navigates to the Create view with the existing DOT pre-populated in the DOT textarea
3. The description field has a helpful placeholder for modification instructions
4. When the user types modification instructions and clicks "Modify", the LLM receives both the base DOT and the change request and streams back a modified DOT
5. The user can also directly edit the DOT source (skipping LLM re-generation)
6. Clicking "Run Pipeline" submits the current DOT as a new pipeline run (new tab in Monitor)
7. The original run is preserved unchanged

## Verification Strategy

- Manual end-to-end: create a pipeline via Create view → run it → click Iterate → modify description → click Modify → verify DOT changed → click Run → verify new run tab appears
- Verify original run is not mutated
- Build passes with Java 21 + Gradle 8.7
- No regressions in existing Re-run, Resume, Generate flows

## Uncertainty Assessment

- **Correctness uncertainty**: Low — iterate is a natural composition of existing pieces
- **Scope uncertainty**: Low — clear user story, bounded to 2 files
- **Architecture uncertainty**: Low — follows the same SSE streaming pattern as `/api/generate/stream`

## Open Questions

1. Should the "Iterate" button appear for running/paused pipelines too (pre-loading DOT while it's still running)? Or only terminal states?
2. Should clicking "Modify" replace the full DOT or allow the user to preview/accept before replacing?
3. When no description is provided but DOT is pre-filled, should clicking "Modify" produce an error, or just run the existing DOT as-is?
