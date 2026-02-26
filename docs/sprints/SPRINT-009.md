# Sprint 009: Critical Path Test Coverage

## Overview

Eight sprints of feature work have accumulated a growing body of complex, deterministic logic
with zero dedicated unit test coverage. The 5 existing test files focus on the DOT parser,
condition evaluator, engine integration, lint rules, and web-layer state — written either at
system-inception or as sprint deliverables. Core modules in the state, engine, style, and
transform layers have been exercised only as side-effects of higher-level tests, leaving their
exact behavioral contracts implicit and unverifiable in isolation.

This sprint writes focused unit tests for eight modules: `EdgeSelector`, `RetryPolicy`/`BackoffConfig`,
`Checkpoint`, `Context`, `Stylesheet`/`StylesheetParser`, `StylesheetApplicationTransform`,
`VariableExpansionTransform`, and `LlmFailureDiagnoser`'s parse/fallback path. All tests are
hermetic — no LLM API calls, no network I/O, no fixed port binding, no global mutable state.
`LlmFailureDiagnoser` tests use a fake `ProviderAdapter` to stub the LLM response, exercising
the full parse path without credentials. No production code changes are required.

The expected outcome is a tighter safety net for future refactors: changes to routing logic,
backoff math, checkpoint format, or stylesheet specificity rules will fail loudly at the test
level rather than silently misbehaving in production pipelines.

## Use Cases

1. **Routing regressions are caught immediately**: A future change to `EdgeSelector.select()` that
   accidentally drops Step 3 (suggested next IDs) is caught by dedicated edge-case tests before
   reaching a running pipeline.

2. **Backoff math is documented and bounded**: A developer can read `RetryPolicyTest` and know that
   delays are capped at `maxDelayMs` and jitter stays within the `[0.5×, 1.5×]` band.

3. **Checkpoint roundtrip verified**: A serialization format change (e.g., adding a new field to
   `CheckpointData`) is caught if it breaks `save()`/`load()` compatibility.

4. **Context coercion contract is clear**: `Context.getInt("key")` coerces numeric strings,
   returns defaults for missing or non-numeric keys — explicitly verified.

5. **Stylesheet specificity is enforced**: A universal rule `{ llm_model: gpt-4o }` does not
   override an id rule `#mynode { llm_model: claude-opus }` — verified directly.

6. **Variable expansion no-ops cleanly**: When a pipeline graph has no `goal`, the transform
   returns the graph unchanged and does not corrupt node attributes.

7. **Diagnoser parse fallback is robust**: When the LLM prepends its JSON response with
   `"Here is my analysis:\n{...}"`, the extractor still produces a correct `DiagnosisResult`.

## Architecture

### Coverage Target Map

```text
engine/
  EdgeSelector.kt              -> EdgeSelectorTest.kt         (8 tests)
  RetryPolicy.kt               -> RetryPolicyTest.kt          (7 tests)
  FailureDiagnoser.kt          -> LlmFailureDiagnoserTest.kt  (5 tests)

state/
  Checkpoint.kt                -> CheckpointTest.kt           (7 tests)
  Context.kt                   -> ContextTest.kt              (9 tests, incl. concurrency)

style/
  Stylesheet.kt                -> StylesheetTest.kt           (8 tests)

transform/
  VariableExpansionTransform.kt -> VariableExpansionTransformTest.kt (5 tests)
  StylesheetApplicationTransform.kt -> StylesheetApplicationTransformTest.kt (3 tests)

Total new test files: 8
Total new tests: ~52
```

### Test Strategy

```text
Unit tests only
  -> deterministic inputs/outputs; no wall-clock sleeps
  -> temp directories via Files.createTempDirectory() for filesystem behaviors
  -> fake ProviderAdapter anonymous object for LlmFailureDiagnoser stubs
  -> no HTTP ports, no live LLM/API calls, no shared global state
```

### Guardrail Principles

- Assert externally observable behavior and outputs, not private internals.
- Each test file is scoped to one module family.
- Use existing Kotest `FunSpec` + `io.kotest` matcher conventions.
- No new production dependencies; test-only helpers only.

