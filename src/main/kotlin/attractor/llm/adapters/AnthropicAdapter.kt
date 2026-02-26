package attractor.llm.adapters

import attractor.llm.*
import attractor.llm.Request as LlmRequest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Anthropic Messages API adapter (Section 7 of the unified LLM spec).
 * Uses the native Messages API at /v1/messages.
 */
class AnthropicAdapter(
    apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com",
    private val defaultHeaders: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 120L
) : ProviderAdapter {

    override val name = "anthropic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val effectiveApiKey = apiKey

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun initialize() {
        if (effectiveApiKey.isBlank()) {
            throw ConfigurationError("Anthropic API key is missing. Set ANTHROPIC_API_KEY environment variable.")
        }
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val body = buildRequestBody(request)
        val httpRequest = buildHttpRequest(request, body)

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            throw NetworkError("Failed to connect to Anthropic API: ${e.message}", e)
        }

        return response.use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                handleHttpError(resp.code, bodyStr)
            }
            parseResponse(bodyStr, request)
        }
    }

    override fun stream(request: LlmRequest): Sequence<StreamEvent> {
        val body = buildRequestBody(request, streaming = true)
        val httpRequest = buildHttpRequest(request, body)

        return sequence {
            val call = client.newCall(httpRequest)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                yield(StreamEvent(StreamEventType.ERROR,
                    error = NetworkError("Failed to connect to Anthropic API: ${e.message}", e)))
                return@sequence
            }

            if (!response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                response.close()
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ProviderError("Anthropic stream error: $bodyStr", "anthropic", response.code)))
                return@sequence
            }

            yield(StreamEvent(StreamEventType.STREAM_START))

            val reader = response.body?.charStream()?.buffered()
                ?: run { response.close(); return@sequence }

            try {
                var fullText = StringBuilder()
                var fullThinking = StringBuilder()

                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data != "[DONE]" && data.isNotBlank()) {
                            try {
                                val evt = json.parseToJsonElement(data).jsonObject
                                val evtType = evt["type"]?.jsonPrimitive?.content ?: ""
                                when (evtType) {
                                    "content_block_delta" -> {
                                        val delta = evt["delta"]?.jsonObject
                                        val deltaType = delta?.get("type")?.jsonPrimitive?.content
                                        if (deltaType == "text_delta") {
                                            val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                            fullText.append(text)
                                            yield(StreamEvent(StreamEventType.TEXT_DELTA, delta = text))
                                        } else if (deltaType == "thinking_delta") {
                                            val thinking = delta["thinking"]?.jsonPrimitive?.content ?: ""
                                            fullThinking.append(thinking)
                                        }
                                    }
                                    "message_stop" -> { /* stream done */ }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    line = reader.readLine()
                }

                // Yield finish event
                val finalMsg = Message.assistant(fullText.toString())
                val finalResponse = LlmResponse(
                    id = UUID.randomUUID().toString(),
                    model = request.model,
                    provider = "anthropic",
                    message = finalMsg,
                    finishReason = FinishReason("stop", "end_turn"),
                    usage = Usage.empty()
                )
                yield(StreamEvent(StreamEventType.FINISH,
                    finishReason = FinishReason("stop", "end_turn"),
                    response = finalResponse))
            } finally {
                reader.close()
                response.close()
            }
        }
    }

    /** Recursively convert a Kotlin Any? value to a JsonElement for API serialization. */
    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toLong())
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJson(v) })
        is List<*> -> JsonArray(value.map { anyToJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun buildRequestBody(request: LlmRequest, streaming: Boolean = false): String {
        val obj = buildJsonObject {
            put("model", request.model)
            put("max_tokens", JsonPrimitive(request.maxTokens ?: 4096))

            // Extract system messages
            val systemMessages = request.messages.filter { msg -> msg.role == Role.SYSTEM || msg.role == Role.DEVELOPER }
            val nonSystemMessages = request.messages.filter { msg -> msg.role != Role.SYSTEM && msg.role != Role.DEVELOPER }

            if (systemMessages.isNotEmpty()) {
                val systemText = systemMessages.joinToString("\n\n") { msg -> msg.text }
                put("system", systemText)
            }

            // Build messages array with strict alternation
            putJsonArray("messages") {
                val merged = mergeConsecutiveSameRole(nonSystemMessages)
                for (msg in merged) {
                    addJsonObject {
                        put("role", mapRole(msg.role))
                        putJsonArray("content") {
                            for (part in msg.content) {
                                add(translateContentPart(part, msg.role))
                            }
                        }
                    }
                }
            }

            // Tools
            if (!request.tools.isNullOrEmpty()) {
                putJsonArray("tools") {
                    for (tool in request.tools) {
                        addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", anyToJson(tool.parameters))
                        }
                    }
                }
            }

            // Optional params
            request.temperature?.let { temp -> put("temperature", temp) }
            request.topP?.let { tp -> put("top_p", tp) }
            if (!request.stopSequences.isNullOrEmpty()) {
                putJsonArray("stop_sequences") {
                    request.stopSequences.forEach { seq -> add(seq) }
                }
            }

            if (streaming) put("stream", true)

            // Provider options (escape hatch)
            val anthropicOpts = request.providerOptions?.get("anthropic") as? Map<*, *>
            anthropicOpts?.get("thinking")?.let { thinking ->
                put("thinking", Json.parseToJsonElement(thinking.toString()))
            }
        }
        return obj.toString()
    }

    private fun buildHttpRequest(request: LlmRequest, body: String): okhttp3.Request {
        val providerOptions = request.providerOptions?.get("anthropic") as? Map<*, *>
        val betaHeaders = providerOptions?.get("beta_headers") as? List<*> ?: emptyList<Any>()
        val betaFeatures = providerOptions?.get("beta_features") as? List<*> ?: emptyList<Any>()
        val allBeta = (betaHeaders + betaFeatures).map { it.toString() }

        return okhttp3.Request.Builder()
            .url("$baseUrl/v1/messages")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("x-api-key", effectiveApiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .apply {
                if (allBeta.isNotEmpty()) header("anthropic-beta", allBeta.joinToString(","))
                defaultHeaders.entries.forEach { entry -> header(entry.key, entry.value) }
            }
            .build()
    }

    private fun parseResponse(bodyStr: String, request: LlmRequest): LlmResponse {
        val obj = try {
            json.parseToJsonElement(bodyStr).jsonObject
        } catch (e: Exception) {
            throw ProviderError("Failed to parse Anthropic response: $bodyStr", "anthropic")
        }

        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val model = obj["model"]?.jsonPrimitive?.content ?: request.model
        val stopReason = obj["stop_reason"]?.jsonPrimitive?.content ?: "end_turn"

        val contentParts = mutableListOf<ContentPart>()
        obj["content"]?.jsonArray?.forEach { item ->
            val itemObj = item.jsonObject
            when (itemObj["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    val text = itemObj["text"]?.jsonPrimitive?.content ?: ""
                    contentParts.add(ContentPart.text(text))
                }
                "tool_use" -> {
                    val toolId = itemObj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val toolName = itemObj["name"]?.jsonPrimitive?.content ?: ""
                    val input = itemObj["input"]?.toString() ?: "{}"
                    contentParts.add(ContentPart.toolCall(toolId, toolName, input))
                }
                "thinking" -> {
                    val thinking = itemObj["thinking"]?.jsonPrimitive?.content ?: ""
                    val sig = itemObj["signature"]?.jsonPrimitive?.content
                    contentParts.add(ContentPart.thinking(thinking, sig))
                }
                "redacted_thinking" -> {
                    val data = itemObj["data"]?.jsonPrimitive?.content ?: ""
                    contentParts.add(ContentPart(ContentKind.REDACTED_THINKING,
                        thinking = ThinkingData(data, redacted = true)))
                }
            }
        }

        val message = Message(Role.ASSISTANT, contentParts)

        val usageObj = obj["usage"]?.jsonObject
        val usage = Usage(
            inputTokens = usageObj?.get("input_tokens")?.jsonPrimitive?.int ?: 0,
            outputTokens = usageObj?.get("output_tokens")?.jsonPrimitive?.int ?: 0,
            totalTokens = (usageObj?.get("input_tokens")?.jsonPrimitive?.int ?: 0) +
                    (usageObj?.get("output_tokens")?.jsonPrimitive?.int ?: 0),
            cacheReadTokens = usageObj?.get("cache_read_input_tokens")?.jsonPrimitive?.intOrNull,
            cacheWriteTokens = usageObj?.get("cache_creation_input_tokens")?.jsonPrimitive?.intOrNull
        )

        val finishReason = mapStopReason(stopReason, contentParts)

        return LlmResponse(
            id = id,
            model = model,
            provider = "anthropic",
            message = message,
            finishReason = finishReason,
            usage = usage,
            raw = mapOf("body" to bodyStr)
        )
    }

    private fun mapRole(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.TOOL -> "user" // tool results go in user messages for Anthropic
        else -> "user"
    }

    private fun mapStopReason(stopReason: String, parts: List<ContentPart>): FinishReason {
        val hasTool = parts.any { it.kind == ContentKind.TOOL_CALL }
        val unified = when {
            hasTool || stopReason == "tool_use" -> "tool_calls"
            stopReason == "end_turn" || stopReason == "stop_sequence" -> "stop"
            stopReason == "max_tokens" -> "length"
            else -> "other"
        }
        return FinishReason(unified, stopReason)
    }

    private fun translateContentPart(part: ContentPart, role: Role): JsonElement {
        return when (part.kind) {
            ContentKind.TEXT -> buildJsonObject {
                put("type", "text")
                put("text", part.text ?: "")
            }
            ContentKind.IMAGE -> {
                val img = part.image!!
                buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        if (img.url != null) {
                            put("type", "url")
                            put("url", img.url)
                        } else if (img.data != null) {
                            put("type", "base64")
                            put("media_type", img.mediaType ?: "image/png")
                            put("data", Base64.getEncoder().encodeToString(img.data))
                        }
                    }
                }
            }
            ContentKind.TOOL_CALL -> {
                val tc = part.toolCall!!
                buildJsonObject {
                    put("type", "tool_use")
                    put("id", tc.id)
                    put("name", tc.name)
                    put("input", try {
                        json.parseToJsonElement(tc.arguments.toString())
                    } catch (_: Exception) {
                        buildJsonObject {}
                    })
                }
            }
            ContentKind.TOOL_RESULT -> {
                val tr = part.toolResult!!
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", tr.toolCallId)
                    put("content", tr.content.toString())
                    if (tr.isError) put("is_error", true)
                }
            }
            ContentKind.THINKING -> {
                val thinking = part.thinking!!
                buildJsonObject {
                    put("type", "thinking")
                    put("thinking", thinking.text)
                    thinking.signature?.let { put("signature", it) }
                }
            }
            ContentKind.REDACTED_THINKING -> {
                val thinking = part.thinking!!
                buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", thinking.text)
                }
            }
            else -> buildJsonObject { put("type", "text"); put("text", part.text ?: "") }
        }
    }

    private fun mergeConsecutiveSameRole(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val result = mutableListOf<Message>()
        var current = messages[0]
        for (i in 1 until messages.size) {
            val next = messages[i]
            if (next.role == current.role) {
                current = current.copy(content = current.content + next.content)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    private fun handleHttpError(statusCode: Int, body: String): Nothing {
        val msg = try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body
        } catch (_: Exception) { body }

        throw when (statusCode) {
            401 -> AuthenticationError("anthropic", msg)
            403 -> AccessDeniedError("anthropic", msg)
            404 -> NotFoundError("anthropic", msg)
            400, 422 -> InvalidRequestError("anthropic", msg)
            429 -> {
                val retryAfter = body.let {
                    try { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject
                        ?.get("retry_after")?.jsonPrimitive?.doubleOrNull } catch (_: Exception) { null }
                }
                RateLimitError("anthropic", msg, retryAfter)
            }
            413 -> ContextLengthError("anthropic", msg)
            in 500..599 -> ServerError("anthropic", msg, statusCode)
            else -> ProviderError(msg, "anthropic", statusCode)
        }
    }

    override fun close() { client.dispatcher.executorService.shutdown() }
}
