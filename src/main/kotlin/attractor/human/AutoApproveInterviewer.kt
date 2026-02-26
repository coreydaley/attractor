package attractor.human

/**
 * Always selects YES / first option. Used for automated pipelines with no human.
 */
class AutoApproveInterviewer : Interviewer {
    override fun ask(question: Question): Answer {
        return when (question.type) {
            QuestionType.YES_NO, QuestionType.CONFIRMATION ->
                Answer.yes()
            QuestionType.MULTIPLE_CHOICE ->
                if (question.options.isNotEmpty()) {
                    Answer.withOption(question.options.first())
                } else {
                    Answer(value = "auto-approved", text = "auto-approved")
                }
            QuestionType.FREEFORM ->
                Answer(value = "auto-approved", text = "auto-approved")
        }
    }

    override fun inform(message: String, stage: String) {
        // No-op for auto-approve
    }
}