## Implementation Plan

### Phase 1: EdgeSelectorTest (~15%)

**Files:**
- `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` (create)

**Why critical**: `EdgeSelector.select()` is the core routing decision — an off-by-one in
priority means pipelines take silent wrong paths. Section 3.3 of the spec defines the 5-step
algorithm.

**Tasks:**
- [ ] `select() returns null when node has no outgoing edges`
- [ ] `Step 1 — condition match takes priority over label, suggested IDs, and weight`
- [ ] `Step 2 — preferred label match used when no condition matches`
- [ ] `Step 3 — suggestedNextIds respected when preferred label doesn't match any edge`
- [ ] `Step 4/5 — unconditional edge with highest weight is selected`
- [ ] `Lexical tiebreak — alphabetically first to-ID wins on equal weight`
- [ ] `normalizeLabel strips [K] prefix, K) prefix, K - prefix and lowercases`
- [ ] `select() falls back to any edge (conditional) when no unconditional edges exist`

**Setup**: Build `DotGraph`/`DotNode`/`DotEdge` objects directly; no parsing needed.
Use a small helper `fun makeEdge(to: String, condition: String = "", label: String = "", weight: Int = 1): DotEdge`.

---

### Phase 2: RetryPolicyTest (~12%)

**Files:**
- `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` (create)

**Why critical**: Retry budget and backoff timing directly affect pipeline recovery behavior.

**Tasks:**
- [ ] `BackoffConfig with jitter=false returns exact base delay for attempt 1`
- [ ] `BackoffConfig with jitter=false returns exact base delay for attempt N (exponential growth)`
- [ ] `BackoffConfig delay is capped at maxDelayMs before jitter is applied`
- [ ] `BackoffConfig with jitter=true stays within [0.5×, 1.5×] band of base delay`
- [ ] `RetryPolicy.NONE has maxAttempts=1`
- [ ] `RetryPolicy.fromNode uses node max_retries when set`
- [ ] `RetryPolicy.fromNode falls back to graph default when node has no max_retries attribute`

**Notes**: Do NOT sleep in tests. Assert the return value of `delayForAttempt()` is within the
expected range. For jitter, run multiple samples (e.g., 20 iterations) and assert all are in
`[base * 0.5, base * 1.5]` capped at `maxDelayMs`.

---

### Phase 3: CheckpointTest (~18%)

**Files:**
- `src/test/kotlin/attractor/state/CheckpointTest.kt` (create)

**Why critical**: Resume correctness depends entirely on checkpoint roundtrip fidelity.

**Tasks:**
- [ ] `save() + load() roundtrip preserves currentNode, completedNodes, nodeRetries, context, stageDurations`
- [ ] `load() returns null when checkpoint.json does not exist`
- [ ] `load() returns null when checkpoint.json contains invalid JSON`
- [ ] `create() extracts retry counts from Context keys prefixed with internal.retry_count.`
- [ ] `create() captures all context values in the checkpoint`
- [ ] `save() creates the logsRoot directory if it does not exist`
- [ ] `saved checkpoint JSON is compact (single line — no newlines at top level)`

**Setup**: Use `Files.createTempDirectory()` scoped to each test; delete in `afterTest`.

---

### Phase 4: ContextTest (~18%)

**Files:**
- `src/test/kotlin/attractor/state/ContextTest.kt` (create)

**Why critical**: `Context` is shared mutable state threaded through the entire engine. Bugs in
type coercion or clone isolation produce silent wrong decisions.

**Tasks:**
- [ ] `set() + get() stores and retrieves value`
- [ ] `get() returns default when key absent`
- [ ] `getInt() coerces String "42" to 42`
- [ ] `getInt() returns default for non-numeric String "hello"`
- [ ] `getInt() returns Int value for Number types`
- [ ] `incrementInt() starts from 0 and returns incremented value`
- [ ] `clone() produces independent copy — mutations do not propagate to source`
- [ ] `snapshot() returns a copy — mutations to snapshot do not affect Context`
- [ ] `incrementInt() is thread-safe — 10 threads × 1000 increments = 10000`

