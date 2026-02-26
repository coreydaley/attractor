package attractor.human

import java.util.LinkedList
import java.util.Queue

/**
 * Reads answers from a pre-filled queue. Used for deterministic testing and replay.
 */
class QueueInterviewer(answers: List<Answer> = emptyList()) : Interviewer {
    private val queue: Queue<Answer> = LinkedList(answers)

    fun enqueue(answer: Answer) { queue.add(answer) }
    fun enqueueAll(answers: List<Answer>) { queue.addAll(answers) }

    override fun ask(question: Question): Answer =
        if (queue.isNotEmpty()) queue.poll()
        else Answer.skipped()
}
