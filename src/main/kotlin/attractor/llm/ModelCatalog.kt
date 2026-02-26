package attractor.llm

data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String,
    val contextWindow: Int,
    val maxOutput: Int? = null,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    val aliases: List<String> = emptyList()
)

object ModelCatalog {
    val MODELS: List<ModelInfo> = listOf(
        // ── Anthropic ──────────────────────────────────────────────────────
        ModelInfo(
            id = "claude-opus-4-6",
            provider = "anthropic",
            displayName = "Claude Opus 4.6",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("claude-opus", "opus")
        ),
        ModelInfo(
            id = "claude-sonnet-4-5",
            provider = "anthropic",
            displayName = "Claude Sonnet 4.5",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("claude-sonnet", "sonnet")
        ),

        // ── OpenAI ─────────────────────────────────────────────────────────
        ModelInfo(
            id = "gpt-5.2",
            provider = "openai",
            displayName = "GPT-5.2",
            contextWindow = 1_047_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gpt5")
        ),
        ModelInfo(
            id = "gpt-5.2-mini",
            provider = "openai",
            displayName = "GPT-5.2 Mini",
            contextWindow = 1_047_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gpt5-mini")
        ),
        ModelInfo(
            id = "gpt-5.2-codex",
            provider = "openai",
            displayName = "GPT-5.2 Codex",
            contextWindow = 1_047_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("codex")
        ),

        // ── Gemini ─────────────────────────────────────────────────────────
        ModelInfo(
            id = "gemini-3-pro-preview",
            provider = "gemini",
            displayName = "Gemini 3 Pro (Preview)",
            contextWindow = 1_048_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gemini-pro")
        ),
        ModelInfo(
            id = "gemini-3-flash-preview",
            provider = "gemini",
            displayName = "Gemini 3 Flash (Preview)",
            contextWindow = 1_048_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gemini-flash")
        )
    )

    private val byId: Map<String, ModelInfo> = MODELS.associateBy { it.id }
    private val byAlias: Map<String, ModelInfo> = MODELS
        .flatMap { model -> model.aliases.map { alias -> alias to model } }
        .toMap()

    fun getModelInfo(modelId: String): ModelInfo? =
        byId[modelId] ?: byAlias[modelId]

    fun listModels(provider: String? = null): List<ModelInfo> =
        if (provider == null) MODELS
        else MODELS.filter { it.provider == provider }

    fun getLatestModel(provider: String, capability: String? = null): ModelInfo? {
        val models = listModels(provider)
        val filtered = when (capability) {
            "reasoning" -> models.filter { it.supportsReasoning }
            "vision"    -> models.filter { it.supportsVision }
            "tools"     -> models.filter { it.supportsTools }
            else        -> models
        }
        return filtered.firstOrNull() // First in list = newest (list is ordered)
    }
}
