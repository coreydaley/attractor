package attractor.engine

import attractor.dot.DotGraph
import attractor.events.PipelineEvent
import attractor.events.PipelineEventBus
import attractor.handlers.HandlerRegistry
import attractor.lint.Validator
import attractor.state.Checkpoint
import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus
import attractor.transform.StylesheetApplicationTransform
import attractor.transform.Transform
import attractor.transform.VariableExpansionTransform
import java.io.File
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class EngineConfig(
    val logsRoot: String = "logs",
    val autoApprove: Boolean = false,
    val resume: Boolean = false,
    val transforms: List<Transform> = listOf(
        VariableExpansionTransform(),
        StylesheetApplicationTransform()
    ),
    val cancelCheck: () -> Boolean = { false },
    val pauseCheck: () -> Boolean = { false }
)

class Engine(
    private val registry: HandlerRegistry,
    private val config: EngineConfig = EngineConfig(),
    private val eventBus: PipelineEventBus = PipelineEventBus()
) {
    private val startTime = System.currentTimeMillis()

    private class PausedException : RuntimeException("Pipeline paused")

    fun subscribe(observer: attractor.events.PipelineEventObserver) = eventBus.subscribe(observer)

    /**
     * Prepare the graph: transforms -> validate.
     */
    fun prepare(graph: DotGraph): DotGraph {
        var g = graph
        for (transform in config.transforms) {
            g = transform.apply(g)
        }
        Validator.validateOrRaise(g)
        return g
    }

    /**
     * Run from the graph's designated start node.
     */
    fun run(graph: DotGraph): Outcome {
        val startNode = graph.startNode()
            ?: return Outcome.fail("No start node found in graph").also {
                eventBus.emit(PipelineEvent.PipelineFailed("No start node", elapsed()))
            }
        return runLoop(graph, startNode.id)
    }

    /**
     * Run from a specific node (used for loop_restart and resume).
     */
    fun runFrom(graph: DotGraph, startNodeId: String): Outcome = runLoop(graph, startNodeId)

    /**
     * Core execution loop (Section 3.2).
     */
    private fun runLoop(graph: DotGraph, startNodeId: String): Outcome {
        val runId = "run-${System.currentTimeMillis()}"
        val graphName = graph.id

        File(config.logsRoot).mkdirs()
        eventBus.emit(PipelineEvent.PipelineStarted(graphName, runId))

        val context = Context()
        mirrorGraphAttributes(graph, context)

        val completedNodes = mutableListOf<String>()
        val nodeOutcomes = mutableMapOf<String, Outcome>()
        val nodeDurations = mutableMapOf<String, Long>()

        var currentNodeId = startNodeId
        var stageIndex = 0
        var lastOutcome = Outcome.success()

        // Checkpoint resume (Section 3.1): restore state if resuming
        if (config.resume) {
            val checkpoint = Checkpoint.load(config.logsRoot)
            if (checkpoint != null) {
                context.applyAnyUpdates(checkpoint.contextValues)
                checkpoint.logs.forEach { context.appendLog(it) }
                completedNodes.addAll(checkpoint.completedNodes)
                currentNodeId = checkpoint.currentNode
                context.appendLog("Resumed from checkpoint at node: ${checkpoint.currentNode}")
                println("[attractor] Resumed from checkpoint at node: ${checkpoint.currentNode}")
            }
        }

        writeManifest(graph, runId)

        try {
            while (true) {
                if (config.cancelCheck() || Thread.currentThread().isInterrupted) {
                    eventBus.emit(PipelineEvent.PipelineCancelled(elapsed()))
                    return Outcome.fail("Pipeline cancelled")
                }

                if (config.pauseCheck()) {
                    eventBus.emit(PipelineEvent.PipelinePaused(elapsed()))
                    return Outcome.fail("Pipeline paused")
                }

                val node = graph.nodes[currentNodeId]
                    ?: return Outcome.fail("Node '$currentNodeId' not found in graph").also {
                        eventBus.emit(PipelineEvent.PipelineFailed("Node not found: $currentNodeId", elapsed()))
                    }

                context.set("current_node", currentNodeId)

                // Step 1: Check for terminal node
                if (node.isTerminal()) {
                    eventBus.emit(PipelineEvent.StageStarted(node.label, stageIndex, nodeId = node.id))
                    val terminalStart = System.currentTimeMillis()
                    val exitOutcome = executeNodeWithRetry(node, context, graph, stageIndex)
                    val terminalDuration = System.currentTimeMillis() - terminalStart
                    lastOutcome = exitOutcome

                    when (exitOutcome.status) {
                        StageStatus.SUCCESS, StageStatus.PARTIAL_SUCCESS -> {
                            completedNodes.add(node.id)
                            nodeDurations[node.id] = terminalDuration
                            eventBus.emit(PipelineEvent.StageCompleted(node.label, stageIndex, terminalDuration))
                            val finalCheckpoint = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
                            finalCheckpoint.save(config.logsRoot)
                        }
                        StageStatus.FAIL ->
                            eventBus.emit(PipelineEvent.StageFailed(node.label, stageIndex, exitOutcome.failureReason))
                        else -> {}
                    }

                    // Goal gate enforcement (Section 3.4)
                    val (gateOk, failedGate) = checkGoalGates(graph, nodeOutcomes)
                    if (!gateOk && failedGate != null) {
                        val retryTarget = getRetryTarget(failedGate, graph)
                        if (retryTarget != null && retryTarget in graph.nodes) {
                            context.appendLog("Goal gate '${failedGate.id}' unsatisfied, jumping to $retryTarget")
                            currentNodeId = retryTarget
                            continue
                        } else {
                            val errMsg = "Goal gate '${failedGate.id}' unsatisfied and no retry target"
                            eventBus.emit(PipelineEvent.PipelineFailed(errMsg, elapsed()))
                            return Outcome.fail(errMsg)
                        }
                    }
                    break
                }

                // Step 2: Execute node handler with retry policy
                eventBus.emit(PipelineEvent.StageStarted(node.label, stageIndex, nodeId = node.id))
                val nodeStart = System.currentTimeMillis()

                val outcome = executeNodeWithRetry(node, context, graph, stageIndex)
                lastOutcome = outcome

                val nodeDuration = System.currentTimeMillis() - nodeStart

                // Step 3: Record completion
                completedNodes.add(node.id)
                nodeOutcomes[node.id] = outcome
                nodeDurations[node.id] = nodeDuration

                when (outcome.status) {
                    StageStatus.SUCCESS, StageStatus.PARTIAL_SUCCESS ->
                        eventBus.emit(PipelineEvent.StageCompleted(node.label, stageIndex, nodeDuration))
                    StageStatus.FAIL ->
                        eventBus.emit(PipelineEvent.StageFailed(node.label, stageIndex, outcome.failureReason))
                    else -> {}
                }

                // Step 4: Apply context updates
                context.applyUpdates(outcome.contextUpdates)
                context.set("outcome", outcome.status.toString())
                if (outcome.preferredLabel.isNotBlank()) {
                    context.set("preferred_label", outcome.preferredLabel)
                }

                // Step 5: Save checkpoint
                val checkpoint = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
                checkpoint.save(config.logsRoot)
                eventBus.emit(PipelineEvent.CheckpointSaved(node.id))

                // Step 6: Select next edge
                val nextEdge = EdgeSelector.select(node, outcome, context, graph)

                if (nextEdge == null) {
                    if (outcome.status == StageStatus.FAIL) {
                        // Failure routing (Section 3.7)
                        val retryTarget = node.retryTarget.takeIf { it.isNotBlank() }
                            ?: node.fallbackRetryTarget.takeIf { it.isNotBlank() }
                        if (retryTarget != null && retryTarget in graph.nodes) {
                            context.appendLog("Failure routing: jumping to $retryTarget")
                            currentNodeId = retryTarget
                            stageIndex++
                            continue
                        }
                        eventBus.emit(PipelineEvent.PipelineFailed(outcome.failureReason, elapsed()))
                        return outcome
                    }
                    break
                }

                // Step 7: Handle loop_restart — terminate this run and re-launch fresh (Section 2.7)
                if (nextEdge.loopRestart) {
                    val restartLogsRoot = "${config.logsRoot}-restart-${System.currentTimeMillis()}"
                    context.appendLog("Loop restart triggered, fresh run from ${nextEdge.to} in $restartLogsRoot")
                    println("[attractor] Loop restart: launching fresh run from '${nextEdge.to}' in $restartLogsRoot")
                    val restartConfig = config.copy(logsRoot = restartLogsRoot, resume = false)
                    val freshEngine = Engine(registry, restartConfig, eventBus)
                    return freshEngine.runFrom(graph, nextEdge.to)
                }

                // Step 8: Advance to next node
                currentNodeId = nextEdge.to
                stageIndex++
            }

            eventBus.emit(PipelineEvent.PipelineCompleted(elapsed()))
            return lastOutcome

        } catch (e: PausedException) {
            eventBus.emit(PipelineEvent.PipelinePaused(elapsed()))
            return Outcome.fail("Pipeline paused")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            eventBus.emit(PipelineEvent.PipelineCancelled(elapsed()))
            return Outcome.fail("Pipeline cancelled")
        } catch (e: Exception) {
            val errMsg = "Pipeline crashed: ${e.message}"
            eventBus.emit(PipelineEvent.PipelineFailed(errMsg, elapsed()))
            return Outcome.fail(errMsg)
        }
    }

    private fun executeNodeWithRetry(
        node: attractor.dot.DotNode,
        context: Context,
        graph: DotGraph,
        stageIndex: Int
    ): Outcome {
        val retryPolicy = RetryPolicy.fromNode(node, graph.defaultMaxRetry)
        val maxAttempts = retryPolicy.maxAttempts

        for (attempt in 1..maxAttempts) {
            val outcome = try {
                val handler = registry.resolve(node)

                // Timeout enforcement (Section 2.6)
                val raw: Outcome = if (node.timeoutMillis > 0) {
                    val future = CompletableFuture.supplyAsync {
                        handler.execute(node, context, graph, config.logsRoot)
                    }
                    try {
                        future.get(node.timeoutMillis, TimeUnit.MILLISECONDS)
                    } catch (e: TimeoutException) {
                        future.cancel(true)
                        return Outcome.fail("Node '${node.id}' timed out after ${node.timeoutMillis}ms")
                    }
                } else {
                    handler.execute(node, context, graph, config.logsRoot)
                }

                // auto_status: if no status.json was written by the handler, auto-generate SUCCESS (Section 2.6)
                if (node.autoStatus) {
                    val statusFile = File(config.logsRoot, "${node.id}/status.json")
                    if (!statusFile.exists() || raw.status == StageStatus.SKIPPED) {
                        return Outcome.success(notes = "auto_status: generated SUCCESS for '${node.id}'")
                    }
                }

                raw
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                if (attempt < maxAttempts) {
                    val delay = retryPolicy.backoff.delayForAttempt(attempt)
                    eventBus.emit(PipelineEvent.StageRetrying(node.label, stageIndex, attempt, delay))
                    Thread.sleep(delay)
                    if (config.pauseCheck()) throw PausedException()
                    continue
                } else {
                    return Outcome.fail(e.message ?: "Unknown exception in handler")
                }
            }

            when (outcome.status) {
                StageStatus.SUCCESS, StageStatus.PARTIAL_SUCCESS -> {
                    context.set("internal.retry_count.${node.id}", 0)
                    return outcome
                }
                StageStatus.RETRY -> {
                    if (attempt < maxAttempts) {
                        val delay = retryPolicy.backoff.delayForAttempt(attempt)
                        eventBus.emit(PipelineEvent.StageRetrying(node.label, stageIndex, attempt, delay))
                        context.incrementInt("internal.retry_count.${node.id}")
                        Thread.sleep(delay)
                        if (config.pauseCheck()) throw PausedException()
                        continue
                    } else {
                        return if (node.allowPartial) {
                            Outcome(StageStatus.PARTIAL_SUCCESS, notes = "Retries exhausted, partial accepted")
                        } else {
                            Outcome.fail("Max retries exceeded for '${node.id}'")
                        }
                    }
                }
                StageStatus.FAIL -> return outcome
                StageStatus.SKIPPED -> return outcome
            }
        }
        return Outcome.fail("Max retries exceeded for '${node.id}'")
    }

    private fun checkGoalGates(
        graph: DotGraph,
        nodeOutcomes: Map<String, Outcome>
    ): Pair<Boolean, attractor.dot.DotNode?> {
        for ((nodeId, outcome) in nodeOutcomes) {
            val node = graph.nodes[nodeId] ?: continue
            if (node.goalGate && !outcome.status.isSuccess) {
                return Pair(false, node)
            }
        }
        return Pair(true, null)
    }

    private fun getRetryTarget(node: attractor.dot.DotNode, graph: DotGraph): String? {
        if (node.retryTarget.isNotBlank() && node.retryTarget in graph.nodes) return node.retryTarget
        if (node.fallbackRetryTarget.isNotBlank() && node.fallbackRetryTarget in graph.nodes) return node.fallbackRetryTarget
        if (graph.retryTarget.isNotBlank() && graph.retryTarget in graph.nodes) return graph.retryTarget
        if (graph.fallbackRetryTarget.isNotBlank() && graph.fallbackRetryTarget in graph.nodes) return graph.fallbackRetryTarget
        return null
    }

    private fun mirrorGraphAttributes(graph: DotGraph, context: Context) {
        context.set("graph.goal", graph.goal)
        context.set("graph.label", graph.label)
        graph.attrs.forEach { (k, v) -> context.set("graph.$k", v.asString()) }
    }

    private fun writeManifest(graph: DotGraph, runId: String) {
        val manifest = mapOf(
            "run_id" to runId,
            "graph_name" to graph.id,
            "goal" to graph.goal,
            "start_time" to Instant.now().toString()
        )
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        File(config.logsRoot, "manifest.json").writeText(
            json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    manifest.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                }
            )
        )
    }

    private fun elapsed(): Long = System.currentTimeMillis() - startTime
}
