# Critique: SPRINT-009-CLAUDE-DRAFT

## Overall Assessment

The draft is directionally strong and mostly aligned with the intent, but it has a few factual/technical mismatches that will cause implementation friction, plus some missing coverage in areas explicitly called out by the Sprint 009 intent.

## High-Priority Findings

1. **`Client` mocking approach is not implementable as written**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:221-223`, `:276-277`  
The draft says to use a mock `Client`/anonymous object that “implements `Client`.” In this codebase, `Client` is a concrete class (not interface/open), so it cannot be implemented/subclassed this way. It also references `GenerateResponse`, which is not a real type in `llm` (`GenerateResult`/`LlmResponse` are).  
**Fix:** Plan tests using a real `Client` instance wired with a fake `ProviderAdapter`, or add a minimal test seam in `LlmFailureDiagnoser` to isolate parse behavior without network calls.

2. **“No production code changes” conflicts with the draft’s own fallback strategy**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:15`, `:249`, `:257`  
The overview/DoD require tests-only with zero production changes, but the risks section proposes changing `parseResponse()` visibility (`internal`) for test access. That is a production-code modification.  
**Fix:** Pick one policy: either strict black-box testing via `analyze()` + fake adapter, or explicitly allow minimal testability seams in DoD.

3. **Intent-required “concurrent access” edge case is not planned as a concrete test**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:149-158`, `:280`  
Concurrency testing for `Context.incrementInt()` is only listed as an open question, not in the execution plan. The intent explicitly identifies concurrent access as a critical edge case to enumerate.  
**Fix:** Promote a bounded, deterministic multi-thread increment test into Phase 4 and DoD.

## Medium-Priority Findings

1. **Architecture count is internally inconsistent (7 files listed, “6 new files” stated)**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:55-64`, `:66`  
The tree shows seven new files, but text says “~47 across 6 new files.”  
**Fix:** Correct the count to avoid confusion in acceptance tracking.

2. **`LlmFailureDiagnoser` described as pure/no-IO, which is inaccurate**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:13-15`  
`LlmFailureDiagnoser.analyze()` calls `generate(...)` and reads stage artifacts from disk. Tests can remain hermetic, but the module itself is not pure.  
**Fix:** Reword to “test target is parse/fallback behavior with hermetic stubs” instead of calling the module pure.

3. **Stylesheet parser error coverage is thin relative to intent**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:175-177`  
Intent calls out parse errors; plan only explicitly covers blank input and value parsing.  
**Fix:** Add malformed/partial stylesheet tests (bad selector, missing braces/colon, truncated declarations) asserting graceful behavior.

4. **Edge label normalization test wording may not match current implementation**  
Reference: `docs/sprints/drafts/SPRINT-009-CLAUDE-DRAFT.md:90`  
Current regex strips `K -` (with space), not `K-` (no space). The wording can produce a false expectation.  
**Fix:** Align test case wording to actual accepted prefixes (`[K]`, `K)`, `K -`) unless behavior is intentionally being expanded.

## Suggested Edits Before Implementation

1. Replace the `Client` mocking plan with a fake `ProviderAdapter`-backed `Client` or an explicit parse helper seam.
2. Resolve the tests-only vs testability-seam contradiction and reflect it in DoD.
3. Add a concrete `Context.incrementInt()` concurrency test to the main plan and DoD.
4. Add malformed stylesheet parse tests, not just blank input tests.
5. Fix internal numeric/file-count inconsistencies and type-name references.

## Bottom Line

This is a solid baseline, but it needs one tightening pass to remove a real implementation blocker (`Client` mocking/type mismatch) and to fully satisfy the intent’s required edge-case coverage (especially concurrency and parser error handling).
