package attractor.human

/**
 * Reads answers from stdin. Displays formatted prompts with option keys.
 */
class ConsoleInterviewer : Interviewer {
    override fun ask(question: Question): Answer {
        println()
        println("[?] ${question.text}")
        if (question.stage.isNotEmpty()) {
            println("    Stage: ${question.stage}")
        }

        return when (question.type) {
            QuestionType.MULTIPLE_CHOICE -> {
                for (opt in question.options) {
                    println("  [${opt.key}] ${opt.label}")
                }
                print("Select: ")
                System.out.flush()
                val response = readLine()?.trim() ?: ""
                findMatchingOption(response, question.options)
            }
            QuestionType.YES_NO -> {
                print("[Y/N]: ")
                System.out.flush()
                val response = readLine()?.trim()?.lowercase() ?: ""
                if (response in setOf("y", "yes")) Answer.yes() else Answer.no()
            }
            QuestionType.CONFIRMATION -> {
                print("[Y/N] Confirm: ")
                System.out.flush()
                val response = readLine()?.trim()?.lowercase() ?: ""
                if (response in setOf("y", "yes")) Answer.yes() else Answer.no()
            }
            QuestionType.FREEFORM -> {
                print("> ")
                System.out.flush()
                val response = readLine()?.trim() ?: ""
                Answer.freeform(response)
            }
        }
    }

    private fun findMatchingOption(input: String, options: List<Option>): Answer {
        if (options.isEmpty()) return Answer.freeform(input)

        // Try matching by key (case-insensitive)
        val byKey = options.firstOrNull { it.key.lowercase() == input.lowercase() }
        if (byKey != null) return Answer.withOption(byKey)

        // Try matching by full label
        val byLabel = options.firstOrNull { it.label.lowercase().contains(input.lowercase()) }
        if (byLabel != null) return Answer.withOption(byLabel)

        // Default to first
        return Answer.withOption(options.first())
    }

    override fun inform(message: String, stage: String) {
        if (stage.isNotEmpty()) println("[INFO:$stage] $message")
        else println("[INFO] $message")
    }
}
