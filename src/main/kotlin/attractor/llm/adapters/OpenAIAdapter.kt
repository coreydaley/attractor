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
 * OpenAI Responses API adapter (Section 7 of the unified LLM spec).
 * Uses the native Responses API at /v1/responses.
 */
class OpenAIAdapter(
    apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com",
    private val orgId: String? = System.getenv("OPENAI_ORG_ID"),
    private val projectId: String? = System.getenv("OPENAI_PROJECT_ID"),
    private val defaultHeaders: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 120L
) : ProviderAdapter {

    override val name = "openai"

    private val effectiveApiKey = apiKey

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun initialize() {
        if (effectiveApiKey.isBlank()) {
            throw ConfigurationError("OpenAI API key is missing. Set OPENAI_API_KEY environment variable.")
        }
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val body = buildRequestBody(request)
        val httpRequest = buildHttpRequest(request, body)

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            throw NetworkError("Failed to connect to OpenAI API: ${e.message}", e)
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
                    error = NetworkError("Failed to connect to OpenAI API: ${e.message}", e)))
                return@sequence
            }

            if (!response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                response.close()
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ProviderError("OpenAI stream error: $bodyStr", "openai", response.code)))
                return@sequence
            }

            yield(StreamEvent(StreamEventType.STREAM_START))

            val reader = response.body?.charStream()?.buffered()
                ?: run { response.close(); return@sequence }

            try {
                val fullText = StringBuilder()
                var finishReason: FinishReason? = null
                var usage: Usage? = null

                reader.forEachLine { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") return@forEachLine
                        try {
                            val evt = json.parseToJsonElement(data).jsonObject
                            // Handle response events
                            val evtType = evt["type"]?.jsonPrimitive?.content
                            when (evtType) {
                                "response.output_text.delta" -> {
                                    val text = evt["delta"]?.jsonPrimitive?.content ?: ""
                                    fullText.append(text)
                                }
                                "response.completed" -> {
                                    val respObj = evt["response"]?.jsonObject
                                    val stopReason = respObj?.get("status")?.jsonPrimitive?.content ?: "stop"
                                    finishReason = FinishReason(mapFinishReason(stopReason), stopReason)
                                    val usageObj = respObj?.get("usage")?.jsonObject
                                    if (usageObj != null) {
                                        usage = parseUsage(usageObj)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                val finalMsg = Message.assistant(fullText.toString())
                val finalResponse = LlmResponse(
                    id = UUID.randomUUID().toString(),
                    model = request.model,
                    provider = "openai",
                    message = finalMsg,
                    finishReason = finishReason ?: FinishReason("stop", "completed"),
                    usage = usage ?: Usage.empty()
                )
                yield(StreamEvent(StreamEventType.FINISH,
                    finishReason = finalResponse.finishReason,
                    usage = finalResponse.usage,
                    response = finalResponse))
            } finally {
                reader.close()
                response.close()
            }
        }
    }

    private fun buildRequestBody(request: LlmRequest, streaming: Boolean = false): String {
        val obj = buildJsonObject {
            put("model", request.model)

            // Extract system/instructions from messages
            val systemMessages = request.messages.filter { msg ->
                msg.role == Role.SYSTEM || msg.role == Role.DEVELOPER
            }
            if (systemMessages.isNotEmpty()) {
                put("instructions", systemMessages.joinToString("\n\n") { msg -> msg.text })
            }

            // Build input array (Responses API format)
            putJsonArray("input") {
                val nonSystem = request.messages.filter { msg ->
                    msg.role != Role.SYSTEM && msg.role != Role.DEVELOPER
                }
                for (msg in nonSystem) {
                    when (msg.role) {
                        Role.TOOL -> {
                            // function_call_output items
                            for (part in msg.content) {
                                if (part.kind == ContentKind.TOOL_RESULT) {
                                    val tr = part.toolResult!!
                                    addJsonObject {
                                        put("type", "function_call_output")
                                        put("call_id", tr.toolCallId)
                                        put("output", tr.content.toString())
                                    }
                                }
                            }
                        }
                        else -> {
                            addJsonObject {
                                put("type", "message")
                                put("role", mapRole(msg.role))
                                putJsonArray("content") {
                                    for (part in msg.content) {
                                        add(translateContentPart(part, msg.role))
                                    }
                                }
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
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", Json.parseToJsonElement(
                                    kotlinx.serialization.json.Json.encodeToString(
                                        JsonObject.serializer(),
                                        buildJsonObject {
                                            put("type", "object")
                                            putJsonObject("properties") {
                                                @Suppress("UNCHECKED_CAST")
                                                val props = tool.parameters["properties"] as? Map<String, Any>
                                                props?.entries?.forEach { entry -> put(entry.key, JsonPrimitive(entry.value.toString())) }
                                            }
                                        }
                                    )
                                ))
                            }
                        }
                    }
                }
            }

            // Reasoning effort (for o-series models)
            request.reasoningEffort?.let { effort ->
                putJsonObject("reasoning") {
                    put("effort", effort)
                }
            }

            request.temperature?.let { temp -> put("temperature", temp) }
            request.topP?.let { tp -> put("top_p", tp) }
            request.maxTokens?.let { mt -> put("max_output_tokens", mt) }

            if (streaming) put("stream", true)
        }
        return obj.toString()
    }

    private fun buildHttpRequest(request: LlmRequest, body: String): okhttp3.Request {
        return okhttp3.Request.Builder()
            .url("$baseUrl/v1/responses")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $effectiveApiKey")
            .header("Content-Type", "application/json")
            .apply {
                orgId?.let { header("OpenAI-Organization", it) }
                projectId?.let { header("OpenAI-Project", it) }
                defaultHeaders.entries.forEach { entry -> header(entry.key, entry.value) }
            }
            .build()
    }

    private fun parseResponse(bodyStr: String, request: LlmRequest): LlmResponse {
        val obj = try {
            json.parseToJsonElement(bodyStr).jsonObject
        } catch (e: Exception) {
            throw ProviderError("Failed to parse OpenAI response: $bodyStr", "openai")
        }

        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val model = obj["model"]?.jsonPrimitive?.content ?: request.model
        val status = obj["status"]?.jsonPrimitive?.content ?: "completed"

        val contentParts = mutableListOf<ContentPart>()
        obj["output"]?.jsonArray?.forEach { item ->
            val itemObj = item.jsonObject
            val itemType = itemObj["type"]?.jsonPrimitive?.content
            when (itemType) {
                "message" -> {
                    itemObj["content"]?.jsonArray?.forEach { contentItem ->
                        val ci = contentItem.jsonObject
                        when (ci["type"]?.jsonPrimitive?.content) {
                            "output_text" -> contentParts.add(ContentPart.text(
                                ci["text"]?.jsonPrimitive?.content ?: ""))
                        }
                    }
                }
                "function_call" -> {
                    val callId = itemObj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val name = itemObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = itemObj["arguments"]?.jsonPrimitive?.content ?: "{}"
                    contentParts.add(ContentPart.toolCall(callId, name, args))
                }
            }
        }

        val usageObj = obj["usage"]?.jsonObject
        val usage = if (usageObj != null) parseUsage(usageObj) else Usage.empty()

        val finishReason = FinishReason(
            mapFinishReason(status),
            status
        )

        return LlmResponse(
            id = id,
            model = model,
            provider = "openai",
            message = Message(Role.ASSISTANT, contentParts),
            finishReason = finishReason,
            usage = usage,
            raw = mapOf("body" to bodyStr)
        )
    }

    private fun parseUsage(usageObj: JsonObject): Usage {
        val inputTokens = usageObj["input_tokens"]?.jsonPrimitive?.intOrNull
            ?: usageObj["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usageObj["output_tokens"]?.jsonPrimitive?.intOrNull
            ?: usageObj["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0

        val outputDetails = usageObj["output_tokens_details"]?.jsonObject
        val reasoningTokens = outputDetails?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull

        val inputDetails = usageObj["input_tokens_details"]?.jsonObject
        val cacheReadTokens = inputDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull

        return Usage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            reasoningTokens = reasoningTokens,
            cacheReadTokens = cacheReadTokens
        )
    }

    private fun mapRole(role: Role): String = when (role) {
        Role.USER -> "user"
        Role.ASSISTANT -> "assistant"
        Role.DEVELOPER -> "developer"
        else -> "user"
    }

    private fun mapFinishReason(reason: String): String = when (reason) {
        "stop", "completed", "end_turn" -> "stop"
        "length", "max_tokens" -> "length"
        "tool_calls", "function_call" -> "tool_calls"
        "content_filter" -> "content_filter"
        else -> "other"
    }

    private fun translateContentPart(part: ContentPart, role: Role): JsonElement {
        return when (part.kind) {
            ContentKind.TEXT -> {
                val partType = if (role == Role.ASSISTANT) "output_text" else "input_text"
                buildJsonObject {
                    put("type", partType)
                    put("text", part.text ?: "")
                }
            }
            ContentKind.IMAGE -> {
                val img = part.image!!
                buildJsonObject {
                    put("type", "input_image")
                    if (img.url != null) {
                        put("image_url", img.url)
                    } else if (img.data != null) {
                        val encoded = Base64.getEncoder().encodeToString(img.data)
                        put("image_url", "data:${img.mediaType ?: "image/png"};base64,$encoded")
                    }
                }
            }
            ContentKind.TOOL_CALL -> {
                val tc = part.toolCall!!
                buildJsonObject {
                    put("type", "function_call")
                    put("id", tc.id)
                    put("name", tc.name)
                    put("arguments", tc.arguments.toString())
                }
            }
            else -> buildJsonObject {
                put("type", "input_text")
                put("text", part.text ?: "")
            }
        }
    }

    private fun handleHttpError(statusCode: Int, body: String): Nothing {
        val msg = try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body
        } catch (_: Exception) { body }

        throw when (statusCode) {
            401 -> AuthenticationError("openai", msg)
            403 -> AccessDeniedError("openai", msg)
            404 -> NotFoundError("openai", msg)
            400, 422 -> InvalidRequestError("openai", msg)
            429 -> RateLimitError("openai", msg)
            413 -> ContextLengthError("openai", msg)
            in 500..599 -> ServerError("openai", msg, statusCode)
            else -> ProviderError(msg, "openai", statusCode)
        }
    }

    override fun close() { client.dispatcher.executorService.shutdown() }
}
