# Sprint 014: DOT File Upload on Create Page

## Overview

The Create view currently gives users two ways to produce a pipeline: generate one from a natural language prompt, or type/paste DOT source directly. Users who already have a `.dot` file on disk — whether hand-authored, previously exported via the CLI, or downloaded from GitHub — must open the file in a text editor, copy all its contents, switch back to the browser, and paste them. This friction discourages the use of file-based pipelines and makes the "upload and run" workflow unnecessarily cumbersome.

This sprint adds a **Upload .dot file** control to the Create view. Clicking it opens the browser's native file picker filtered to `.dot` files. Once a file is selected, its text content is immediately loaded into the "Generated DOT" textarea and the graph preview re-renders — exactly the same visual result as if the user had typed that DOT source by hand. The Run Pipeline button becomes enabled. The filename is preserved and used as the `fileName` when submitting the pipeline, so artifacts and logs are labelled with the original file name rather than the generic `"generated.dot"`.

The implementation is entirely client-side: one hidden `<input type="file">`, one small button, and a JavaScript `FileReader` handler. No new server routes, no new Kotlin code, no new Gradle dependencies. The existing `FileReader` pattern from the Import ZIP modal is the direct model. The change is additive: all existing Create view workflows (NL generate, manual paste, and iterate) continue to work exactly as before.

## Use Cases

1. **Load a pre-authored pipeline**: A user has written `deploy-container.dot` locally. They open the Create view, click "Upload .dot file", select the file, and see the graph render immediately. They click Run Pipeline — the pipeline starts with `fileName = "deploy-container.dot"`.

2. **Re-use a CLI-generated file**: A developer ran `attractor dot generate --output pipeline.dot` from the terminal. They open the web UI, upload `pipeline.dot` to preview and run it without needing to copy-paste anything.

3. **Quick iteration from a downloaded example**: A user downloads one of the `examples/*.dot` files from GitHub. They upload it to the Create view, see the graph, modify a few lines in the DOT textarea, and run the modified version.

4. **Upload then tweak**: A user uploads a `.dot` file, then edits the DOT source in the textarea before running. The NL input is left intact (not cleared), so they can type a description and click Iterate if they want to further refine via LLM.

5. **Upload an invalid file**: A user accidentally selects a `.dot` file with a syntax error. The DOT loads into the textarea and the graph preview shows a render error (the same error rendering that already exists). The user can see the raw source and either fix it manually or click Fix.

## Architecture

```
Create view (existing two-column layout)
─────────────────────────────────────────────────────────────────────────
Left column
  ┌─ Describe your pipeline ───────────────────────────────────────────┐
  │  [textarea #nlInput]                                                │
  │  [ ] Simulate    [✓] Auto-approve                                  │
  └─────────────────────────────────────────────────────────────────────┘
  ┌─ Generated DOT ─────────────────── [Status] [📂 Upload .dot] ──────┐  ← CHANGED
  │  [textarea #dotPreview]                                             │
  │  <input type="file" id="dotFileInput" accept=".dot" hidden>        │  ← NEW
  │  [gen-hint]               [Cancel] [▶ Run Pipeline]                │
  └─────────────────────────────────────────────────────────────────────┘

Right column
  ┌─ Graph Preview ─────────────────────────────────────────────────────┐
  │  (no change)                                                        │
  └─────────────────────────────────────────────────────────────────────┘

Data flow on file select:
  user clicks "📂 Upload .dot"
    → click triggers #dotFileInput.click()
    → file picker opens filtered to .dot
  user selects file
    → onchange fires on #dotFileInput
    → FileReader.readAsText(file)
    → onload: dotPreview.value = result
             uploadedFileName = file.name        ← new module-scope var
             runBtn.disabled = false
             setGenStatus('ok', 'Loaded: ' + file.name)
             renderGraph()                        ← re-uses existing function
    → onerror: setGenStatus('error', 'Could not read file')

runGenerated() (modified):
  - fileName: uploadedFileName || 'generated.dot'   ← use uploaded name if set
  - after successful submit: uploadedFileName = null  ← reset

clearCreateForm() (modified):
  - dotFileInput.value = ''    ← reset file input
  - uploadedFileName = null    ← reset filename state
```

## Implementation Plan

### Phase 1: HTML — file input and upload button (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add a hidden `<input type="file">` element inside `#viewCreate`, just after the `#dotPreview` textarea:
  ```html
  <input type="file" id="dotFileInput" accept=".dot" style="display:none;" onchange="onDotFileSelected()">
  ```
- [ ] Add an "Upload .dot" button to the `dot-header-row` div, to the right of the `#genStatus` span:
  ```html
  <button class="dot-upload-btn" onclick="document.getElementById('dotFileInput').click()" title="Load a .dot file from disk">&#128194;&ensp;Upload .dot</button>
  ```
  (📂 is `&#128194;`)
- [ ] Add `.dot-upload-btn` CSS to the existing style block:
  ```css
  .dot-upload-btn { background: none; border: 1px solid var(--border); border-radius: 6px; color: var(--text-muted); font-size: 0.75rem; padding: 3px 10px; cursor: pointer; white-space: nowrap; }
  .dot-upload-btn:hover { border-color: var(--accent); color: var(--text); }
  ```
  This matches the subdued style of other secondary controls (e.g., `graph-zoom-btn`).

---

### Phase 2: JavaScript — FileReader handler and state (~50%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `var uploadedFileName = null;` near the top of the script block alongside other module-scope variables (e.g., near `var iterateSourceId = null`).

