package attractor.llm

import attractor.db.RunStore

enum class ExecutionMode { API, CLI }

data class ProviderToggles(
    val anthropic: Boolean,
    val openai: Boolean,
    val gemini: Boolean,
    val copilot: Boolean
)

data class CliCommands(
    val anthropic: String,
    val openai: String,
    val gemini: String,
    val copilot: String
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
        "copilot"   -> providerToggles.copilot
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
                    anthropic = parseBool(store.getSetting("provider_anthropic_enabled"), default = false),
                    openai    = parseBool(store.getSetting("provider_openai_enabled"), default = false),
                    gemini    = parseBool(store.getSetting("provider_gemini_enabled"), default = false),
                    copilot   = parseBool(store.getSetting("provider_copilot_enabled"), default = false)
                ),
                cliCommands = CliCommands(
                    anthropic = store.getSetting("cli_anthropic_command") ?: "claude --dangerously-skip-permissions -p {prompt}",
                    openai    = store.getSetting("cli_openai_command")    ?: "codex exec --full-auto {prompt}",
                    gemini    = store.getSetting("cli_gemini_command")    ?: "gemini --yolo -p {prompt}",
                    copilot   = store.getSetting("cli_copilot_command")   ?: "copilot --allow-all-tools -p {prompt}"
                )
            )
        }
    }
}
