package attractor.llm

object ModelSelection {

    fun selectModel(
        config: LlmExecutionConfig,
        env: Map<String, String> = System.getenv()
    ): Pair<String, String> {
        // Priority: Anthropic → OpenAI → Gemini
        if (config.isProviderEnabled("anthropic")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["ANTHROPIC_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "anthropic" to "claude-sonnet-4-6"
                }
                ExecutionMode.CLI -> return "anthropic" to "claude-sonnet-4-6"
            }
        }

        if (config.isProviderEnabled("openai")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["OPENAI_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "openai" to "gpt-5.2-mini"
                }
                ExecutionMode.CLI -> return "openai" to "gpt-5.2-mini"
            }
        }

        if (config.isProviderEnabled("gemini")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["GEMINI_API_KEY"] ?: env["GOOGLE_API_KEY"] ?: ""
                    if (key.isNotBlank()) return "gemini" to "gemini-3-flash-preview"
                }
                ExecutionMode.CLI -> return "gemini" to "gemini-3-flash-preview"
            }
        }

        throw ConfigurationError(
            "No LLM provider available. Enable at least one provider in Settings."
        )
    }
}
