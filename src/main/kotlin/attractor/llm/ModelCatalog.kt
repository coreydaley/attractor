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
            id = "claude-sonnet-4-6",
            provider = "anthropic",
            displayName = "Claude Sonnet 4.6",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("claude-sonnet", "sonnet")
        ),
        ModelInfo(
            id = "claude-haiku-4-5-20251001",
            provider = "anthropic",
            displayName = "Claude Haiku 4.5",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("claude-haiku", "haiku")
        ),

        // ── OpenAI ─────────────────────────────────────────────────────────
        ModelInfo(
            id = "gpt-4o",
            provider = "openai",
            displayName = "GPT-4o",
            contextWindow = 128_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("gpt4o")
        ),
        ModelInfo(
            id = "gpt-4o-mini",
            provider = "openai",
            displayName = "GPT-4o Mini",
            contextWindow = 128_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("gpt4o-mini")
        ),
        ModelInfo(
            id = "gpt-4.1",
            provider = "openai",
            displayName = "GPT-4.1",
            contextWindow = 1_047_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("gpt41")
        ),
        ModelInfo(
            id = "gpt-4.1-mini",
            provider = "openai",
            displayName = "GPT-4.1 Mini",
            contextWindow = 1_047_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("gpt41-mini")
        ),
        ModelInfo(
            id = "o3",
            provider = "openai",
            displayName = "o3",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("openai-o3")
        ),
        ModelInfo(
            id = "o4-mini",
            provider = "openai",
            displayName = "o4 Mini",
            contextWindow = 200_000,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("openai-o4-mini")
        ),

        // ── Gemini ─────────────────────────────────────────────────────────
        ModelInfo(
            id = "gemini-2.5-pro",
            provider = "gemini",
            displayName = "Gemini 2.5 Pro",
            contextWindow = 1_048_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gemini-pro")
        ),
        ModelInfo(
            id = "gemini-2.5-flash",
            provider = "gemini",
            displayName = "Gemini 2.5 Flash",
            contextWindow = 1_048_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = true,
            aliases = listOf("gemini-flash")
        ),
        ModelInfo(
            id = "gemini-2.0-flash",
            provider = "gemini",
            displayName = "Gemini 2.0 Flash",
            contextWindow = 1_048_576,
            supportsTools = true,
            supportsVision = true,
            supportsReasoning = false,
            aliases = listOf("gemini-2-flash")
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
