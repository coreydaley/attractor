package attractor.llm

import attractor.db.RunStore

data class AgentEntry(
    val id: String,
    val displayName: String,
    /** Models available in Direct API mode. Null = not supported in this mode. */
    val directApiModels: List<AgentModelInfo>?,
    /** Models available in CLI subprocess mode. Null = not supported in this mode. */
    val cliModels: List<AgentModelInfo>?
)

object AgentCatalog {

    private val AGENT_DISPLAY_NAMES = mapOf(
        "anthropic" to "Anthropic",
        "openai"    to "OpenAI",
        "gemini"    to "Gemini",
        "copilot"   to "Copilot",
        "custom"    to "Custom API"
    )

    private val COPILOT_CLI_MODELS = listOf(
        AgentModelInfo("copilot", "Copilot (auto)")
    )

    /**
     * Build the full agent catalog.
     * For Direct API providers: reads from DB cache, falls back to ModelCatalog.kt.
     * For CLI providers: always uses ModelCatalog.kt fallback lists.
     * Copilot is CLI-only. Custom is Direct API-only with an empty model list.
     */
    fun buildCatalog(store: RunStore): List<AgentEntry> {
        val apiProviders = listOf("anthropic", "openai", "gemini")
        val entries = mutableListOf<AgentEntry>()

        for (provider in apiProviders) {
            val directModels = loadModelsFromDb(store, provider)
                ?: ModelFetcher.fallback(provider)
            val cliModels = ModelFetcher.fallback(provider)
            entries.add(AgentEntry(
                id = provider,
                displayName = AGENT_DISPLAY_NAMES[provider] ?: provider,
                directApiModels = directModels,
                cliModels = cliModels
            ))
        }

        // Copilot: CLI-only
        entries.add(AgentEntry(
            id = "copilot",
            displayName = "Copilot",
            directApiModels = null,
            cliModels = COPILOT_CLI_MODELS
        ))

        // Custom: Direct API only, empty model list (model set in Settings)
        entries.add(AgentEntry(
            id = "custom",
            displayName = "Custom API",
            directApiModels = emptyList(),
            cliModels = null
        ))

        return entries
    }

    private fun loadModelsFromDb(store: RunStore, provider: String): List<AgentModelInfo>? {
        val json = store.getSetting("models_${provider}_json") ?: return null
        return try {
            val models = ModelFetcher.parseModelsJson(json)
            models.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
