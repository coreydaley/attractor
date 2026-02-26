package attractor.human

enum class QuestionType {
    YES_NO,
    MULTIPLE_CHOICE,
    FREEFORM,
    CONFIRMATION
}

data class Option(
    val key: String,
    val label: String
)

data class Question(
    val text: String,
    val type: QuestionType,
    val options: List<Option> = emptyList(),
    val default: Answer? = null,
    val timeoutSeconds: Double? = null,
    val stage: String = "",
    val metadata: Map<String, Any> = emptyMap()
)
