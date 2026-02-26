package attractor.human

/**
 * Delegates question answering to a provided callback function.
 */
class CallbackInterviewer(
    private val callback: (Question) -> Answer,
    private val informCallback: ((String, String) -> Unit)? = null
) : Interviewer {
    override fun ask(question: Question): Answer = callback(question)

    override fun inform(message: String, stage: String) {
        informCallback?.invoke(message, stage)
    }
}