- [ ] Implement `onDotFileSelected()`:
  ```javascript
  function onDotFileSelected() {
    var input = document.getElementById('dotFileInput');
    if (!input || !input.files || input.files.length === 0) return;
    var file = input.files[0];
    var reader = new FileReader();
    reader.onload = function(e) {
      var dotSource = e.target.result;
      var preview = document.getElementById('dotPreview');
      if (preview) {
        preview.value = dotSource;
        document.getElementById('runBtn').disabled = !dotSource.trim();
        uploadedFileName = file.name;
        setGenStatus('ok', 'Loaded: ' + file.name);
        renderRetries = 0;
        renderGraph();
      }
    };
    reader.onerror = function() {
      setGenStatus('error', 'Could not read file.');
    };
    reader.readAsText(file, 'UTF-8');
  }
  ```

- [ ] Modify `runGenerated()` to use `uploadedFileName` if set:
  ```javascript
  // Change this line:
  body: JSON.stringify({ dotSource: dotSource, fileName: 'generated.dot', ...})
  // To:
  body: JSON.stringify({ dotSource: dotSource, fileName: uploadedFileName || 'generated.dot', ...})
  ```
  And reset after a successful submit:
  ```javascript
  // In the success branch (after resetCreatePage()):
  uploadedFileName = null;
  ```

- [ ] Modify `clearCreateForm()` to reset file input state:
  ```javascript
  // Add after existing resets:
  var dotFileInput = document.getElementById('dotFileInput');
  if (dotFileInput) dotFileInput.value = '';
  uploadedFileName = null;
  ```

---

### Phase 3: Docs update — Web App tab (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (`webAppTabContent()`)

**Tasks:**
- [ ] Update the "Creating a Pipeline" section in `webAppTabContent()` to add Option C (Upload .dot file):
  ```
  Option C — Upload a .dot file
  Click "Upload .dot" in the Generated DOT section header to open a file picker.
  Select a .dot file from disk. The DOT source loads into the editor and the
  graph renders immediately. Click Run Pipeline to execute it.
  ```

---

### Phase 4: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/web/DocsEndpointTest.kt` — Modify (add one assertion)
- No new test file needed — this is pure client-side JS, untestable at the HTTP level

**Tasks:**
- [ ] Add one assertion to `DocsEndpointTest` verifying the docs page still mentions the upload workflow:
  - Body contains `Upload .dot` (upload control documented in Web App tab)
- [ ] Verify that `GET /` still returns 200 (existing regression guard covers this)
- [ ] Verify that `GET /api/v1/pipelines` still returns 200 (existing guard)

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `.dot-upload-btn` CSS, hidden file input HTML, upload button HTML, `onDotFileSelected()` JS, `uploadedFileName` var, modify `runGenerated()` and `clearCreateForm()` |
| `src/test/kotlin/attractor/web/DocsEndpointTest.kt` | Modify | Add assertion for upload content in Web App docs tab |

## Definition of Done

- [ ] "Upload .dot" button appears in the "Generated DOT" section header on the Create page
- [ ] Clicking it opens a native file picker filtered to `.dot` files
- [ ] Selecting a file populates `#dotPreview` with the file's text content
- [ ] The graph preview renders automatically after file selection
- [ ] Run Pipeline button becomes enabled after a non-empty file is loaded
- [ ] `genStatus` shows `"Loaded: <filename>"` after successful load
- [ ] `genStatus` shows an error message if the file cannot be read
- [ ] Uploaded filename is used as `fileName` when the pipeline is submitted (instead of `generated.dot`)
- [ ] `clearCreateForm()` resets the file input and clears `uploadedFileName`
- [ ] Uploading a file does NOT clear the NL input (`#nlInput`)
- [ ] All existing Create view flows continue to work: NL generate, manual paste, iterate/cancel-iterate
- [ ] `/docs` Web App tab mentions the upload workflow
- [ ] `DocsEndpointTest` assertions pass
- [ ] No compiler warnings
- [ ] Build passes: `export JAVA_HOME=... && gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| File picker accepts non-DOT files | Low | Low | `accept=".dot"` on the input is advisory (browsers enforce it); the user can still force-select other file types. Render errors from invalid DOT are already handled gracefully by `renderGraph()`. |
| Very large DOT files cause browser lag | Low | Low | `FileReader.readAsText` is async; DOT files are plain text and rarely exceed a few KB. No mitigation needed. |
| `uploadedFileName` leaks into next NL-generate flow if not cleared | Medium | Low | `clearCreateForm()` and `runGenerated()` both reset it. NL-generate doesn't touch `uploadedFileName`. |
| Upload button visually crowds the `dot-header-row` | Low | Low | Button is small (tertiary style), right-aligned. If needed, can be moved to the `run-row`. |
| Pop-up blocker prevents file picker on indirect click | Low | Low | `document.getElementById('dotFileInput').click()` in a button `onclick` is a direct user gesture — browsers do not block this. |

## Security Considerations

- File contents are read client-side via `FileReader` and placed into a textarea. No file data is sent to the server until the user explicitly clicks Run Pipeline — and even then only the text content is sent as `dotSource` in the existing `/api/run` JSON body.
- No new attack surface is introduced server-side.
- DOT source is already user-controlled (the user can type anything in the textarea); accepting a file upload does not change the trust model.

## Dependencies

- Sprint 013 (completed) — `clearCreateForm()`, `renderGraph()`, and the Create view HTML are the integration points
- No external dependencies

## Open Questions

1. Should the upload button label say "Upload .dot" or "📂 Load file"? Either works; "Upload .dot" is explicit about the expected format.
2. Should uploading clear the NL input? The seed doesn't request this; keeping it helps users who want to Iterate after uploading.
3. Should we show a small "×" to clear the uploaded file, or is relying on the user retyping/pasting sufficient? Clearing is handled by `clearCreateForm()`, which is already called by navigation away from the Create view.
