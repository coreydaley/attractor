package attractor.llm.adapters

import attractor.llm.*
import attractor.llm.Request as LlmRequest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Adapter for OpenAI-compatible chat completions endpoints (Ollama, LM Studio, vLLM, etc.).
 * Uses the standard /v1/chat/completions format.
 */
class CustomApiAdapter(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val model: String = "llama3.2",
    timeoutSeconds: Long = 120L
) : ProviderAdapter {

    override val name = "custom"

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
        // No required key check — custom endpoints may be keyless (e.g., Ollama default)
    }

    override fun complete(request: LlmRequest): LlmResponse {
        val body = buildRequestBody(request, streaming = false)
        val httpRequest = buildHttpRequest(body)

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (e: IOException) {
            throw NetworkError("Failed to connect to custom API at $baseUrl: ${e.message}", e)
        }

        return response.use { resp ->
            val bodyStr = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw ProviderError("Custom API error (${resp.code}): $bodyStr", "custom", resp.code)
            }
            parseChatResponse(bodyStr)
        }
    }

    override fun stream(request: LlmRequest): Sequence<StreamEvent> {
        val body = buildRequestBody(request, streaming = true)
        val httpRequest = buildHttpRequest(body)

        return sequence {
            val call = client.newCall(httpRequest)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                yield(StreamEvent(StreamEventType.ERROR,
                    error = NetworkError("Failed to connect to custom API at $baseUrl: ${e.message}", e)))
                return@sequence
            }

            if (!response.isSuccessful) {
                val bodyStr = response.body?.string() ?: ""
                response.close()
                yield(StreamEvent(StreamEventType.ERROR,
                    error = ProviderError("Custom API stream error: $bodyStr", "custom", response.code)))
                return@sequence
            }

            yield(StreamEvent(StreamEventType.STREAM_START))

            val reader = response.body?.charStream()?.buffered()
                ?: run { response.close(); return@sequence }

            try {
                val fullText = StringBuilder()

                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data != "[DONE]" && data.isNotBlank()) {
                            try {
                                val evt = json.parseToJsonElement(data).jsonObject
                                val delta = evt["choices"]?.jsonArray
                                    ?.firstOrNull()?.jsonObject
                                    ?.get("delta")?.jsonObject
                                    ?.get("content")?.jsonPrimitive?.contentOrNull
                                if (delta != null) {
                                    fullText.append(delta)
                                    yield(StreamEvent(StreamEventType.TEXT_DELTA, delta = delta))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    line = reader.readLine()
                }

                val finalResponse = LlmResponse(
                    id = UUID.randomUUID().toString(),
                    model = model,
                    provider = "custom",
                    message = Message.assistant(fullText.toString()),
                    finishReason = FinishReason("stop", "stop"),
                    usage = Usage.empty()
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

    private fun buildRequestBody(request: LlmRequest, streaming: Boolean): String {
        return buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                for (msg in request.messages) {
                    addJsonObject {
                        put("role", when (msg.role) {
                            Role.SYSTEM    -> "system"
                            Role.ASSISTANT -> "assistant"
                            Role.USER      -> "user"
                            else           -> "user"
                        })
                        put("content", msg.text)
                    }
                }
            }
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            if (streaming) put("stream", true)
        }.toString()
    }

    private fun buildHttpRequest(body: String): okhttp3.Request {
        val builder = okhttp3.Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    private fun parseChatResponse(bodyStr: String): LlmResponse {
        val obj = try {
            json.parseToJsonElement(bodyStr).jsonObject
        } catch (e: Exception) {
            throw ProviderError("Failed to parse custom API response: $bodyStr", "custom")
        }

        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val content = obj["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull ?: ""

        return LlmResponse(
            id = id,
            model = model,
            provider = "custom",
            message = Message.assistant(content),
            finishReason = FinishReason("stop", "stop"),
            usage = Usage.empty()
        )
    }

    override fun close() { client.dispatcher.executorService.shutdown() }
}
