package attractor.state

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

@Serializable
data class CheckpointData(
    val timestamp: String,
    val currentNode: String,
    val completedNodes: List<String>,
    val nodeRetries: Map<String, Int>,
    val context: Map<String, String>,
    val logs: List<String>,
    val stageDurations: Map<String, Long> = emptyMap()
)

class Checkpoint(
    val timestamp: Instant = Instant.now(),
    val currentNode: String = "",
    val completedNodes: List<String> = emptyList(),
    val nodeRetries: Map<String, Int> = emptyMap(),
    val contextValues: Map<String, Any> = emptyMap(),
    val logs: List<String> = emptyList(),
    val stageDurations: Map<String, Long> = emptyMap()
) {
    private val json = Json { prettyPrint = true }

    fun save(logsRoot: String) {
        val dir = File(logsRoot)
        dir.mkdirs()
        val file = File(dir, "checkpoint.json")
        val data = CheckpointData(
            timestamp = timestamp.toString(),
            currentNode = currentNode,
            completedNodes = completedNodes,
            nodeRetries = nodeRetries,
            context = contextValues.mapValues { it.value.toString() },
            logs = logs,
            stageDurations = stageDurations
        )
        file.writeText(json.encodeToString(data))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(logsRoot: String): Checkpoint? {
            val file = File(logsRoot, "checkpoint.json")
            if (!file.exists()) return null
            return try {
                val data = json.decodeFromString<CheckpointData>(file.readText())
                Checkpoint(
                    timestamp = Instant.parse(data.timestamp),
                    currentNode = data.currentNode,
                    completedNodes = data.completedNodes,
                    nodeRetries = data.nodeRetries,
                    contextValues = data.context,
                    logs = data.logs,
                    stageDurations = data.stageDurations
                )
            } catch (e: Exception) {
                null
            }
        }

        fun create(context: Context, currentNodeId: String, completedNodes: List<String>, stageDurations: Map<String, Long> = emptyMap()): Checkpoint {
            val snapshot = context.snapshot()
            val retries = snapshot
                .filter { it.key.startsWith("internal.retry_count.") }
                .mapKeys { it.key.removePrefix("internal.retry_count.") }
                .mapValues { (it.value as? Number)?.toInt() ?: 0 }
            return Checkpoint(
                currentNode = currentNodeId,
                completedNodes = completedNodes,
                nodeRetries = retries,
                contextValues = snapshot,
                logs = context.logs(),
                stageDurations = stageDurations
            )
        }
    }
}