**Notes for concurrency test**: Use a fixed thread pool of 10 threads, a `CountDownLatch` for
synchronization, and `ExecutorService.awaitTermination`. Assert `context.getInt("counter") == 10000`.

---

### Phase 5: StylesheetTest (~15%)

**Files:**
- `src/test/kotlin/attractor/style/StylesheetTest.kt` (create)

**Why critical**: Stylesheet controls which LLM model is used per node. Wrong specificity
resolution silently assigns wrong models.

**Tasks:**
- [ ] `Universal selector (*) applies to all nodes`
- [ ] `Class selector (.cls) matches nodes with matching cssClass`
- [ ] `Id selector (#id) matches only the named node`
- [ ] `Id selector overrides class selector overrides universal (specificity chain)`
- [ ] `Existing node attribute is NOT overwritten by a matching stylesheet rule`
- [ ] `StylesheetParser handles quoted and unquoted property values`
- [ ] `StylesheetParser returns empty list for blank input`
- [ ] `StylesheetParser degrades gracefully on malformed input (missing brace, bad selector)`

**Setup**: Construct `DotNode` instances directly; call `Stylesheet.applyToNode()` and
`Stylesheet.applyToGraph()`.

---

### Phase 6: VariableExpansionTransformTest and StylesheetApplicationTransformTest (~10%)

**Files:**
- `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` (create)
- `src/test/kotlin/attractor/transform/StylesheetApplicationTransformTest.kt` (create)

**Tasks (VariableExpansionTransform):**
- [ ] `$goal in prompt is replaced with graph goal value`
- [ ] `$goal in label is replaced with graph goal value`
- [ ] `Graph with empty goal is returned unchanged (no-op)`
- [ ] `Node without $goal in prompt is unchanged`
- [ ] `Multiple $goal occurrences in a single prompt are all replaced`

**Tasks (StylesheetApplicationTransform):**
- [ ] `Transform applies stylesheet to all nodes in the graph`
- [ ] `Transform is a no-op when model_stylesheet is blank`
- [ ] `Existing node attributes are not overwritten by stylesheet`

**Setup**: Parse a DOT string via `Parser.parse()` or construct `DotGraph` directly. Assert node
`attrs["prompt"]` / `attrs["label"]` / `attrs["llm_model"]` have expected values after transform.

---

### Phase 7: LlmFailureDiagnoserTest (~7%)

**Files:**
- `src/test/kotlin/attractor/engine/LlmFailureDiagnoserTest.kt` (create)

**Why critical**: The JSON extraction fallback (preamble stripping) is a non-trivial path that
has never been exercised in isolation.

**Approach**: Create a fake `ProviderAdapter` anonymous object that returns a canned `LlmResponse`
containing a preset text string. Construct `Client(mapOf("fake" to fakeAdapter), defaultProvider = "fake")`.
Pass the client to `LlmFailureDiagnoser`. Call `analyze(ctx)` to exercise the full parse path.

```kotlin
val fakeAdapter = object : ProviderAdapter {
    override val name = "fake"
    override fun complete(request: Request): LlmResponse = LlmResponse(
        id = "t", model = "fake", provider = "fake",
        message = Message.assistant(cannedResponse),
        finishReason = FinishReason("stop"), usage = Usage()
    )
    override fun stream(request: Request) = emptySequence<StreamEvent>()
}
val client = Client(mapOf("fake" to fakeAdapter), defaultProvider = "fake")
val diagnoser = LlmFailureDiagnoser(client)
```

**Tasks:**
- [ ] `NullFailureDiagnoser returns ABORT with strategy="ABORT" and recoverable=false`
- [ ] `NullFailureDiagnoser custom reason string is preserved in explanation`
- [ ] `LlmFailureDiagnoser: valid JSON response parses to correct DiagnosisResult`
- [ ] `LlmFailureDiagnoser: JSON embedded in LLM preamble text is extracted correctly`
- [ ] `LlmFailureDiagnoser: completely invalid/non-JSON text produces ABORT fallback`

