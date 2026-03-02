# Sprint 014 Merge Notes

## Claude Draft Strengths
- Correct identification of the `FileReader` + hidden file input pattern from the existing Import ZIP modal as the direct implementation model
- Good handling of `uploadedFileName` state var and threading it through `runGenerated()` for correct artifact labelling
- Correctly identified that all changes are confined to `WebMonitorServer.kt`
- Docs update (webAppTabContent) included

## Codex Draft Strengths
- Correctly flagged the NL auto-generate debounce overwrite race condition (High priority)
- Correctly flagged that `resetCreatePage()` also needs file-input reset, not just `clearCreateForm()`
- Proposed a dedicated `CreateDotUploadTest.kt` for HTTP-level markup regression test (better than extending DocsEndpointTest)
- Dispatching a synthetic `input` event on `#dotPreview` after populating it is a clean way to reuse the existing listener logic (enable Run button)
- Strong security note: use `textContent` not `innerHTML` for filename in genStatus

## Valid Critiques Accepted

1. **Debounce overwrite risk** — `onDotFileSelected()` must cancel any pending `genDebounce` timer and clear `genCountdown` to prevent in-flight NL generation from overwriting the uploaded DOT. Add `clearTimeout(genDebounce); clearInterval(genCountdown);` at the top of the handler.

2. **resetCreatePage() gap** — Both `clearCreateForm()` and `resetCreatePage()` must reset `uploadedFileName = null` and `dotFileInput.value = ''`. The success path after `runGenerated()` calls `resetCreatePage()`, not `clearCreateForm()`.

3. **HTTP-level markup test** — Create `CreateDotUploadTest.kt` (Kotest FunSpec, same pattern as `DocsEndpointTest`) asserting `GET /` contains the file input id, `accept=".dot"`, and the JS handler name.

4. **"No new Kotlin code" phrasing** — Rephrase as "no new server routes, no new server handlers, no new Gradle dependencies".

## Critiques Rejected (with reasoning)

1. **dot-header-row layout concern** — Moot. The user interview resolved button placement to the **run-row** (next to Run Pipeline / Cancel buttons), not the header row. Layout concern does not apply there.

2. **Codex draft preserves nlInput on upload** — Rejected. The user interview explicitly chose "clear NL input on upload" for a clean state.

3. **Codex suggests button in dot-header-row** — Rejected per user interview preference for run-row placement.

## Interview Refinements Applied

- **Button placement**: run-row (next to Cancel / Run Pipeline), not dot-header-row
- **NL prompt on upload**: clear `#nlInput` on file upload (clean state)

## Final Decisions

- Button label: "📂 Upload .dot" in the run-row, left of Cancel (when visible) and Run Pipeline
- Hidden `<input type="file" id="dotFileInput" accept=".dot">` placed in the `create-section` div (after dotPreview textarea)
- JS handler name: `onDotFileSelected()` (consistent with Claude draft naming)
- State var: `uploadedFileName` (module-scope, null until a file is loaded)
- Debounce cancellation: cancel `genDebounce` + `genCountdown` on file select
- Reset: both `clearCreateForm()` and `resetCreatePage()` reset file input + uploadedFileName
- NL input: cleared on upload
- Iterate mode: upload does not touch `iterateSourceId`; upload button remains visible in iterate mode (useful for "replace DOT" during iteration)
- Test: new `CreateDotUploadTest.kt` asserting markup presence on `GET /`
- Docs: update `webAppTabContent()` to add Option C (Upload .dot file) in Creating a Pipeline section
