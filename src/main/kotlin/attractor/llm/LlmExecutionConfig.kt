package attractor.llm

import attractor.db.RunStore

enum class ExecutionMode { API, CLI }

data class ProviderToggles(
    val anthropic: Boolean,
    val openai: Boolean,
    val gemini: Boolean,
    val copilot: Boolean,
    val custom: Boolean
)

data class CliCommands(
    val anthropic: String,
    val openai: String,
    val gemini: String,
    val copilot: String
)

data class CustomApiConfig(
    val host: String,
    val port: String,
    val apiKey: String,
    val model: String
) {
    val baseUrl: String get() = if (port.isBlank()) host else "$host:$port"
}

data class LlmExecutionConfig(
    val mode: ExecutionMode,
    val providerToggles: ProviderToggles,
    val cliCommands: CliCommands,
    val customApiConfig: CustomApiConfig
) {
    fun isProviderEnabled(name: String): Boolean = when (name) {
        "anthropic" -> providerToggles.anthropic
        "openai"    -> providerToggles.openai
        "gemini"    -> providerToggles.gemini
        "copilot"   -> providerToggles.copilot
        "custom"    -> providerToggles.custom
        else        -> false
    }

    companion object {
        private fun parseBool(value: String?, default: Boolean = true): Boolean =
            when (value?.lowercase()?.trim()) {
                "false" -> false
                "true"  -> true
                else    -> default
            }

        // Precedence for each value: DB setting > env var > hardcoded default.
        // Env vars allow bootstrapping a fresh container without touching the UI.
        fun from(store: RunStore, env: Map<String, String> = System.getenv()): LlmExecutionConfig {
            fun db(key: String) = store.getSetting(key)
            val mode = when (db("execution_mode")?.lowercase()?.trim()) {
                "cli" -> ExecutionMode.CLI
                else  -> ExecutionMode.API
            }
            val modeKey = if (mode == ExecutionMode.API) "api" else "cli"
            return LlmExecutionConfig(
                mode = mode,
                providerToggles = ProviderToggles(
                    anthropic = parseBool(db("provider_anthropic_${modeKey}_enabled") ?: db("provider_anthropic_enabled"), default = false),
                    openai    = parseBool(db("provider_openai_${modeKey}_enabled")    ?: db("provider_openai_enabled"),    default = false),
                    gemini    = parseBool(db("provider_gemini_${modeKey}_enabled")    ?: db("provider_gemini_enabled"),    default = false),
                    copilot   = parseBool(db("provider_copilot_enabled"), default = false),
                    custom    = parseBool(db("provider_custom_enabled")
                                    ?: env["ATTRACTOR_CUSTOM_API_ENABLED"], default = false)
                ),
                cliCommands = CliCommands(
                    anthropic = db("cli_anthropic_command") ?: "claude --dangerously-skip-permissions -p {prompt}",
                    openai    = db("cli_openai_command")    ?: "codex --full-auto {prompt}",
                    gemini    = db("cli_gemini_command")    ?: "gemini --yolo -p {prompt}",
                    copilot   = db("cli_copilot_command")   ?: "copilot --allow-all-tools -p {prompt}"
                ),
                customApiConfig = CustomApiConfig(
                    host   = db("custom_api_host")  ?: env["ATTRACTOR_CUSTOM_API_HOST"]  ?: "http://localhost",
                    port   = db("custom_api_port")  ?: env["ATTRACTOR_CUSTOM_API_PORT"]  ?: "11434",
                    apiKey = db("custom_api_key")   ?: env["ATTRACTOR_CUSTOM_API_KEY"]   ?: "",
                    model  = db("custom_api_model") ?: env["ATTRACTOR_CUSTOM_API_MODEL"] ?: "llama3.2"
                )
            )
        }
    }
}
