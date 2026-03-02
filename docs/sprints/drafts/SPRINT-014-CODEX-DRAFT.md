# Sprint 014: DOT File Upload on Create Page

## Overview

The Create view currently supports two ways to get DOT into Attractor: (A) type a natural-language description to auto-generate a DOT graph, or (B) paste/type DOT directly into the "Generated DOT" textarea. A very common workflow is missing: selecting an existing `.dot` file from disk. Today users must open the file and copy/paste its contents, which is slow, error-prone, and makes it harder to reuse DOT from the CLI, from GitHub, or from previous work.

This sprint adds a small, native-feeling "Upload .dot" control to the Create view. Selecting a file reads it client-side (via `FileReader`) and populates `#dotPreview` with its text. The existing Create flow remains the source of truth: once `#dotPreview` has content, the Run button enables and the Graph Preview auto-renders using the existing `/api/render` endpoint and `renderGraph()` logic. No new server routes, no new Gradle dependencies, and changes remain confined to `WebMonitorServer.kt`.

## Use Cases

1. **Local DOT reuse**: A user has a DOT pipeline file in their editor and wants to run it in Attractor without copy/paste.
2. **CLI/export roundtrip**: A user exports or generates DOT via CLI, then uploads it into the Create view to visually inspect, tweak, and run.
3. **GitHub/example ingestion**: A user downloads a DOT example and uploads it to see the graph preview immediately.
4. **Iterative authoring**: A user starts with an uploaded DOT file, then tweaks the text area and relies on debounced re-rendering as they edit.
5. **Faster onboarding**: New users can load `examples/*.dot` and see a working pipeline without learning the generate flow first.

## Architecture

### UI Placement

- Add an "Upload .dot" control in the Create view header row (`.dot-header-row`), alongside the "Generated DOT" label and `#genStatus`.
- Use a hidden `<input type="file" accept=".dot">` triggered by a small button to keep styling consistent.
- Surface the selected filename in `#genStatus` (and keep `#nlInput` untouched).

### Data Flow

```text
User clicks Upload
  -> native file picker (filtered to .dot)
    -> on change:
        -> FileReader.readAsText(file)
          -> dotPreview.value = fileText
          -> runBtn enabled (existing dotPreview input listener)
          -> renderGraph() (existing)
          -> setGenStatus('ok', 'Loaded <filename> ✓')
```

### State + Compatibility

- No new server-side state and no new HTTP routes.
- Upload handler must not touch `iterateSourceId` (iterate/cancel-iterate behavior stays unchanged).
- `clearCreateForm()` and `resetCreatePage()` reset the upload control so subsequent creates start clean.
- Optional quality improvement: when running a pipeline, send `fileName` as the uploaded filename instead of the fixed `'generated.dot'`.

## Implementation Plan

### Phase 1: Create View Markup + Styling (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add a small Upload button in the Create view `.dot-header-row` (near `Generated DOT`).
- [ ] Add a hidden file input (e.g., `#dotUploadInput`) with `accept=".dot"`; clicking the button triggers `.click()` on the input.
- [ ] Keep styling consistent with existing button patterns (small, subtle, in-row; no layout shifts).

### Phase 2: FileReader Handler + UX (~45%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Implement `onDotUploadChange()` (or similar) to:
  - read the selected file as text (UTF-8 via `FileReader.readAsText`)
  - populate `#dotPreview` with the file contents
  - trigger the existing `#dotPreview` input behavior (enable Run + debounced render) by dispatching an `input` event or calling the same logic directly
  - call `renderGraph()` immediately (or rely on debounce) for fast feedback
  - set `genStatus` to a success message including the filename
- [ ] Handle edge cases:
  - empty file → `setGenStatus('error', 'File is empty')` and keep Run disabled
  - read error → `setGenStatus('error', 'Failed to read file')`
  - invalid DOT syntax → allow existing `renderGraph()` error UI to surface it
- [ ] Preserve `#nlInput` content (do not clear prompt text).
- [ ] Track `uploadedDotFileName` (string) so `runGenerated()` can optionally use it as `fileName`.

### Phase 3: Reset + Regression Hardening (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Update `clearCreateForm()` to reset the upload input value and any `uploadedDotFileName` state.
- [ ] Update `resetCreatePage()` similarly (post-run cleanup).
- [ ] Ensure upload does not break iterate mode:
  - do not mutate `iterateSourceId`
  - do not hide/show iterate controls incorrectly

### Phase 4: HTTP-Level Markup Test (~10%)

**Files:**
- `src/test/kotlin/attractor/web/CreateDotUploadTest.kt` — Create

**Tasks:**
- [ ] Start `WebMonitorServer(0, ...)` (same pattern as `DocsEndpointTest`).
- [ ] Assert `GET /` contains:
  - the file input id (e.g., `dotUploadInput`)
  - `accept=".dot"`
  - a stable upload handler hook (`onDotUploadChange()` or equivalent)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add Create view upload control + JS handler to load DOT into `#dotPreview` and render graph preview |
| `src/test/kotlin/attractor/web/CreateDotUploadTest.kt` | Create | HTTP-level regression test asserting upload markup is present on `/` |

## Definition of Done

- [ ] Create view shows an "Upload .dot" control near the "Generated DOT" label.
- [ ] File picker opens and is filtered to `.dot` files.
- [ ] Selecting a file populates `#dotPreview` with its text.
- [ ] Graph preview updates automatically after upload.
- [ ] Run Pipeline button becomes enabled after upload (when DOT is non-empty).
- [ ] `genStatus` confirms load success and includes the filename.
- [ ] `#nlInput` content is preserved on upload.
- [ ] `clearCreateForm()` and `resetCreatePage()` reset the upload control state.
- [ ] Existing flows (NL generate, paste DOT, iterate) still work as before.
- [ ] Tests pass.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Upload handler bypasses existing debounced input behavior | Medium | Medium | Dispatch an `input` event after setting `#dotPreview.value`, so existing listener stays authoritative |
| Confusion about which filename is used for runs | Medium | Low | Show filename in `genStatus`; optionally pass uploaded name as `fileName` to `/api/run` |
| Large DOT files cause sluggish rendering | Low | Medium | Keep behavior consistent; rely on existing `/api/render` + retry/fix flow; consider showing "Rendering…" immediately |
| Interference with iterate mode behavior | Low | Medium | Ensure upload code never touches `iterateSourceId`; keep iterate controls unchanged |

## Security Considerations

- The file is read locally in the browser and never uploaded as a file; only its text is sent in existing JSON requests when running/rendering.
- Avoid inserting filename or file contents into HTML via `innerHTML`; use `textContent` (existing `setGenStatus` already uses `textContent`).
- Keep the upload handler free of user-controlled script injection (no dynamic `<script>` / `innerHTML` usage).

## Dependencies

- Existing Create view DOM + JS in `WebMonitorServer.kt`.
- Existing DOT rendering endpoint (`POST /api/render`) and error handling.
- Existing Kotest patterns in `src/test/kotlin/attractor/web/*`.

## Open Questions

1. Should `runGenerated()` send `fileName` as the uploaded filename (instead of always `'generated.dot'`)?
2. Should upload be disabled in iterate mode, or allowed (keeping `iterateSourceId` intact) to support “replace DOT” during iteration?
