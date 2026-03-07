package attractor.llm

object ModelSelection {

    fun selectModel(
        config: LlmExecutionConfig,
        env: Map<String, String> = System.getenv(),
        agentId: String = "",
        modelId: String = ""
    ): Pair<String, String> {
        // Explicit override: if agentId is provided, validate and use it directly
        if (agentId.isNotBlank()) {
            if (!config.isProviderEnabled(agentId)) {
                throw ConfigurationError(
                    "Selected agent '$agentId' is not enabled. Enable it in Settings."
                )
            }
            if (config.mode == ExecutionMode.API && agentId != "custom") {
                val key = when (agentId) {
                    "anthropic" -> env["ATTRACTOR_ANTHROPIC_API_KEY"] ?: ""
                    "openai"    -> env["ATTRACTOR_OPENAI_API_KEY"] ?: ""
                    "gemini"    -> env["ATTRACTOR_GEMINI_API_KEY"] ?: env["ATTRACTOR_GOOGLE_API_KEY"] ?: ""
                    else        -> ""
                }
                if (key.isBlank()) {
                    throw ConfigurationError(
                        "Selected agent '$agentId' has no API key configured."
                    )
                }
            }
            val resolvedModel = modelId.ifBlank { defaultModelForAgent(agentId, config) }
            return agentId to resolvedModel
        }

        // Auto-select with optional modelId hint: priority order, substitute modelId when provided
        // Priority: Anthropic → OpenAI → Gemini → Copilot → Custom
        if (config.isProviderEnabled("anthropic")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["ATTRACTOR_ANTHROPIC_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "anthropic" to modelId.ifBlank { "claude-sonnet-4-6" }
                }
                ExecutionMode.CLI -> return "anthropic" to modelId.ifBlank { "claude-sonnet-4-6" }
            }
        }

        if (config.isProviderEnabled("openai")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["ATTRACTOR_OPENAI_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "openai" to modelId.ifBlank { "gpt-4o-mini" }
                }
                ExecutionMode.CLI -> return "openai" to modelId.ifBlank { "gpt-4o-mini" }
            }
        }

        if (config.isProviderEnabled("gemini")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["ATTRACTOR_GEMINI_API_KEY"] ?: env["ATTRACTOR_GOOGLE_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "gemini" to modelId.ifBlank { "gemini-2.0-flash" }
                }
                ExecutionMode.CLI -> return "gemini" to modelId.ifBlank { "gemini-2.0-flash" }
            }
        }

        if (config.isProviderEnabled("copilot") && config.mode == ExecutionMode.CLI) {
            return "copilot" to "copilot"
        }

        if (config.isProviderEnabled("custom") && config.mode == ExecutionMode.API) {
            return "custom" to config.customApiConfig.model
        }

        throw ConfigurationError(
            "No LLM provider available. Enable at least one provider in Settings."
        )
    }

    private fun defaultModelForAgent(agentId: String, config: LlmExecutionConfig): String =
        when (agentId) {
            "anthropic" -> "claude-sonnet-4-6"
            "openai"    -> "gpt-4o-mini"
            "gemini"    -> "gemini-2.0-flash"
            "copilot"   -> "copilot"
            "custom"    -> config.customApiConfig.model
            else        -> agentId
        }
}
