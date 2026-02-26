package attractor.llm.adapters

import attractor.llm.LlmResponse
import attractor.llm.Request
import attractor.llm.StreamEvent

interface ProviderAdapter {
    val name: String

    /** Blocking call. Blocks until the model finishes, returns full response. */
    fun complete(request: Request): LlmResponse

    /** Streaming call. Returns a sequence of stream events. */
    fun stream(request: Request): Sequence<StreamEvent>

    /** Release resources. */
    fun close() {}

    /** Validate configuration on startup. */
    fun initialize() {}

    /** Query whether a particular tool choice mode is supported. */
    fun supportsToolChoice(mode: String): Boolean = mode in setOf("auto", "none", "required")
}
