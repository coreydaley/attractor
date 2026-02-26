package attractor.llm

import kotlinx.serialization.Serializable

// ─── Core Data Types ─────────────────────────────────────────────────────────

enum class Role { SYSTEM, USER, ASSISTANT, TOOL, DEVELOPER }

enum class ContentKind {
    TEXT, IMAGE, AUDIO, DOCUMENT, TOOL_CALL, TOOL_RESULT, THINKING, REDACTED_THINKING
}

enum class FinishReasonType {
    STOP, LENGTH, TOOL_CALLS, CONTENT_FILTER, ERROR, OTHER
}

@Serializable
data class FinishReason(
    val reason: String, // unified: stop, length, tool_calls, content_filter, error, other
    val raw: String? = null
)

@Serializable
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
    val reasoningTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val raw: Map<String, String>? = null
) {
    operator fun plus(other: Usage): Usage = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        totalTokens = totalTokens + other.totalTokens,
        reasoningTokens = sumOptional(reasoningTokens, other.reasoningTokens),
        cacheReadTokens = sumOptional(cacheReadTokens, other.cacheReadTokens),
        cacheWriteTokens = sumOptional(cacheWriteTokens, other.cacheWriteTokens)
    )

    companion object {
        fun empty() = Usage(0, 0, 0)
        private fun sumOptional(a: Int?, b: Int?): Int? =
            if (a == null && b == null) null
            else (a ?: 0) + (b ?: 0)
    }
}

// ─── Content Parts ───────────────────────────────────────────────────────────

data class ImageData(
    val url: String? = null,
    val data: ByteArray? = null,
    val mediaType: String? = null,
    val detail: String? = null
)

data class AudioData(
    val url: String? = null,
    val data: ByteArray? = null,
    val mediaType: String? = null
)

data class DocumentData(
    val url: String? = null,
    val data: ByteArray? = null,
    val mediaType: String? = null,
    val fileName: String? = null
)

data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: Any, // Dict or String
    val type: String = "function"
)

data class ToolResultData(
    val toolCallId: String,
    val content: Any, // String or Dict
    val isError: Boolean = false,
    val imageData: ByteArray? = null,
    val imageMediaType: String? = null
)

data class ThinkingData(
    val text: String,
    val signature: String? = null,
    val redacted: Boolean = false
)

data class ContentPart(
    val kind: ContentKind,
    val text: String? = null,
    val image: ImageData? = null,
    val audio: AudioData? = null,
    val document: DocumentData? = null,
    val toolCall: ToolCallData? = null,
    val toolResult: ToolResultData? = null,
    val thinking: ThinkingData? = null
) {
    companion object {
        fun text(text: String) = ContentPart(ContentKind.TEXT, text = text)
        fun image(url: String? = null, data: ByteArray? = null, mediaType: String? = null) =
            ContentPart(ContentKind.IMAGE, image = ImageData(url, data, mediaType))
        fun toolCall(id: String, name: String, arguments: Any) =
            ContentPart(ContentKind.TOOL_CALL, toolCall = ToolCallData(id, name, arguments))
        fun toolResult(toolCallId: String, content: Any, isError: Boolean = false) =
            ContentPart(ContentKind.TOOL_RESULT, toolResult = ToolResultData(toolCallId, content, isError))
        fun thinking(text: String, signature: String? = null) =
            ContentPart(ContentKind.THINKING, thinking = ThinkingData(text, signature))
    }
}

// ─── Message ─────────────────────────────────────────────────────────────────

data class Message(
    val role: Role,
    val content: List<ContentPart>,
    val name: String? = null,
    val toolCallId: String? = null
) {
    val text: String
        get() = content.filter { it.kind == ContentKind.TEXT }.joinToString("") { it.text ?: "" }

    companion object {
        fun system(text: String) = Message(Role.SYSTEM, listOf(ContentPart.text(text)))
        fun user(text: String) = Message(Role.USER, listOf(ContentPart.text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentPart.text(text)))
        fun toolResult(toolCallId: String, content: String, isError: Boolean = false) =
            Message(
                Role.TOOL,
                listOf(ContentPart.toolResult(toolCallId, content, isError)),
                toolCallId = toolCallId
            )
    }
}

// ─── Tool Definition ─────────────────────────────────────────────────────────

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    val execute: ((Map<String, Any>) -> Any)? = null
)

