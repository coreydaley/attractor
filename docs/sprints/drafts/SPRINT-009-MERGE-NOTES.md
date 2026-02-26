# Sprint 009 Merge Notes

## Claude Draft Strengths

- Comprehensive DoD list with per-file test counts
- `StylesheetApplicationTransform` coverage identified (via interview)
- Strong Checkpoint tests: `create()` from Context, retry key extraction, directory creation
- Explicit use of `Files.createTempDirectory()` + `afterTest` cleanup pattern
- Clear phasing rationale tied to risk priority

## Codex Draft Strengths

- Added "Suite Validation" phase (regression check pass after all new tests written)
- Named diagnoser test file `LlmFailureDiagnoserTest.kt` (more precise than `FailureDiagnoserTest.kt`)
- Guardrail principles section ("prefer behavioral assertions over implementation-coupled assertions")
- More explicit about concurrency test in the execution plan (not just an open question)
- Noted optional production seam for diagnoser if needed

## Valid Critiques Accepted

1. **`Client` is not an interface — cannot be "implemented"**: Fixed. Use a fake `ProviderAdapter`
   (which IS an interface) to construct a `Client(mapOf("fake" to fakeAdapter))`. No production
   code change required.

2. **"No production code changes" contradicts internal visibility suggestion**: Resolved. The
   fake ProviderAdapter approach tests through `analyze()` without any visibility change.

3. **Concurrent `incrementInt()` test was only an open question**: Promoted to main plan
   (Phase 4) and DoD. User confirmed YES during interview.

4. **File count inconsistency (7 files vs "6 new files" text)**: Fixed. There are 8 new test
   files (the 7 from Claude's draft + `StylesheetApplicationTransformTest.kt` from interview).

5. **LlmFailureDiagnoser described as "pure"**: Removed. The module reads disk artifacts and
   makes network calls. Tests are hermetic via fake adapter stubs.

6. **Stylesheet parser error coverage is thin**: Added explicit malformed stylesheet tests
   (bad selector, missing braces, truncated declaration).

7. **Label normalization wording**: Aligned to actual implementation (`K -` with space).

## Critiques Rejected (with reasoning)

- *"Remove/avoid redundant assertions in EngineTest"* — Rejected. The existing engine tests
  are integration-level; their coverage complements, not duplicates, unit tests. Removing
  them would reduce safety coverage.

## Interview Refinements Applied

1. **LlmFailureDiagnoser testing**: Mock Client via fake `ProviderAdapter` anonymous object
2. **Context concurrency test**: Include multi-threaded `incrementInt()` stress test in plan
3. **Scope**: Add `StylesheetApplicationTransformTest.kt` (not RunStore)

## Final Decisions

- 8 new test files targeting 8 modules
- Total new tests: ~55 (up from ~47 in Claude draft)
- No production code changes required
- Suite Validation phase added as final phase (from Codex)
- Test file naming: `LlmFailureDiagnoserTest.kt` (Codex's more precise naming)
- Guardrail principles added to Architecture section (Codex)
- All tests hermetic: no network, no fixed ports, temp files cleaned up
