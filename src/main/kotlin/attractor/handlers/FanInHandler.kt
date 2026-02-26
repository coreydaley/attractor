package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome
import attractor.state.StageStatus

class FanInHandler : Handler {
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        // 1. Read parallel results
        val resultsRaw = context.getString("parallel.results")
        if (resultsRaw.isBlank()) {
            return Outcome.fail("No parallel results to evaluate at fan-in '${node.id}'")
        }

        // Parse results: "nodeId:STATUS,nodeId:STATUS,..."
        data class BranchResult(val id: String, val status: StageStatus)

        val results = resultsRaw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val id = parts[0]
                val status = try { StageStatus.valueOf(parts[1].uppercase()) } catch (e: Exception) { StageStatus.FAIL }
                BranchResult(id, status)
            } else null
        }

        if (results.isEmpty()) {
            return Outcome.fail("Could not parse parallel results at fan-in '${node.id}'")
        }

        // 2. Select best candidate using heuristic
        val outcomeRank = mapOf(
            StageStatus.SUCCESS to 0,
            StageStatus.PARTIAL_SUCCESS to 1,
            StageStatus.RETRY to 2,
            StageStatus.SKIPPED to 3,
            StageStatus.FAIL to 4
        )

        val best = results.sortedWith(
            compareBy(
                { outcomeRank[it.status] ?: 5 },
                { it.id }
            )
        ).first()

        // Check if all failed
        if (results.all { it.status == StageStatus.FAIL }) {
            return Outcome.fail("All parallel branches failed at fan-in '${node.id}'")
        }

        // 3. Record winner in context
        return Outcome(
            status = StageStatus.SUCCESS,
            contextUpdates = mapOf(
                "parallel.fan_in.best_id" to best.id,
                "parallel.fan_in.best_outcome" to best.status.toString()
            ),
            notes = "Selected best candidate: ${best.id} (${best.status})"
        )
    }
}
