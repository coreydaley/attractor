package attractor.web

import attractor.events.ProjectEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import java.io.File
import java.nio.file.Files

class ProjectStateTest : FunSpec({

    test("recentLogs never exceeds 200 entries under burst logging") {
        val state = ProjectState()
        // Trigger 300 log entries via StageStarted events
        repeat(300) { i ->
            state.update(ProjectEvent.StageStarted("stage-$i", i, "node-$i"))
        }
        val size = state.recentLogs.toList().size
        size shouldBeLessThanOrEqual 200
    }

    test("recentLogsSize stays in sync with recentLogs after burst") {
        val state = ProjectState()
        repeat(250) { i ->
            state.update(ProjectEvent.StageStarted("stage-$i", i, "node-$i"))
        }
        val dequeSize = state.recentLogs.toList().size
        val counterSize = state.recentLogsSize.get()
        counterSize shouldBe dequeSize
    }

    test("toJson() does not hit filesystem — hasLog is false by default") {
        val state = ProjectState()
        state.update(ProjectEvent.ProjectStarted("test-pipeline", "run-1"))
        state.update(ProjectEvent.StageStarted("stage-a", 0, "node-a"))
        state.update(ProjectEvent.StageCompleted("stage-a", 0, 100L))

        val json = state.toJson()
        // logsRoot is blank so hasLog should be false — no filesystem access occurred
        json.contains("\"hasLog\":false") shouldBe true
    }

    test("hasLog is true on StageCompleted when log file exists") {
        val tmpDir = Files.createTempDirectory("attractor-test").toFile()
        try {
            val nodeId = "node-b"
            val logFile = File(tmpDir, "$nodeId/live.log")
            logFile.parentFile.mkdirs()
            logFile.writeText("some log content")

            val state = ProjectState()
            state.logsRoot = tmpDir.absolutePath
            state.update(ProjectEvent.ProjectStarted("test-pipeline", "run-2"))
            state.update(ProjectEvent.StageStarted("stage-b", 0, nodeId))
            state.update(ProjectEvent.StageCompleted("stage-b", 0, 200L))

            val json = state.toJson()
            json.contains("\"hasLog\":true") shouldBe true
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    test("hasLog is true on StageFailed when log file exists") {
        val tmpDir = Files.createTempDirectory("attractor-test").toFile()
        try {
            val nodeId = "node-c"
            val logFile = File(tmpDir, "$nodeId/live.log")
            logFile.parentFile.mkdirs()
            logFile.writeText("error output")

            val state = ProjectState()
            state.logsRoot = tmpDir.absolutePath
            state.update(ProjectEvent.ProjectStarted("test-pipeline", "run-3"))
            state.update(ProjectEvent.StageStarted("stage-c", 0, nodeId))
            state.update(ProjectEvent.StageFailed("stage-c", 0, "something went wrong"))

            val json = state.toJson()
            json.contains("\"hasLog\":true") shouldBe true
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    test("reset clears recentLogs and resets size counter") {
        val state = ProjectState()
        repeat(50) { i ->
            state.update(ProjectEvent.StageStarted("stage-$i", i, "node-$i"))
        }
        state.reset()
        state.recentLogs.toList().size shouldBe 0
        state.recentLogsSize.get() shouldBe 0
    }
})
