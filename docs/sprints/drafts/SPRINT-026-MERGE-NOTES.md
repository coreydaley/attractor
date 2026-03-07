# Sprint 026 Merge Notes

## Claude Draft Strengths
- Detailed task-level breakdowns: exact DB keys, precise API endpoint changes, specific line references in WebMonitorServer.kt
- Thorough propagation chain for agentId/modelId through RunOptions → ProjectRunner → LlmCodergenBackend
- Explicit localStorage scoping by execution mode to prevent cross-mode confusion
- Good security section (HTTPS-only URLs, treat cached JSON as untrusted, size limits)
- Per-route breakdown for which endpoints need agentId/modelId param parsing
- Identified `node.llmModel`/`node.llmProvider` per-node DOT attributes as the existing override mechanism to build on

## Codex Draft Strengths
- Correctly followed the interview refinement (DB-backed live fetch, no agents.json/Python/Action)
- Clean architecture diagram showing data flow end-to-end
- Server-filtered `/api/agents` recommendation: cleaner client, safer edge-case handling
- Graceful fallback for invalid overrides (invalid model → auto-select + warning) vs hard throw
- Suggested "last refreshed + model count" display per provider in Settings
- Noted that RestApiRouter.kt (not just WebMonitorServer.kt) may be the right home for new endpoints

## Valid Critiques Accepted
- **Drop agents.json / Python script / GitHub Action entirely** — the Claude draft was written before the interview, so it still had the old approach. The final sprint follows the refined DB-backed approach.
- **Server-filtered `/api/agents`** — server returns only agents that are both enabled and compatible with current execution mode. Client rendering is simpler and safer.
- **localStorage persistence is non-optional** — Add explicit tasks, scope key by execution mode.
- **Custom agent dropdown is locked in** — "Use custom_api_model setting" disabled option. Not an open question.
- **Graceful fallback for invalid model** — if a selected model is no longer in the fetched list, reset to auto-select with a non-blocking warning rather than a hard error crash. (Backend ConfigurationError still thrown if provider not enabled, but UI should prevent that.)

## Critiques Rejected (with reasoning)
- **"RestApiRouter.kt for new endpoints"** — Both Codex and existing code show that WebMonitorServer.kt handles endpoint registration (createContext). All 37 existing endpoints are in WebMonitorServer.kt, not RestApiRouter.kt. Keep new endpoints there.
- **"invalid model should fall through silently"** — Rejected for the backend. If an explicit agentId is provided for a disabled provider, we still throw ConfigurationError — the UI is responsible for preventing this via dropdown gating. Silent fallback would mask misconfiguration.

## Interview Refinements Applied
1. **No agents.json file, Python script, or GitHub Action** — replaced with ModelFetcher.kt + /api/settings/fetch-models + DB cache.
2. **Agent+model applies to BOTH generation AND project execution runs.**
3. **Custom agent**: disabled "Use custom_api_model setting" dropdown option.
4. **localStorage persistence**: explicit tasks, key scoped by execution mode.
5. **Refresh models button per Direct API provider** in Settings.

## Final Decisions
- Phase structure: (1) ModelFetcher + AgentCatalog, (2) /api/agents + /api/settings/fetch-models, (3) ModelSelection + RunOptions + DotGenerator + LlmCodergenBackend, (4) Settings UI, (5) Create/Iterate UI, (6) Tests, (7) Docs
- `/api/agents` is server-filtered by enabled providers + current execution mode
- DB keys: `models_{provider}_json` (JSON array) + `models_{provider}_fetched_at` (ISO timestamp)
- `ModelFetcher` for Direct API mode only; CLI mode always uses ModelCatalog.kt fallbacks
- Override error: explicit agentId for disabled provider → ConfigurationError; stale/unknown modelId → fall back to agent default + log warning
- `LlmCodergenBackend` carries `runAgentId`/`runModelId` as constructor params; node-level attrs take priority
