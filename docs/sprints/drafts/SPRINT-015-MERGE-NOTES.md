# Sprint 015 Merge Notes

## Claude Draft Strengths
- Comprehensive gap audit across all three surfaces (REST, CLI, Web)
- Identified mock LLM strategy: set execution_mode=cli + provider_anthropic_enabled=true + cli_anthropic_command=echo in test store — exercises full request/response pipeline without a real model
- Interview result: user chose happy-path browser API tests with pipeline registration, full mock LLM integration, --version graceful output test
- Detailed per-verb coverage listing; showed exactly which HTTP methods/paths map to which CLI commands
- Correctly identified the `dot render` CLI flow returns JSON `{"svg":"..."}` (after confirming from source)

## Codex Draft Strengths
- "One solid test per command/route behavior class" framing — avoids combinatorial explosion
- Proposes separate `RestApiSpecRoutesTest.kt` file for OpenAPI/Swagger endpoints (cleaner separation)
- Adds Phase 5: CI verification and coverage inventory update (discipline check)
- Cleaner architecture diagram; more focused overview prose

## Valid Critiques Accepted
1. **`POST /api/v1/pipelines` execution**: Codex correctly identified that `PipelineRunner.submit()` IS called directly in `handleCreatePipeline`. Test must be designed around the HTTP contract only (assert 201 + `id` + `status`); background pipeline execution is acceptable side effect.
2. **Non-deterministic status expectations**: All "200 or 400" / "200 or 409" language replaced with exact assertions confirmed from handler source.
3. **`dot render` CLI**: Receives `{"svg":"..."}` JSON, writes text to file. Test uses JSON response fixture, asserts file contains SVG text and stdout says "Saved to output.svg". (Confirmed in DotCommands.kt:126-129.)
4. **Spec-route test file inconsistency**: Resolved — create `RestApiSpecRoutesTest.kt` as new file.
5. **`/api/pipelines` no method guard**: The handler (line 52-60 in WebMonitorServer.kt) has NO explicit guard for non-GET methods — it just silently exits, which causes connection close. Do NOT test `DELETE /api/pipelines -> 405`. Instead, only test 405 for routes that have explicit guards.
6. **`swagger.json` endpoint**: Returns `application/json` (same as openapi.json via handleSpecJson). NOT HTML. Codex's critique about this was WRONG — swagger.json is an alias for handleSpecJson, not handleSwaggerUi. `GET /api/v1/docs` is the HTML Swagger UI page.

## Critiques Rejected (with reasoning)
- **"LLM routes: parameter validation only"** (Codex draft): User explicitly chose full integration with mock LLM. The `echo` CLI mode approach is clean, hermetic, and exercises the full request pipeline. Accepted interview result over Codex's simpler approach.
- **"Skip --version"** (not explicitly said by Codex, but implied): User chose to test graceful output. Keeping it.

## Interview Refinements Applied
1. **Browser API happy path**: Pre-register a pipeline in the `WebMonitorServerBrowserApiTest` setup, then test the full route (not just boundary cases).
2. **Mock LLM**: Set store settings in `beforeSpec` so `DotGenerator` uses CLI mode + `echo` command. This enables `POST /dot/generate`, `POST /dot/fix`, `POST /dot/iterate` to return actual responses.
3. **--version**: Test exits 0 and produces some output (even if version string is empty in test context).

## Final Decisions
1. Create `RestApiSpecRoutesTest.kt` for openapi.json, openapi.yaml, swagger.json routes.
2. LLM happy-path tests in a new `RestApiLlmTest.kt` file (separate from RestApiRouterTest for clarity — mock LLM setup would otherwise pollute the non-LLM test setup).
3. Browser API method-guard tests only for routes with explicit guards (not /api/pipelines).
4. All binary response tests (artifacts.zip, export) assert `application/zip` content-type + status 200 (or appropriate error for missing logsRoot).
5. Phase 5 from Codex: add CI verification and brief endpoint checklist task.
