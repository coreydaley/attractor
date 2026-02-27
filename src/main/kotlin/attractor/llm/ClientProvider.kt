package attractor.llm

import attractor.db.RunStore
import attractor.llm.adapters.*

object ClientProvider {

    fun getClient(
        config: LlmExecutionConfig,
        env: Map<String, String> = System.getenv()
    ): Client {
        val providers = mutableMapOf<String, attractor.llm.adapters.ProviderAdapter>()
        var firstProvider: String? = null

        if (config.isProviderEnabled("anthropic")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["ANTHROPIC_API_KEY"] ?: ""
                    if (key.isNotBlank()) {
                        providers["anthropic"] = AnthropicAdapter(apiKey = key)
                        if (firstProvider == null) firstProvider = "anthropic"
                    }
                }
                ExecutionMode.CLI -> {
                    providers["anthropic"] = AnthropicCliAdapter(config.cliCommands.anthropic)
                    if (firstProvider == null) firstProvider = "anthropic"
                }
            }
        }

        if (config.isProviderEnabled("openai")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["OPENAI_API_KEY"] ?: ""
                    if (key.isNotBlank()) {
                        providers["openai"] = OpenAIAdapter(apiKey = key)
                        if (firstProvider == null) firstProvider = "openai"
                    }
                }
                ExecutionMode.CLI -> {
                    providers["openai"] = OpenAICliAdapter(config.cliCommands.openai)
                    if (firstProvider == null) firstProvider = "openai"
                }
            }
        }

        if (config.isProviderEnabled("gemini")) {
            when (config.mode) {
                ExecutionMode.API -> {
                    val key = env["GEMINI_API_KEY"] ?: env["GOOGLE_API_KEY"] ?: ""
                    if (key.isNotBlank()) {
                        providers["gemini"] = GeminiAdapter(apiKey = key)
                        if (firstProvider == null) firstProvider = "gemini"
                    }
                }
                ExecutionMode.CLI -> {
                    providers["gemini"] = GeminiCliAdapter(config.cliCommands.gemini)
                    if (firstProvider == null) firstProvider = "gemini"
                }
            }
        }

        if (providers.isEmpty()) {
            throw ConfigurationError(
                "No provider available. Enable at least one provider in Settings."
            )
        }

        return Client(providers, firstProvider)
    }

    fun fromStore(store: RunStore): Client {
        val config = LlmExecutionConfig.from(store)
        return getClient(config)
    }
}
