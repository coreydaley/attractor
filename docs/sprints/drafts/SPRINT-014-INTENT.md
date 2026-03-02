# Sprint 014 Intent: DOT File Upload on Create Page

## Seed

Users should also be able to upload a DOT file on the create page, and that DOT file should populate the graph preview and the text from that file should be populated in the generated dot box.

## Context

- All 13 sprints are complete. The project has a rich feature set: live dashboard, LLM-powered pipeline generation, 35-endpoint REST API, Kotlin CLI, execution mode toggles, and in-app documentation.
- The Create view currently has two workflows: (A) type a natural language description to auto-generate a DOT graph, or (B) paste/type DOT source directly into the "Generated DOT" textarea.
- A third workflow is missing: load a DOT file from disk. Users who have authored DOT files locally, exported them from the CLI, or downloaded them from GitHub cannot easily get them into the Create view without opening the file, copying all text, and pasting it.

## Recent Sprint Context

- **Sprint 011**: Added global AI provider execution mode (API vs CLI subprocess) and per-provider toggles surfaced on the Settings page. All settings persisted in SQLite.
- **Sprint 012**: Added a full Kotlin CLI client (`attractor-cli.jar`) wrapping all 35 REST API v1 endpoints. Uses FileReader/OkHttp, no new deps.
- **Sprint 013**: Added an in-app documentation window (`/docs`) with four tabs (Web App, REST API, CLI, DOT Format). Pure additive: one new HTTP context, private helper functions, no changes to the SPA or API.

## Relevant Codebase Areas

| Location | Role |
|---|---|
| `WebMonitorServer.kt` | Single file containing all HTML, CSS, JS, and server handlers |
| `#viewCreate` block (~line 2448) | Create view HTML: two columns, NL textarea left, DOT textarea + graph preview |
| `dot-header-row` div (~line 2461) | Flex row containing "Generated DOT" label and `#genStatus` span — natural insertion point for an upload button |
| `clearCreateForm()` JS (~line 3771) | Resets NL, dotPreview, runBtn, graphContent, genStatus |
| `renderGraph()` JS (~line 3811) | Reads `#dotPreview`, calls `/api/render-dot`, sets `#graphContent` |
| `setGenStatus(cls, msg)` JS (~line 4000) | Updates the genStatus span with a class and message |
| Import ZIP modal (~line 2634) | Uses `<input type="file" accept=".zip">` + `FileReader` — **directly reusable pattern** |
| `#dotPreview` input event listener (~line 3982) | Enables runBtn and debounces `renderGraph()` when user types in the textarea |

## Constraints

- Must follow project conventions: no new Gradle dependencies, no new server-side routes
- Changes confined to `WebMonitorServer.kt`
- Kotest FunSpec for any new tests (but this feature is pure front-end — HTTP-level tests verify the route isn't broken, not file reading)
- Must integrate with the existing Create view flow without breaking NL-generate, paste-DOT, or iterate workflows
- Must not interfere with `iterateSourceId` state or the iterate/cancel-iterate flow

## Success Criteria

1. A "Upload .dot file" control appears on the Create view, near the "Generated DOT" label
2. Clicking it opens a native file picker filtered to `.dot` files
3. After selecting a file:
   - The file's text content populates `#dotPreview`
   - The graph preview updates automatically (same as typing in dotPreview)
   - The Run Pipeline button becomes enabled
   - `genStatus` shows a confirmation that the file was loaded
4. The NL textarea (`#nlInput`) is **not** cleared (user may still want to see/modify the prompt context)
5. The filename is reflected somewhere (either in `genStatus` or the file input label)
6. All existing Create view flows (NL generate, paste-DOT, iterate) continue to work
7. `clearCreateForm()` resets the file input state along with everything else

## Verification Strategy

- Correctness: file picker opens filtered to `.dot`; FileReader loads UTF-8 text; `#dotPreview` and graph preview update correctly
- Edge cases: empty file, file with invalid DOT syntax (renderGraph handles render errors already), very large file
- Existing tests: `DocsEndpointTest`, `WebMonitorServerTest` (if it exists) should still pass; no new route tests needed
- Manual verification: upload `examples/simple.dot`, confirm DOT appears in textarea and graph renders

## Uncertainty Assessment

- Correctness uncertainty: **Low** — FileReader + `<input type="file">` is well-understood, existing import pattern to follow
- Scope uncertainty: **Low** — seed is specific and bounded: one UI control, one JS handler
- Architecture uncertainty: **Low** — no new server routes, no new state, pure DOM manipulation

## Open Questions

1. Where exactly should the upload control appear? Options: (a) in the `dot-header-row` alongside "Generated DOT" label, (b) below the dotPreview textarea in the run-row, (c) as a third "pill" next to the Fix/Iterate buttons. The `dot-header-row` is the most natural placement.
2. Should uploading a DOT file clear the NL input? The seed says the DOT populates the DOT box; it doesn't mention the NL input. Leaving NL as-is seems safest.
3. Should the filename be used as the `fileName` when running the pipeline (instead of `"generated.dot"`)? This would be a nice-to-have quality improvement.
