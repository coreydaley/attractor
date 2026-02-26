package attractor.human

/**
 * Wraps another interviewer and records all Q&A pairs for replay, debug, audit.
 */
class RecordingInterviewer(private val inner: Interviewer) : Interviewer {
    data class Recording(val question: Question, val answer: Answer)

    private val _recordings = mutableListOf<Recording>()
    val recordings: List<Recording> get() = _recordings.toList()

    override fun ask(question: Question): Answer {
        val answer = inner.ask(question)
        _recordings.add(Recording(question, answer))
        return answer
    }

    override fun inform(message: String, stage: String) {
        inner.inform(message, stage)
    }

    fun clear() { _recordings.clear() }
}
