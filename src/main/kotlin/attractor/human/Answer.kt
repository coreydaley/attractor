package attractor.human

enum class AnswerValue {
    YES,
    NO,
    SKIPPED,
    TIMEOUT
}

data class Answer(
    val value: Any? = null, // String key or AnswerValue
    val selectedOption: Option? = null,
    val text: String = ""
) {
    val isYes: Boolean
        get() = value == AnswerValue.YES || value?.toString()?.lowercase() in setOf("y", "yes", "true")

    val isNo: Boolean
        get() = value == AnswerValue.NO || value?.toString()?.lowercase() in setOf("n", "no", "false")

    val isTimeout: Boolean get() = value == AnswerValue.TIMEOUT
    val isSkipped: Boolean get() = value == AnswerValue.SKIPPED

    val valueString: String
        get() = when (value) {
            is AnswerValue -> value.name.lowercase()
            null -> ""
            else -> value.toString()
        }

    companion object {
        fun yes() = Answer(AnswerValue.YES)
        fun no() = Answer(AnswerValue.NO)
        fun skipped() = Answer(AnswerValue.SKIPPED)
        fun timeout() = Answer(AnswerValue.TIMEOUT)
        fun withOption(option: Option) = Answer(value = option.key, selectedOption = option)
        fun freeform(text: String) = Answer(value = text, text = text)
    }
}
