package attractor.dot

import kotlinx.serialization.Serializable

// ─── Value Types ─────────────────────────────────────────────────────────────

sealed class DotValue {
    data class StringValue(val value: String) : DotValue()
    data class IntegerValue(val value: Long) : DotValue()
    data class FloatValue(val value: Double) : DotValue()
    data class BooleanValue(val value: Boolean) : DotValue()
    /** Duration stored as milliseconds */
    data class DurationValue(val millis: Long, val original: String) : DotValue()

    fun asString(): String = when (this) {
        is StringValue -> value
        is IntegerValue -> value.toString()
        is FloatValue -> value.toString()
        is BooleanValue -> value.toString()
        is DurationValue -> original
    }

    fun asBoolean(): Boolean = when (this) {
        is BooleanValue -> value
        is StringValue -> value.lowercase() == "true"
        is IntegerValue -> value != 0L
        else -> false
    }

    fun asLong(): Long? = when (this) {
        is IntegerValue -> value
        is FloatValue -> value.toLong()
        is StringValue -> value.toLongOrNull()
        else -> null
    }

    fun asMillis(): Long = when (this) {
        is DurationValue -> millis
        is IntegerValue -> value
        else -> 0L
    }
}

// ─── Graph Model ─────────────────────────────────────────────────────────────

data class DotNode(
    val id: String,
    val attrs: MutableMap<String, DotValue> = mutableMapOf()
) {
    fun attr(key: String): String? = attrs[key]?.asString()
    fun attrOrDefault(key: String, default: String): String = attrs[key]?.asString() ?: default
    fun attrBool(key: String, default: Boolean = false): Boolean =
        attrs[key]?.asBoolean() ?: default
    fun attrLong(key: String, default: Long = 0L): Long =
        attrs[key]?.asLong() ?: default

    val label: String get() = attrOrDefault("label", id)
    val shape: String get() = attrOrDefault("shape", "box")
    val type: String get() = attrOrDefault("type", "")
    val prompt: String get() = attrOrDefault("prompt", "")
    val maxRetries: Int get() = attrLong("max_retries", 0L).toInt()
    val goalGate: Boolean get() = attrBool("goal_gate")
    val retryTarget: String get() = attrOrDefault("retry_target", "")
    val fallbackRetryTarget: String get() = attrOrDefault("fallback_retry_target", "")
    val fidelity: String get() = attrOrDefault("fidelity", "")
    val threadId: String get() = attrOrDefault("thread_id", "")
    val cssClass: String get() = attrOrDefault("class", "")
    val timeoutMillis: Long get() = attrs["timeout"]?.asMillis() ?: 0L
    val llmModel: String get() = attrOrDefault("llm_model", "")
    val llmProvider: String get() = attrOrDefault("llm_provider", "")
    val reasoningEffort: String get() = attrOrDefault("reasoning_effort", "high")
    val autoStatus: Boolean get() = attrBool("auto_status")
    val allowPartial: Boolean get() = attrBool("allow_partial")

    fun isStart(): Boolean = shape == "Mdiamond" || id.lowercase() == "start"
    fun isExit(): Boolean = shape == "Msquare" || id.lowercase() in setOf("exit", "end")
    fun isTerminal(): Boolean = isExit()
}

data class DotEdge(
    val from: String,
    val to: String,
    val attrs: MutableMap<String, DotValue> = mutableMapOf()
) {
    fun attr(key: String): String? = attrs[key]?.asString()
    fun attrOrDefault(key: String, default: String): String = attrs[key]?.asString() ?: default
    fun attrBool(key: String, default: Boolean = false): Boolean =
        attrs[key]?.asBoolean() ?: default
    fun attrLong(key: String, default: Long = 0L): Long =
        attrs[key]?.asLong() ?: default

    val label: String get() = attrOrDefault("label", "")
    val condition: String get() = attrOrDefault("condition", "")
    val weight: Int get() = attrLong("weight", 0L).toInt()
    val fidelity: String get() = attrOrDefault("fidelity", "")
    val threadId: String get() = attrOrDefault("thread_id", "")
    val loopRestart: Boolean get() = attrBool("loop_restart")
}

data class DotGraph(
    val id: String,
    val nodes: MutableMap<String, DotNode> = mutableMapOf(),
    val edges: MutableList<DotEdge> = mutableListOf(),
    val attrs: MutableMap<String, DotValue> = mutableMapOf()
) {
    fun attr(key: String): String? = attrs[key]?.asString()
    fun attrOrDefault(key: String, default: String): String = attrs[key]?.asString() ?: default

    val goal: String get() = attrOrDefault("goal", "")
    val label: String get() = attrOrDefault("label", "")
    val modelStylesheet: String get() = attrOrDefault("model_stylesheet", "")
    val defaultMaxRetry: Int get() = attrs["default_max_retry"]?.asLong()?.toInt() ?: 50
    val retryTarget: String get() = attrOrDefault("retry_target", "")
    val fallbackRetryTarget: String get() = attrOrDefault("fallback_retry_target", "")
    val defaultFidelity: String get() = attrOrDefault("default_fidelity", "compact")

    fun outgoingEdges(nodeId: String): List<DotEdge> =
        edges.filter { it.from == nodeId }

    fun incomingEdges(nodeId: String): List<DotEdge> =
        edges.filter { it.to == nodeId }

    fun startNode(): DotNode? =
        nodes.values.firstOrNull { it.isStart() }

    fun exitNode(): DotNode? =
        nodes.values.firstOrNull { it.isExit() }

    fun clone(): DotGraph {
        val cloned = DotGraph(
            id = id,
            attrs = attrs.toMutableMap()
        )
        nodes.forEach { (k, v) -> cloned.nodes[k] = v.copy(attrs = v.attrs.toMutableMap()) }
        edges.forEach { cloned.edges.add(it.copy(attrs = it.attrs.toMutableMap())) }
        return cloned
    }
}
