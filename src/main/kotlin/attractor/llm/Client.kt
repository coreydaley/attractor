package attractor.llm

import attractor.llm.adapters.AnthropicAdapter
import attractor.llm.adapters.GeminiAdapter
import attractor.llm.adapters.OpenAIAdapter
import attractor.llm.adapters.ProviderAdapter

typealias Middleware = (Request, (Request) -> LlmResponse) -> LlmResponse

class Client(
    private val providers: Map<String, ProviderAdapter>,
    private val defaultProvider: String? = null,
    private val middleware: List<Middleware> = emptyList()
) {
    private val effectiveDefault: String? = defaultProvider ?: providers.keys.firstOrNull()

    fun complete(request: Request): LlmResponse {
        val adapter = resolveAdapter(request)
        val chain = buildChain(adapter)
        return chain(request)
    }

    fun stream(request: Request): Sequence<StreamEvent> {
        val adapter = resolveAdapter(request)
        return adapter.stream(request)
    }

    private fun resolveAdapter(request: Request): ProviderAdapter {
        val providerName = request.provider ?: effectiveDefault
            ?: throw ConfigurationError("No provider specified and no default provider configured")
        return providers[providerName]
            ?: throw ConfigurationError("Provider '$providerName' not registered. Available: ${providers.keys}")
    }

    private fun buildChain(adapter: ProviderAdapter): (Request) -> LlmResponse {
        var chain: (Request) -> LlmResponse = { req -> adapter.complete(req) }
        // Apply middleware in reverse order so first registered runs first
        for (m in middleware.reversed()) {
            val next = chain
            chain = { req -> m(req, next) }
        }
        return chain
    }

    fun close() {
        providers.values.forEach { it.close() }
    }

    companion object {
        /**
         * Create a client from environment variables.
         * Only providers whose API keys are present are registered.
         */
        fun fromEnv(
            middleware: List<Middleware> = emptyList()
        ): Client {
            val providers = mutableMapOf<String, ProviderAdapter>()
            var firstProvider: String? = null

            // Anthropic
            val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
            if (anthropicKey.isNotBlank()) {
                providers["anthropic"] = AnthropicAdapter(apiKey = anthropicKey)
                if (firstProvider == null) firstProvider = "anthropic"
            }

            // OpenAI
            val openaiKey = System.getenv("OPENAI_API_KEY") ?: ""
            if (openaiKey.isNotBlank()) {
                providers["openai"] = OpenAIAdapter(apiKey = openaiKey)
                if (firstProvider == null) firstProvider = "openai"
            }

            // Gemini
            val geminiKey = System.getenv("GEMINI_API_KEY")
                ?: System.getenv("GOOGLE_API_KEY") ?: ""
            if (geminiKey.isNotBlank()) {
                providers["gemini"] = GeminiAdapter(apiKey = geminiKey)
                if (firstProvider == null) firstProvider = "gemini"
            }

            return Client(providers, firstProvider, middleware)
        }
    }
}

// ─── High-Level generate() function ─────────────────────────────────────────

data class GenerateResult(
    val text: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallData> = emptyList(),
    val finishReason: FinishReason,
    val usage: Usage,
    val totalUsage: Usage,
    val response: LlmResponse
)

private var _defaultClient: Client? = null

fun setDefaultClient(client: Client) { _defaultClient = client }

fun getDefaultClient(): Client = _defaultClient ?: Client.fromEnv().also { _defaultClient = it }

/**
 * High-level generate function (Section 4.3 of unified LLM spec).
 */
fun generate(
    model: String,
    prompt: String? = null,
    messages: List<Message>? = null,
    system: String? = null,
    tools: List<ToolDefinition>? = null,
    toolChoice: ToolChoice? = null,
    maxToolRounds: Int = 1,
    responseFormat: ResponseFormat? = null,
    temperature: Double? = null,
    topP: Double? = null,
    maxTokens: Int? = null,
    stopSequences: List<String>? = null,
    reasoningEffort: String? = null,
    provider: String? = null,
    providerOptions: Map<String, Any>? = null,
    maxRetries: Int = 2,
    client: Client? = null
): GenerateResult {
    require(prompt == null || messages == null) {
        "Provide either 'prompt' or 'messages', not both"
    }

    val effectiveClient = client ?: getDefaultClient()

    // Build message list
    val msgs = mutableListOf<Message>()
    if (system != null) msgs.add(Message.system(system))
    when {
        prompt != null -> msgs.add(Message.user(prompt))
        messages != null -> msgs.addAll(messages)
    }

    val request = Request(
        model = model,
        messages = msgs,
        provider = provider,
        tools = tools,
        toolChoice = toolChoice ?: if (tools != null) ToolChoice.AUTO else null,
        responseFormat = responseFormat,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        reasoningEffort = reasoningEffort,
        providerOptions = providerOptions
    )

    var totalUsage = Usage.empty()
    var lastResponse: LlmResponse? = null
    var conversation = msgs.toMutableList()

    // Tool execution loop
    for (round in 0..maxToolRounds) {
        val req = request.copy(messages = conversation.toList())

        val response = try {
            effectiveClient.complete(req)
        } catch (e: SdkError) {
            if (round < maxRetries) continue
            throw e
        }

        totalUsage = totalUsage + response.usage
        lastResponse = response

        val toolCallsInResponse = response.toolCalls
        val hasToolCalls = toolCallsInResponse.isNotEmpty() &&
                response.finishReason.reason == "tool_calls"

        // If no tool calls or no active tools, break the loop
        if (!hasToolCalls || tools.isNullOrEmpty()) break
        if (round >= maxToolRounds) break

        // Execute tools and add results to conversation
        val activeTool = tools.filter { it.execute != null }.associateBy { it.name }
        val toolResults = toolCallsInResponse.map { tc ->
            val tool = activeTool[tc.name]
            if (tool?.execute != null) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val args = when (val a = tc.arguments) {
                        is String -> try {
                            kotlinx.serialization.json.Json.parseToJsonElement(a)
                                .let { el ->
                                    if (el is kotlinx.serialization.json.JsonObject) {
                                        el.entries.associate { (k, v) ->
                                            k to (v as? kotlinx.serialization.json.JsonPrimitive)?.content as Any
                                        }
                                    } else emptyMap()
                                }
                        } catch (_: Exception) { emptyMap<String, Any>() }
                        is Map<*, *> -> a as Map<String, Any>
                        else -> emptyMap<String, Any>()
                    }
                    val result = tool.execute!!(args)
                    Message.toolResult(tc.id, result.toString())
                } catch (e: Exception) {
                    Message.toolResult(tc.id, "Error: ${e.message}", isError = true)
                }
            } else {
                Message.toolResult(tc.id, "Unknown tool: ${tc.name}", isError = true)
            }
        }

        conversation.add(response.message)
        conversation.addAll(toolResults)
    }

    val finalResponse = lastResponse ?: throw SdkError("No response received")
    return GenerateResult(
        text = finalResponse.text,
        reasoning = finalResponse.reasoning,
        toolCalls = finalResponse.toolCalls,
        finishReason = finalResponse.finishReason,
        usage = finalResponse.usage,
        totalUsage = totalUsage,
        response = finalResponse
    )
}
