package attractor.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class CheckpointTest : FunSpec({

    lateinit var tmpDir: File

    beforeEach {
        tmpDir = Files.createTempDirectory("attractor-checkpoint-test").toFile()
    }

    afterEach {
        tmpDir.deleteRecursively()
    }

    test("save() + load() roundtrip preserves all fields") {
        val checkpoint = Checkpoint(
            currentNode = "node-b",
            completedNodes = listOf("node-a", "node-b"),
            nodeRetries = mapOf("node-a" to 2),
            contextValues = mapOf("key1" to "value1", "key2" to "42"),
            logs = listOf("log line 1", "log line 2"),
            stageDurations = mapOf("node-a" to 1500L, "node-b" to 3000L)
        )
        checkpoint.save(tmpDir.absolutePath)

        val loaded = Checkpoint.load(tmpDir.absolutePath)
        loaded.shouldNotBeNull()
        loaded.currentNode shouldBe "node-b"
        loaded.completedNodes shouldContainAll listOf("node-a", "node-b")
        loaded.nodeRetries["node-a"] shouldBe 2
        (loaded.contextValues["key1"] as? String) shouldBe "value1"
        loaded.stageDurations["node-a"] shouldBe 1500L
        loaded.stageDurations["node-b"] shouldBe 3000L
    }

    test("load() returns null when checkpoint.json does not exist") {
        val result = Checkpoint.load(tmpDir.absolutePath)
        result.shouldBeNull()
    }

    test("load() returns null when checkpoint.json contains invalid JSON") {
        val file = File(tmpDir, "checkpoint.json")
        file.writeText("this is not valid json { broken }")
        val result = Checkpoint.load(tmpDir.absolutePath)
        result.shouldBeNull()
    }

    test("create() extracts retry counts from Context keys prefixed with internal.retry_count.") {
        val ctx = Context()
        ctx.set("internal.retry_count.node-a", 3)
        ctx.set("internal.retry_count.node-b", 1)
        ctx.set("someOtherKey", "value")

        val checkpoint = Checkpoint.create(ctx, "node-b", listOf("node-a"))
        checkpoint.nodeRetries["node-a"] shouldBe 3
        checkpoint.nodeRetries["node-b"] shouldBe 1
        // non-retry keys should not be in nodeRetries
        (checkpoint.nodeRetries["someOtherKey"]) shouldBe null
    }

    test("create() captures all context values in the checkpoint") {
        val ctx = Context()
        ctx.set("output.result", "hello world")
        ctx.set("step_count", 5)

        val checkpoint = Checkpoint.create(ctx, "node-x", listOf("node-x"))
        checkpoint.contextValues.containsKey("output.result") shouldBe true
        checkpoint.contextValues.containsKey("step_count") shouldBe true
    }

    test("save() creates the logsRoot directory if it does not exist") {
        val nestedDir = File(tmpDir, "nested/deep/path")
        // directory does not exist yet
        nestedDir.exists() shouldBe false

        val checkpoint = Checkpoint(currentNode = "x", completedNodes = listOf("x"))
        checkpoint.save(nestedDir.absolutePath)

        nestedDir.exists() shouldBe true
        File(nestedDir, "checkpoint.json").exists() shouldBe true
    }

    test("saved checkpoint JSON is compact (no leading newlines at top level)") {
        val checkpoint = Checkpoint(
            currentNode = "node-a",
            completedNodes = listOf("node-a")
        )
        checkpoint.save(tmpDir.absolutePath)

        val text = File(tmpDir, "checkpoint.json").readText()
        // Compact JSON should not start with whitespace or newlines
        text.trimStart() shouldBe text
        // And should not contain a top-level newline after the opening brace
        text.shouldNotContain("\n{\n")
    }
})
