package attractor.llm

import attractor.db.RunStore

enum class ExecutionMode { API, CLI }

data class ProviderToggles(
    val anthropic: Boolean,
    val openai: Boolean,
    val gemini: Boolean
)

data class CliCommands(
    val anthropic: String,
    val openai: String,
    val gemini: String
)

data class LlmExecutionConfig(
    val mode: ExecutionMode,
    val providerToggles: ProviderToggles,
    val cliCommands: CliCommands
) {
    fun isProviderEnabled(name: String): Boolean = when (name) {
        "anthropic" -> providerToggles.anthropic
        "openai"    -> providerToggles.openai
        "gemini"    -> providerToggles.gemini
        else        -> false
    }

    companion object {
        private fun parseBool(value: String?, default: Boolean = true): Boolean =
            when (value?.lowercase()?.trim()) {
                "false" -> false
                "true"  -> true
                else    -> default
            }

        fun from(store: RunStore): LlmExecutionConfig {
            val mode = when (store.getSetting("execution_mode")?.lowercase()?.trim()) {
                "cli" -> ExecutionMode.CLI
                else  -> ExecutionMode.API
            }
            return LlmExecutionConfig(
                mode = mode,
                providerToggles = ProviderToggles(
                    anthropic = parseBool(store.getSetting("provider_anthropic_enabled")),
                    openai    = parseBool(store.getSetting("provider_openai_enabled")),
                    gemini    = parseBool(store.getSetting("provider_gemini_enabled"))
                ),
                cliCommands = CliCommands(
                    anthropic = store.getSetting("cli_anthropic_command") ?: "claude -p {prompt}",
                    openai    = store.getSetting("cli_openai_command")    ?: "codex -p {prompt}",
                    gemini    = store.getSetting("cli_gemini_command")    ?: "gemini -p {prompt}"
                )
            )
        }
    }
}
