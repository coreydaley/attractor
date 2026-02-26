package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object ToolHandler : Handler {
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        val command = node.attrOrDefault("tool_command", "")
        if (command.isBlank()) {
            return Outcome.fail("No tool_command specified on node '${node.id}'")
        }

        val timeoutMs = node.timeoutMillis.coerceAtLeast(0L)
        val timeoutSec = if (timeoutMs > 0) timeoutMs / 1000 else 60L

        return try {
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val outputLines = mutableListOf<String>()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val completed = if (timeoutSec > 0) {
                // Read output in a thread while waiting
                val thread = Thread {
                    reader.lines().forEach { outputLines.add(it) }
                }
                thread.start()
                val done = process.waitFor(timeoutSec, TimeUnit.SECONDS)
                thread.join(5000)
                done
            } else {
                reader.lines().forEach { outputLines.add(it) }
                process.waitFor()
                true
            }

            if (!completed) {
                process.destroyForcibly()
                return Outcome.fail("Tool command timed out after ${timeoutSec}s: $command")
            }

            val exitCode = process.exitValue()
            val output = outputLines.joinToString("\n")

            if (exitCode != 0) {
                Outcome.fail(
                    reason = "Tool command exited with code $exitCode: $command",
                    notes = output
                )
            } else {
                Outcome(
                    status = attractor.state.StageStatus.SUCCESS,
                    contextUpdates = mapOf("tool.output" to output),
                    notes = "Tool completed: $command"
                )
            }
        } catch (e: Exception) {
            Outcome.fail("Tool execution error: ${e.message}")
        }
    }
}
