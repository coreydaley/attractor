package attractor.state

import kotlinx.serialization.Serializable

@Serializable
enum class StageStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    RETRY,
    FAIL,
    SKIPPED;

    val isSuccess: Boolean get() = this == SUCCESS || this == PARTIAL_SUCCESS

    override fun toString(): String = name.lowercase()
}

@Serializable
data class Outcome(
    val status: StageStatus = StageStatus.SUCCESS,
    val preferredLabel: String = "",
    val suggestedNextIds: List<String> = emptyList(),
    val contextUpdates: Map<String, String> = emptyMap(),
    val notes: String = "",
    val failureReason: String = ""
) {
    companion object {
        fun success(notes: String = "", contextUpdates: Map<String, String> = emptyMap()) =
            Outcome(StageStatus.SUCCESS, notes = notes, contextUpdates = contextUpdates)

        fun fail(reason: String, notes: String = "") =
            Outcome(StageStatus.FAIL, failureReason = reason, notes = notes)

        fun retry(reason: String = "") =
            Outcome(StageStatus.RETRY, failureReason = reason)

        fun partial(notes: String = "", contextUpdates: Map<String, String> = emptyMap()) =
            Outcome(StageStatus.PARTIAL_SUCCESS, notes = notes, contextUpdates = contextUpdates)

        fun skipped(notes: String = "") =
            Outcome(StageStatus.SKIPPED, notes = notes)
    }

    fun withContextUpdate(key: String, value: String): Outcome =
        copy(contextUpdates = contextUpdates + (key to value))

    fun withPreferredLabel(label: String): Outcome = copy(preferredLabel = label)

    fun withSuggestedNext(vararg ids: String): Outcome = copy(suggestedNextIds = ids.toList())
}
