# Sprint 012 Merge Notes

## Claude Draft Strengths

- Domain-split command files (`PipelineCommands.kt`, `DotCommands.kt`, etc.) rather than monolithic `Commands.kt` — more maintainable as the command surface grows
- Fake JDK `HttpServer` strategy for hermetic tests — no new deps, matches project idiom
- Clear `CliContext` / `CliException` / `Formatter` separation — good abstraction layering
- Explicit `getStream()` SSE helper in `ApiClient`
- `events <id>` auto-exits when pipeline reaches terminal state — good UX for CI use

## Codex Draft Strengths

- Separate `artifact` resource group (vs `pipeline artifact*` sub-commands) — cleaner command grammar
- Explicit streaming subcommands: `dot generate-stream`, `dot fix-stream`, `dot iterate-stream` — covers the 3 missing streaming endpoints from Claude's draft
- Clean exit code contract: `0`=success, `1`=API/runtime error, `2`=usage error
- `pipeline watch --interval-ms --timeout-ms` — good parameterization
- `--on-conflict` flag uses `overwrite` wording matching the API query param
- Calls out `README.md` as an explicit deliverable

## Valid Critiques Accepted

1. **Missing endpoint coverage**: Claude's draft omitted `pipeline update` (PATCH) and all 3 DOT streaming endpoints. Adding:
   - `pipeline update <id>` → PATCH `/api/v1/pipelines/{id}`
   - `dot generate-stream --prompt <text>` → GET `/api/v1/dot/generate/stream`
   - `dot fix-stream --file <path>` → GET `/api/v1/dot/fix/stream`
   - `dot iterate-stream --file <path> --changes <text>` → GET `/api/v1/dot/iterate/stream`

2. **CLI JAR naming**: Use explicit `archiveBaseName = "attractor-cli"` in Gradle task so output is `attractor-cli-<version>.jar`, not `coreys-attractor-<version>-cli.jar`.

3. **Documentation deliverable**: Add `README.md` update as first-class file change with CLI quickstart section.

4. **Java 17 language**: Standardize on Java 21 throughout the sprint document (matching CI, Makefile, existing build).

5. **Exit code contract**: Codify `0`/`1`/`2` explicitly in the design and add to Definition of Done.

6. **Release workflow two-asset upload**: Define explicit discovery steps for both JARs; don't rely on single glob.

## Critiques Rejected (with reasoning)

- **Monolithic `Commands.kt`**: Codex grouped all commands in one file. Rejected — domain-split files (`PipelineCommands.kt`, `DotCommands.kt`, etc.) are more maintainable and easier to navigate. Codex's dispatcher pattern (`CliCommandDispatcher.kt`) is valuable as a routing layer and will be adopted as a separate file.
- **`--out` required for binary downloads**: Codex proposed requiring `--out <file>` always. Rejected per user interview — auto-derived filename is better UX (user confirmed preference).

## Interview Refinements Applied

1. Binary downloads auto-derive filename: `pipeline-<id>.zip`, `artifacts-<id>.zip`. `--output <file>` overrides.
2. `pipeline watch <id>` included with `--interval-ms` and `--timeout-ms` flags.
3. `bin/attractor` shell wrapper script included so users can invoke CLI as `attractor` without `java -jar`.

## Final Decisions

- **Command grammar**: `attractor [--host] [--output] <resource> <verb> [options]`
- **Resource groups**: `pipeline`, `artifact`, `dot`, `settings`, `models`, `events`
- **Separate resource group for artifacts** (Codex's idea): `artifact list <id>`, `artifact get <id> --path`, etc.
- **JAR name**: `attractor-cli-<version>.jar` (explicit archiveBaseName)
- **Tests**: JDK `HttpServer` fake (no new deps)
- **Shell wrapper**: `bin/attractor` included
- **Streaming commands**: all 3 DOT stream variants added as explicit subcommands
- **Exit codes**: 0 success, 1 API/runtime, 2 usage
- **Java 21** throughout
