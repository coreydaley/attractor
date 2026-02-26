package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.engine.EdgeSelector
import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit

class ParallelHandler(private val registry: HandlerRegistry? = null) : Handler {

    /**
     * Traverse the graph from startNodeId until a terminal, fan-in, or dead-end node.
     * Each branch gets an isolated clone of the context (Section 3.8).
     */
    private fun executeSubgraph(
        startNodeId: String,
        context: Context,
        graph: DotGraph,
        logsRoot: String
    ): Outcome {
        val reg = registry ?: return Outcome.success(notes = "Branch $startNodeId completed (no registry)")

        var currentNodeId = startNodeId
        var lastOutcome = Outcome.success()

        while (true) {
            val node = graph.nodes[currentNodeId] ?: break

            // Stop at terminal or fan-in nodes
            if (node.isTerminal()) break
            val nodeType = SHAPE_TO_TYPE[node.shape] ?: node.type
            if (nodeType == "parallel.fan_in") break

            val handler = reg.resolve(node)
            lastOutcome = try {
                handler.execute(node, context, graph, logsRoot)
            } catch (e: Exception) {
                Outcome.fail("Branch node ${node.id} threw: ${e.message}")
            }

            if (lastOutcome.status == StageStatus.FAIL) break

            // Apply context updates within the branch's isolated context
            context.applyUpdates(lastOutcome.contextUpdates)

            val nextEdge = EdgeSelector.select(node, lastOutcome, context, graph) ?: break
            currentNodeId = nextEdge.to
        }

        return lastOutcome
    }

    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        // 1. Get fan-out edges
        val branches = graph.outgoingEdges(node.id)
        if (branches.isEmpty()) {
            return Outcome.fail("Parallel node '${node.id}' has no outgoing branches")
        }

        // 2. Get policy attributes
        val joinPolicy = node.attrOrDefault("join_policy", "wait_all")
        val errorPolicy = node.attrOrDefault("error_policy", "continue")
        val maxParallel = node.attrLong("max_parallel", 4L).toInt().coerceAtLeast(1)

        // 3. Execute branches concurrently with bounded parallelism
        val results = runBlocking {
            val semaphore = kotlinx.coroutines.sync.Semaphore(maxParallel)
            branches.map { branch ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val branchContext = context.clone()
                        try {
                            executeSubgraph(branch.to, branchContext, graph, logsRoot)
                        } catch (e: Exception) {
                            if (errorPolicy == "fail_fast") {
                                coroutineContext.cancel()
                            }
                            Outcome.fail("Branch ${branch.to} failed: ${e.message}")
                        }
                    }
                }
            }.let { deferred ->
                if (errorPolicy == "fail_fast") {
                    // Cancel remaining on first failure
                    val collected = mutableListOf<Outcome>()
                    for (d in deferred) {
                        val result = d.await()
                        collected.add(result)
                        if (result.status == StageStatus.FAIL) {
                            deferred.forEach { it.cancel() }
                            break
                        }
                    }
                    collected
                } else {
                    deferred.awaitAll()
                }
            }
        }

        // 4. Evaluate join policy
        val successCount = results.count { it.status.isSuccess }
        val failCount = results.count { it.status == StageStatus.FAIL }
        val total = results.size

        val (finalStatus, notes) = when (joinPolicy) {
            "wait_all" -> {
                if (failCount == 0) Pair(StageStatus.SUCCESS, "All $total branches completed")
                else Pair(StageStatus.PARTIAL_SUCCESS, "$failCount/$total branches failed")
            }
            "first_success" -> {
                if (successCount > 0) Pair(StageStatus.SUCCESS, "First success achieved")
                else Pair(StageStatus.FAIL, "No branches succeeded")
            }
            "k_of_n" -> {
                val k = node.attrLong("join_k", (total / 2 + 1).toLong()).toInt()
                if (successCount >= k) Pair(StageStatus.SUCCESS, "$successCount/$total branches succeeded (need $k)")
                else Pair(StageStatus.FAIL, "Only $successCount/$total branches succeeded (need $k)")
            }
            "quorum" -> {
                val quorum = node.attrOrDefault("join_quorum", "0.5").toDoubleOrNull() ?: 0.5
                val needed = (total * quorum).toInt()
                if (successCount >= needed) Pair(StageStatus.SUCCESS, "Quorum achieved: $successCount/$total")
                else Pair(StageStatus.FAIL, "Quorum not achieved: $successCount/$total (need ${(quorum * 100).toInt()}%)")
            }
            else -> Pair(StageStatus.SUCCESS, "Branches completed")
        }

        // 5. Store results in context for downstream fan-in
        val serialized = results.mapIndexed { i, r ->
            "${branches[i].to}:${r.status}"
        }.joinToString(",")

        return Outcome(
            status = finalStatus,
            notes = notes,
            contextUpdates = mapOf("parallel.results" to serialized)
        )
    }
}