data class ToolChoice(
    val mode: String, // auto, none, required, named
    val toolName: String? = null
) {
    companion object {
        val AUTO = ToolChoice("auto")
        val NONE = ToolChoice("none")
        val REQUIRED = ToolChoice("required")
        fun named(toolName: String) = ToolChoice("named", toolName)
    }
}

// ─── Request / Response ──────────────────────────────────────────────────────

data class ResponseFormat(
    val type: String = "text", // text, json, json_schema
    val jsonSchema: Map<String, Any>? = null,
    val strict: Boolean = false
)

data class Request(
    val model: String,
    val messages: List<Message>,
    val provider: String? = null,
    val tools: List<ToolDefinition>? = null,
    val toolChoice: ToolChoice? = null,
    val responseFormat: ResponseFormat? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String>? = null,
    val reasoningEffort: String? = null,
    val metadata: Map<String, String>? = null,
    val providerOptions: Map<String, Any>? = null
)

@Serializable
data class Warning(
    val message: String,
    val code: String? = null
)

@Serializable
data class RateLimitInfo(
    val requestsRemaining: Int? = null,
    val requestsLimit: Int? = null,
    val tokensRemaining: Int? = null,
    val tokensLimit: Int? = null,
    val resetAt: String? = null
)

data class LlmResponse(
    val id: String,
    val model: String,
    val provider: String,
    val message: Message,
    val finishReason: FinishReason,
    val usage: Usage,
    val raw: Map<String, Any>? = null,
    val warnings: List<Warning> = emptyList(),
    val rateLimit: RateLimitInfo? = null
) {
    val text: String get() = message.text

    val toolCalls: List<ToolCallData>
        get() = message.content
            .filter { it.kind == ContentKind.TOOL_CALL }
            .mapNotNull { it.toolCall }

    val reasoning: String?
        get() {
            val parts = message.content.filter { it.kind == ContentKind.THINKING }
            return if (parts.isEmpty()) null
            else parts.joinToString("") { it.thinking?.text ?: "" }
        }
}

// ─── Stream Events ────────────────────────────────────────────────────────────

enum class StreamEventType {
    STREAM_START, TEXT_START, TEXT_DELTA, TEXT_END,
    REASONING_START, REASONING_DELTA, REASONING_END,
    TOOL_CALL_START, TOOL_CALL_DELTA, TOOL_CALL_END,
    FINISH, ERROR, PROVIDER_EVENT
}

data class StreamEvent(
    val type: StreamEventType,
    val delta: String? = null,
    val textId: String? = null,
    val reasoningDelta: String? = null,
    val toolCall: ToolCallData? = null,
    val finishReason: FinishReason? = null,
    val usage: Usage? = null,
    val response: LlmResponse? = null,
    val error: SdkError? = null,
    val raw: Map<String, Any>? = null
)

// ─── Errors ───────────────────────────────────────────────────────────────────

open class SdkError(message: String, cause: Throwable? = null) : Exception(message, cause)

open class ProviderError(
    message: String,
    val provider: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val retryAfter: Double? = null,
    val raw: Map<String, Any>? = null,
    cause: Throwable? = null
) : SdkError(message, cause)

class AuthenticationError(provider: String, message: String) :
    ProviderError(message, provider, 401, retryable = false)

class AccessDeniedError(provider: String, message: String) :
    ProviderError(message, provider, 403, retryable = false)

class NotFoundError(provider: String, message: String) :
    ProviderError(message, provider, 404, retryable = false)

class InvalidRequestError(provider: String, message: String) :
    ProviderError(message, provider, 400, retryable = false)

class RateLimitError(provider: String, message: String, retryAfter: Double? = null) :
    ProviderError(message, provider, 429, retryable = true, retryAfter = retryAfter)

class ServerError(provider: String, message: String, statusCode: Int = 500) :
    ProviderError(message, provider, statusCode, retryable = true)

class ContentFilterError(provider: String, message: String) :
    ProviderError(message, provider, retryable = false)

class ContextLengthError(provider: String, message: String) :
    ProviderError(message, provider, 413, retryable = false)

class QuotaExceededError(provider: String, message: String) :
    ProviderError(message, provider, retryable = false)

class RequestTimeoutError(message: String = "Request timed out") : SdkError(message)
class AbortError(message: String = "Request aborted") : SdkError(message)
class NetworkError(message: String, cause: Throwable? = null) : SdkError(message, cause)
class StreamError(message: String, cause: Throwable? = null) : SdkError(message, cause)
class ConfigurationError(message: String) : SdkError(message)
class NoObjectGeneratedError(message: String) : SdkError(message)
