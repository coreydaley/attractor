package attractor.llm

import attractor.db.RunStore
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class AgentModelInfo(val id: String, val displayName: String)

data class FetchResult(
    val models: List<AgentModelInfo>,
    val error: String? = null
)

object ModelFetcher {

    private const val TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_BYTES = 1_048_576 // 1 MB

    fun fetchModels(
        provider: String,
        store: RunStore,
        env: Map<String, String> = System.getenv()
    ): FetchResult {
        return try {
            val models = when (provider) {
                "anthropic" -> {
                    val key = env["ATTRACTOR_ANTHROPIC_API_KEY"] ?: ""
                    if (key.isBlank()) throw IllegalStateException("ATTRACTOR_ANTHROPIC_API_KEY not set")
                    fetchAnthropicModels(key)
                }
                "openai" -> {
                    val key = env["ATTRACTOR_OPENAI_API_KEY"] ?: ""
                    if (key.isBlank()) throw IllegalStateException("ATTRACTOR_OPENAI_API_KEY not set")
                    fetchOpenAIModels(key)
                }
                "gemini" -> {
                    val key = env["ATTRACTOR_GEMINI_API_KEY"] ?: env["ATTRACTOR_GOOGLE_API_KEY"] ?: ""
                    if (key.isBlank()) throw IllegalStateException("ATTRACTOR_GEMINI_API_KEY not set")
                    fetchGeminiModels(key)
                }
                else -> throw IllegalArgumentException("Unknown provider: $provider")
            }
            val json = modelsToJson(models)
            store.setSetting("models_${provider}_json", json)
            store.setSetting("models_${provider}_fetched_at", Instant.now().toString())
            FetchResult(models = models)
        } catch (e: Exception) {
            FetchResult(models = emptyList(), error = e.message ?: "Unknown error")
        }
    }

    private fun fetchAnthropicModels(apiKey: String): List<AgentModelInfo> {
        val body = httpGet("https://api.anthropic.com/v1/models", mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01"
        ))
        // Response: {"data":[{"id":"claude-opus-4-6","display_name":"Claude Opus 4","type":"model",...}],...}
        return parseAnthropicModels(body)
    }

    private fun fetchOpenAIModels(apiKey: String): List<AgentModelInfo> {
        val body = httpGet("https://api.openai.com/v1/models", mapOf(
            "Authorization" to "Bearer $apiKey"
        ))
        // Response: {"data":[{"id":"gpt-4o","object":"model",...}],...}
        return parseOpenAIModels(body)
    }

    private fun fetchGeminiModels(apiKey: String): List<AgentModelInfo> {
        val body = httpGet(
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey",
            emptyMap()
        )
        // Response: {"models":[{"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash",...}],...}
        return parseGeminiModels(body)
    }

    private fun httpGet(urlStr: String, headers: Map<String, String>): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream.use { it.readNBytes(MAX_RESPONSE_BYTES) }
        val body = String(bytes, Charsets.UTF_8)
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${body.take(200)}")
        return body
    }

    // ── Parsers (manual JSON — no new dependencies) ───────────────────────────

    private fun parseAnthropicModels(json: String): List<AgentModelInfo> {
        // Extract all "id" and "display_name" values from the data array
        val result = mutableListOf<AgentModelInfo>()
        val dataSection = extractJsonArray(json, "data") ?: return fallback("anthropic")
        forEachJsonObject(dataSection) { obj ->
            val id = extractJsonString(obj, "id") ?: return@forEachJsonObject
            if (!id.contains("claude")) return@forEachJsonObject
            val displayName = extractJsonString(obj, "display_name") ?: id
            result.add(AgentModelInfo(id, displayName))
        }
        return result.ifEmpty { fallback("anthropic") }
    }

    private fun parseOpenAIModels(json: String): List<AgentModelInfo> {
        val result = mutableListOf<AgentModelInfo>()
        val dataSection = extractJsonArray(json, "data") ?: return fallback("openai")
        val allowedPrefixes = listOf("gpt-", "o1", "o3", "o4")
        val blockedSubstrings = listOf("embed", "whisper", "tts", "dall-e", "realtime",
            "audio", "transcribe", "babbage", "davinci", "search", "instruct")
        forEachJsonObject(dataSection) { obj ->
            val id = extractJsonString(obj, "id") ?: return@forEachJsonObject
            if (allowedPrefixes.none { id.startsWith(it) }) return@forEachJsonObject
            if (blockedSubstrings.any { id.contains(it) }) return@forEachJsonObject
            result.add(AgentModelInfo(id, modelIdToDisplayName(id)))
        }
        return result.ifEmpty { fallback("openai") }
    }

    private fun parseGeminiModels(json: String): List<AgentModelInfo> {
        val result = mutableListOf<AgentModelInfo>()
        val dataSection = extractJsonArray(json, "models") ?: return fallback("gemini")
        forEachJsonObject(dataSection) { obj ->
            val name = extractJsonString(obj, "name") ?: return@forEachJsonObject
            val id = name.removePrefix("models/")
            if (!id.contains("gemini")) return@forEachJsonObject
            // Only include models that can generate content (not embedding models)
            val supportedMethods = extractJsonString(obj, "supportedGenerationMethods") ?: ""
            if (!supportedMethods.contains("generateContent") && supportedMethods.isNotBlank()) return@forEachJsonObject
            val displayName = extractJsonString(obj, "displayName") ?: modelIdToDisplayName(id)
            result.add(AgentModelInfo(id, displayName))
        }
        return result.ifEmpty { fallback("gemini") }
    }

    // ── Serialization helpers ────────────────────────────────────────────────

    fun modelsToJson(models: List<AgentModelInfo>): String =
        "[" + models.joinToString(",") { """{"id":${jsStr(it.id)},"displayName":${jsStr(it.displayName)}}""" } + "]"

    fun parseModelsJson(json: String): List<AgentModelInfo> {
        val result = mutableListOf<AgentModelInfo>()
        forEachJsonObject(json) { obj ->
            val id = extractJsonString(obj, "id") ?: return@forEachJsonObject
            val displayName = extractJsonString(obj, "displayName") ?: id
            result.add(AgentModelInfo(id, displayName))
        }
        return result
    }

    private fun jsStr(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    // ── Fallback model lists (from ModelCatalog) ─────────────────────────────

    fun fallback(provider: String): List<AgentModelInfo> =
        ModelCatalog.listModels(provider).map { AgentModelInfo(it.id, it.displayName) }

    // ── Minimal JSON extraction helpers ──────────────────────────────────────

    /** Extract the first array value for a given key in a JSON object. */
    private fun extractJsonArray(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*\[""")
        val m = pattern.find(json) ?: return null
        val start = m.range.last // points to '['
        var depth = 0
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
            i++
        }
        return null
    }

    /** Iterate over top-level JSON objects in an array string. */
    private fun forEachJsonObject(arrayStr: String, block: (String) -> Unit) {
        // Strip surrounding [ ]
        val inner = arrayStr.trimStart().let {
            if (it.startsWith("[")) it.substring(1, it.lastIndexOf(']')) else it
        }
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '{') {
                var depth = 0
                val start = i
                while (i < inner.length) {
                    when (inner[i]) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { block(inner.substring(start, i + 1)); i++; break } }
                    }
                    i++
                }
            } else {
                i++
            }
        }
    }

    /** Extract first string value for a given key in a JSON object string. */
    private fun extractJsonString(json: String, key: String): String? {
        // Match "key": "value" or "key":"value"
        val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return pattern.find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.replace("\\n", "\n")
    }

    private fun modelIdToDisplayName(id: String): String =
        id.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
