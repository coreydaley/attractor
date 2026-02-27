# Sprint 010 Merge Notes

## Claude Draft Strengths

- Detailed, phase-by-phase implementation plan with explicit handler function names and logic per endpoint
- Complete endpoint matrix covering all application features
- Comprehensive Definition of Done checklist organized by category
- Explicit security considerations for path traversal in artifact endpoints
- Normalized pipeline JSON response shape defined with all fields
- Good phasing strategy: router skeleton first, then CRUD, lifecycle, artifacts, etc.
- `GET /api/v1/dot/fix/stream` endpoint added beyond intent — a reasonable streaming parity addition

## Codex Draft Strengths

- More concise, better suited for a planning document (less overwhelming)
- Correctly uses `WebMonitorServer.kt` as a primary modification target (acknowledges SSE coupling)
- API spec document (`docs/api/rest-v1.md`) as an explicit deliverable — satisfies success criterion 1
- Two test files instead of one: separate `RestApiTest` and `SseTest` — better organization
- Parity tests: explicitly test that v1 and legacy routes produce equivalent results
- `POST /api/v1/pipelines/{id}/rerun` (correct verb from intent) not `run`
- Health endpoint question raised
- Cleaner resource model documentation
- Correctly adds `GET /api/v1/dot/iterate/stream` but omits `GET /api/v1/dot/fix/stream`

## Valid Critiques Accepted

1. **PipelineRunner is a Kotlin `object` (singleton)**: Cannot be constructor-injected. All calls
   must use `PipelineRunner.submit(dotSource, fileName, options, registry, store, ...)` form.
   RestApiRouter must take `registry` and `store` as constructor parameters (it does) but also
   needs access to the `onUpdate` / `broadcastUpdate` lambda that triggers SSE broadcasts.
   **Fix**: `RestApiRouter(registry, store, onUpdate: () -> Unit)` constructor.

2. **WebMonitorServer.kt is a modification target, not "unchanged"**: SSE client list (`sseClients`)
   and `broadcastUpdate()` are private. RestApiRouter needs to add REST SSE clients to the
   broadcast. **Fix**: `WebMonitorServer` gains an internal `restSseClients` list, a method
   `fun addRestSseClient(client)`, and the existing `broadcastUpdate()` fans out to both lists.
   This is still far less risky than a full WebMonitorServer refactor — just a few additions.

3. **Endpoint name `rerun` not `run`**: `POST /api/v1/pipelines/{id}/rerun` matches intent mapping
   and is clearer semantically. Accepted.

4. **`docs/api/rest-v1.md` API spec document as explicit deliverable**: Added to files table and
   Definition of Done.

5. **Expanded test coverage**: Two test files (`RestApiRouterTest.kt` and `RestApiSseTest.kt`),
   covering all endpoint families including artifacts, import/export, SSE, and security cases.

6. **Import body format**: Raw `application/zip` bytes (not multipart). Body is read via
   `ex.requestBody.readBytes()` + `ZipInputStream`, same as existing `/api/import-run`.

7. **`ModelCatalog.listModels(provider)` already exists**: Use directly; no uncertainty needed.

8. **Remove inaccurate "JSON body size-limited via executor"**: Removed from Security Considerations.

## Critiques Rejected (with reasoning)

1. **"WebMonitorServer.kt not separate file"** (Codex draft uses in-WebMonitorServer routing):
   The user explicitly chose `RestApiRouter.kt` (separate file) in the interview. The separation
   keeps the 2000+ line WebMonitorServer from growing further and makes the REST layer independently
   testable. The SSE coupling concern is addressed by the minimal `addRestSseClient()` addition.

2. **"Trim `GET /api/v1/dot/fix/stream`"** (Codex says not in intent map): It is a logical
   streaming parity addition — the existing `/api/fix-dot` is already SSE-streaming. Adding a
   stream endpoint is consistent with the intent to "support anything this application supports."
   Retained.

## Interview Refinements Applied

- New file `RestApiRouter.kt` (user's choice confirmed)
- `/api/v1/` versioned prefix (user's choice confirmed)
- `dotSource` excluded from list responses, included in single GET (user's choice confirmed)
- JSON request bodies (user's choice confirmed)

## Final Decisions

1. Architecture: `RestApiRouter.kt` (new file), registered via single `createContext("/api/v1/")` in `WebMonitorServer`
2. `WebMonitorServer` minimal additions: `restSseClients` list, `addRestSseClient()`, fanout in `broadcastUpdate()`
3. `RestApiRouter` constructor: `(registry: PipelineRegistry, store: RunStore, onUpdate: () -> Unit)`
4. PipelineRunner calls: `PipelineRunner.submit(..., registry, store, onUpdate)` — object pattern
5. Endpoint: `POST /api/v1/pipelines/{id}/rerun` (not `/run`)
6. New deliverable: `docs/api/rest-v1.md` API spec
7. Tests: `RestApiRouterTest.kt` (14+ integration tests) + `RestApiSseTest.kt` (SSE tests)
8. Import: raw `application/zip` body bytes
9. `ModelCatalog.listModels(null)` → all models
10. `GET /api/v1/dot/fix/stream` retained (streaming parity)
