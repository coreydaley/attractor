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
 * Google Gemini native API adapter (Section 7 of the unified LLM spec).
 * Uses the native Gemini API at /v1beta/models/{model}/generateContent.
 */
class GeminiAdapter(
    apiKey: String = System.getenv("GEMINI_API_KEY") ?: System.getenv("GOOGLE_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("GEMINI_BASE_URL") ?: "https://generativelanguage.googleapis.com",
    private val defaultHeaders: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 120L
) : ProviderAdapter {

    override val name = "gemini"

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

    // Maps synthetic tool call IDs to function names
    private val toolCallIdToName = mutableMapOf<String, String>()

    override fun initialize() {
        if (effectiveApiKey.isBlank()) {
            throw ConfigurationError("Gemini API key is missing. Set GEMINI_API_KEY or GOOGLE_API_KEY environment variable.")
        }
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val body = buildRequestBody(request)
        val url = buildUrl(request.model, streaming = false)
        val httpRequest = buildHttpRequest(url, body)

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            throw NetworkError("Failed to connect to Gemini API: ${e.message}", e)
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
        val body = buildRequestBody(request)
        val url = buildUrl(request.model, streaming = true)
        val httpRequest = buildHttpRequest(url, body)

        return sequence {
            val call = client.newCall(httpRequest)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                yield(StreamEvent(StreamEventType.ERROR,
                    error = NetworkError("Failed to connect to Gemini API: ${e.message}", e)))
                return@sequence
            }

            if (!response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                response.close()
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ProviderError("Gemini stream error: $bodyStr", "gemini", response.code)))
                return@sequence
            }

            yield(StreamEvent(StreamEventType.STREAM_START))

            val reader = response.body?.charStream()?.buffered()
                ?: run { response.close(); return@sequence }

            try {
                val fullText = StringBuilder()
                var usage: Usage? = null

                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("data: {")) {
                        val jsonStr = if (trimmed.startsWith("data: ")) trimmed.removePrefix("data: ") else trimmed
                        try {
                            val chunkObj = json.parseToJsonElement(jsonStr).jsonObject
                            chunkObj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.let { cand ->
                                cand["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                                    val text = part.jsonObject["text"]?.jsonPrimitive?.content ?: ""
                                    fullText.append(text)
                                }
                            }
                            chunkObj["usageMetadata"]?.jsonObject?.let {
                                usage = parseUsage(it)
                            }
                        } catch (_: Exception) {}
                    }
                }

                val finalMsg = Message.assistant(fullText.toString())
                val finalResponse = LlmResponse(
                    id = UUID.randomUUID().toString(),
                    model = request.model,
                    provider = "gemini",
                    message = finalMsg,
                    finishReason = FinishReason("stop", "STOP"),
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

    private fun buildUrl(model: String, streaming: Boolean): String {
        val endpoint = if (streaming) "streamGenerateContent?alt=sse&key=$effectiveApiKey"
        else "generateContent?key=$effectiveApiKey"
        return "$baseUrl/v1beta/models/$model:$endpoint"
    }

    private fun buildRequestBody(request: LlmRequest): String {
        val obj = buildJsonObject {
            // System instruction
            val systemMessages = request.messages.filter { msg ->
                msg.role == Role.SYSTEM || msg.role == Role.DEVELOPER
            }
            if (systemMessages.isNotEmpty()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemMessages.joinToString("\n\n") { msg -> msg.text })
                        }
                    }
                }
            }

            // Contents
            val nonSystem = request.messages.filter { msg ->
                msg.role != Role.SYSTEM && msg.role != Role.DEVELOPER
            }
            putJsonArray("contents") {
                for (msg in nonSystem) {
                    addJsonObject {
                        put("role", mapRole(msg.role))
                        putJsonArray("parts") {
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
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            for (tool in request.tools) {
                                addJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    putJsonObject("parameters") {
                                        put("type", "object")
                                        putJsonObject("properties") {
                                            @Suppress("UNCHECKED_CAST")
                                            val props = tool.parameters["properties"] as? Map<String, Any>
                                            props?.entries?.forEach { entry -> put(entry.key, JsonPrimitive(entry.value.toString())) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Generation config
            putJsonObject("generationConfig") {
                request.temperature?.let { temp -> put("temperature", temp) }
                request.topP?.let { tp -> put("topP", tp) }
                request.maxTokens?.let { mt -> put("maxOutputTokens", mt) }

                // Response format
                if (request.responseFormat?.type == "json") {
                    put("responseMimeType", "application/json")
                }

                // Thinking config for Gemini 3 models
                request.reasoningEffort?.let { effort ->
                    val budget = when (effort) {
                        "low" -> 1024
                        "medium" -> 8192
                        "high" -> 32768
                        else -> 8192
                    }
                    putJsonObject("thinkingConfig") {
                        put("thinkingBudget", JsonPrimitive(budget))
                    }
                }
            }
        }
        return obj.toString()
    }

    private fun buildHttpRequest(url: String, body: String): okhttp3.Request {
        return okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .apply {
                defaultHeaders.entries.forEach { entry -> header(entry.key, entry.value) }
            }
            .build()
    }

    private fun parseResponse(bodyStr: String, request: LlmRequest): LlmResponse {
        val obj = try {
            json.parseToJsonElement(bodyStr).jsonObject
        } catch (e: Exception) {
            throw ProviderError("Failed to parse Gemini response: $bodyStr", "gemini")
        }

        val candidates = obj["candidates"]?.jsonArray ?: JsonArray(emptyList())
        val candidate = candidates.firstOrNull()?.jsonObject

        val contentParts = mutableListOf<ContentPart>()
        var finishReasonStr = "STOP"

        candidate?.let { cand ->
            finishReasonStr = cand["finishReason"]?.jsonPrimitive?.content ?: "STOP"
            cand["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                val partObj = part.jsonObject
                when {
                    partObj.containsKey("text") -> {
                        val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                        val text = partObj["text"]?.jsonPrimitive?.content ?: ""
                        if (isThought) {
                            contentParts.add(ContentPart.thinking(text))
                        } else {
                            contentParts.add(ContentPart.text(text))
                        }
                    }
                    partObj.containsKey("functionCall") -> {
                        val fc = partObj["functionCall"]!!.jsonObject
                        val name = fc["name"]?.jsonPrimitive?.content ?: ""
                        val argsJson = fc["args"]?.toString() ?: "{}"
                        val syntheticId = "call_${UUID.randomUUID().toString().replace("-", "").take(8)}"
                        toolCallIdToName[syntheticId] = name
                        contentParts.add(ContentPart.toolCall(syntheticId, name, argsJson))
                    }
                }
            }
        }

        val hasFunctionCall = contentParts.any { it.kind == ContentKind.TOOL_CALL }
        val unifiedFinish = mapFinishReason(finishReasonStr, hasFunctionCall)

        val usageObj = obj["usageMetadata"]?.jsonObject
        val usage = if (usageObj != null) parseUsage(usageObj) else Usage.empty()

        return LlmResponse(
            id = UUID.randomUUID().toString(),
            model = request.model,
            provider = "gemini",
            message = Message(Role.ASSISTANT, contentParts),
            finishReason = FinishReason(unifiedFinish, finishReasonStr),
            usage = usage,
            raw = mapOf("body" to bodyStr)
        )
    }

    private fun parseUsage(usageObj: JsonObject): Usage {
        val inputTokens = usageObj["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usageObj["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        val reasoningTokens = usageObj["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull
        val cachedTokens = usageObj["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull
        return Usage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            reasoningTokens = reasoningTokens,
            cacheReadTokens = cachedTokens
        )
    }

    private fun mapRole(role: Role): String = when (role) {
        Role.USER, Role.TOOL -> "user"
        Role.ASSISTANT -> "model"
        else -> "user"
    }

    private fun mapFinishReason(reason: String, hasFunctionCall: Boolean): String {
        if (hasFunctionCall) return "tool_calls"
        return when (reason.uppercase()) {
            "STOP" -> "stop"
            "MAX_TOKENS" -> "length"
            "SAFETY", "RECITATION" -> "content_filter"
            else -> "other"
        }
    }

    private fun translateContentPart(part: ContentPart, role: Role): JsonElement {
        return when (part.kind) {
            ContentKind.TEXT -> buildJsonObject {
                put("text", part.text ?: "")
            }
            ContentKind.IMAGE -> {
                val img = part.image!!
                if (img.url != null) {
                    buildJsonObject {
                        putJsonObject("fileData") {
                            put("mimeType", img.mediaType ?: "image/png")
                            put("fileUri", img.url)
                        }
                    }
                } else if (img.data != null) {
                    buildJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", img.mediaType ?: "image/png")
                            put("data", Base64.getEncoder().encodeToString(img.data))
                        }
                    }
                } else {
                    buildJsonObject { put("text", "") }
                }
            }
            ContentKind.TOOL_CALL -> {
                val tc = part.toolCall!!
                buildJsonObject {
                    putJsonObject("functionCall") {
                        put("name", tc.name)
                        put("args", try {
                            json.parseToJsonElement(tc.arguments.toString())
                        } catch (_: Exception) {
                            buildJsonObject {}
                        })
                    }
                }
            }
            ContentKind.TOOL_RESULT -> {
                val tr = part.toolResult!!
                // Gemini uses functionResponse in user-role messages
                val funcName = toolCallIdToName[tr.toolCallId] ?: tr.toolCallId
                buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", funcName)
                        putJsonObject("response") {
                            val content = tr.content
                            if (content is String) {
                                put("result", content)
                            } else {
                                put("result", content.toString())
                            }
                        }
                    }
                }
            }
            else -> buildJsonObject { put("text", part.text ?: "") }
        }
    }

    private fun handleHttpError(statusCode: Int, body: String): Nothing {
        val msg = try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body
        } catch (_: Exception) { body }

        throw when (statusCode) {
            401 -> AuthenticationError("gemini", msg)
            403 -> AccessDeniedError("gemini", msg)
            404 -> NotFoundError("gemini", msg)
            400, 422 -> InvalidRequestError("gemini", msg)
            429 -> RateLimitError("gemini", msg)
            413 -> ContextLengthError("gemini", msg)
            in 500..599 -> ServerError("gemini", msg, statusCode)
            else -> ProviderError(msg, "gemini", statusCode)
        }
    }

    override fun close() { client.dispatcher.executorService.shutdown() }
}
