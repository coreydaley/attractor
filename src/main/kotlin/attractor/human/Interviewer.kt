package attractor.human

interface Interviewer {
    fun ask(question: Question): Answer
    fun askMultiple(questions: List<Question>): List<Answer> = questions.map { ask(it) }
    fun inform(message: String, stage: String = "") {}
}