---

### Phase 8: Suite Validation (~5%)

**Tasks:**
- [ ] Run `gradle test` and confirm all 8 new test files are executed
- [ ] Confirm all new tests pass (0 failures)
- [ ] Confirm all 61 existing tests still pass (0 regressions)
- [ ] Confirm no test uses network calls, shared mutable global state, or fixed ports
- [ ] Run `gradle jar` and confirm build succeeds

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` | Create | 5-step routing algorithm, label normalization |
| `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` | Create | Backoff math, jitter bounds, `fromNode()` factory |
| `src/test/kotlin/attractor/engine/LlmFailureDiagnoserTest.kt` | Create | JSON parsing, preamble extraction, ABORT fallback |
| `src/test/kotlin/attractor/state/CheckpointTest.kt` | Create | Save/load roundtrip, corrupt file, `create()` extraction |
| `src/test/kotlin/attractor/state/ContextTest.kt` | Create | Type coercion, clone isolation, concurrent increment |
| `src/test/kotlin/attractor/style/StylesheetTest.kt` | Create | Selector specificity, non-overwrite, malformed input |
| `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` | Create | `$goal` substitution, blank-goal no-op |
| `src/test/kotlin/attractor/transform/StylesheetApplicationTransformTest.kt` | Create | Graph-level stylesheet application |

## Definition of Done

- [ ] `EdgeSelectorTest.kt` exists with 8 tests covering all 5 selection steps; all pass
- [ ] `RetryPolicyTest.kt` exists with 7 tests; backoff cap and jitter bounds verified; all pass
- [ ] `LlmFailureDiagnoserTest.kt` exists with 5 tests; preamble extraction tested; all pass
- [ ] `CheckpointTest.kt` exists with 7 tests; save/load roundtrip and corrupt file verified; all pass
- [ ] `ContextTest.kt` exists with 9 tests including concurrent `incrementInt()` stress; all pass
- [ ] `StylesheetTest.kt` exists with 8 tests including malformed input graceful degradation; all pass
- [ ] `VariableExpansionTransformTest.kt` exists with 5 tests; all pass
- [ ] `StylesheetApplicationTransformTest.kt` exists with 3 tests; all pass
- [ ] All ~52 new tests pass with `gradle test`
- [ ] All 61 existing tests continue to pass — zero regressions
- [ ] No new production code changes
- [ ] No new Gradle dependencies
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] All tests are hermetic (no network, no fixed ports, temp files cleaned up)

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Jitter test is flaky (random values) | Low | Medium | Sample 20+ delay values; assert all are within `[base*0.5, base*1.5]` |
| Concurrent `incrementInt()` test is slow | Low | Low | Use fixed thread pool with `awaitTermination`; not expected to take >1s |
| `LlmFailureDiagnoser.analyze()` calls `generate()` which has retry logic | Medium | Low | The retry logic makes network calls on failure — the fake adapter must not throw, only return |
| `DotGraph`/`DotNode` construction for EdgeSelector tests is verbose | Medium | Low | Extract a `makeGraph()` helper within the test file |
| Checkpoint tests leave temp files if test fails before cleanup | Low | Low | Use `afterTest { tmpDir.deleteRecursively() }` Kotest hook |
| `StylesheetApplicationTransform` is a thin wrapper — trivially tested | Low | Low | 3 tests are sufficient; do not over-test trivial delegation |

## Security Considerations

- No new endpoints, network access, or process spawning
- Temp directory tests use `Files.createTempDirectory()` — process-scoped, cleaned up after each test
- Fake adapter returns canned strings with no sensitive data

## Dependencies

- Sprint 008 (completed) — establishes Kotest FunSpec pattern and `io.kotest` matcher conventions
- No external dependencies

## Open Questions

1. Should `Context.incrementInt()` concurrency test be tagged as a `@Tag("slow")` test for
   future CI filtering? Currently not expected to be slow.
2. Do we want to add minimum test counts per module to the DoD for future sprints (e.g., "at
   least 5 tests per new test file")?
