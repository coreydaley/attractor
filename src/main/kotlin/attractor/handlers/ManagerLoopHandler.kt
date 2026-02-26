package attractor.handlers

import attractor.condition.ConditionEvaluator
import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus

class ManagerLoopHandler(
    private val childLauncher: ((dotfile: String, logsRoot: String) -> Process)? = null
) : Handler {

    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        val pollIntervalMs = node.attrs["manager.poll_interval"]?.asMillis() ?: 45_000L
        val maxCycles = node.attrLong("manager.max_cycles", 1000L).toInt()
        val stopCondition = node.attrOrDefault("manager.stop_condition", "")
        val actions = node.attrOrDefault("manager.actions", "observe,wait")
            .split(",").map { it.trim() }

        val steerCooldownMs = node.attrs["manager.steer_cooldown"]?.asMillis() ?: 300_000L
        var lastSteerTime = 0L

        // Auto-start child pipeline if configured (Section 4.11)
        val childAutostart = node.attrOrDefault("stack.child_autostart", "true") == "true"
        val childDotfile = graph.attr("stack.child_dotfile") ?: ""
        if (childAutostart && childDotfile.isNotBlank()) {
            startChildPipeline(childDotfile, logsRoot, context)
        }

        for (cycle in 1..maxCycles) {
            if ("observe" in actions) {
                ingestChildTelemetry(context)
            }

            val now = System.currentTimeMillis()
            if ("steer" in actions && (now - lastSteerTime) >= steerCooldownMs) {
                steerChild(context, node)
                lastSteerTime = now
            }

            // Evaluate stop conditions
            val childStatus = context.getString("context.stack.child.status")
            if (childStatus in setOf("completed", "failed")) {
                val childOutcome = context.getString("context.stack.child.outcome")
                return if (childOutcome == "success") {
                    Outcome.success(notes = "Child pipeline completed successfully")
                } else {
                    Outcome.fail("Child pipeline failed: $childOutcome")
                }
            }

            // Custom stop condition
            if (stopCondition.isNotBlank()) {
                val dummyOutcome = Outcome.success()
                if (ConditionEvaluator.evaluate(stopCondition, dummyOutcome, context)) {
                    return Outcome.success(notes = "Manager stop condition satisfied")
                }
            }

            if ("wait" in actions) {
                Thread.sleep(pollIntervalMs.coerceAtMost(5_000L)) // cap at 5s for tests
            }
        }

        return Outcome.fail("Manager loop exceeded max cycles ($maxCycles)")
    }

    private fun startChildPipeline(dotfile: String, logsRoot: String, context: Context) {
        try {
            val process = if (childLauncher != null) {
                childLauncher.invoke(dotfile, logsRoot)
            } else {
                // Auto-detect jar path from current JVM
                val jarPath = ManagerLoopHandler::class.java.protectionDomain
                    ?.codeSource?.location?.toURI()?.path ?: ""
                if (jarPath.endsWith(".jar")) {
                    val childLogsRoot = "$logsRoot/child-${System.currentTimeMillis()}"
                    ProcessBuilder("java", "-jar", jarPath, dotfile, "--auto-approve",
                        "--logs-root", childLogsRoot)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start()
                } else {
                    context.appendLog("Cannot determine jar path for child autostart; skipping")
                    return
                }
            }
            context.set("stack.child.pid", process.pid().toString())
            context.appendLog("Child pipeline started (PID ${process.pid()}): $dotfile")
        } catch (e: Exception) {
            context.appendLog("Failed to start child pipeline '$dotfile': ${e.message}")
        }
    }

    private fun ingestChildTelemetry(context: Context) {
        val activeStage = context.getString("stack.child.active_stage")
        if (activeStage.isNotEmpty()) {
            context.appendLog("Manager observed child at stage: $activeStage")
        }
    }

    private fun steerChild(context: Context, node: DotNode) {
        val steerPrompt = node.attrOrDefault("manager.steer_prompt", "")
        if (steerPrompt.isNotBlank()) {
            context.set("stack.manager.steer", steerPrompt)
            context.appendLog("Manager steering child with: $steerPrompt")
        }
    }
}
