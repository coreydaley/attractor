package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*

class EventsCommand(private val ctx: CliContext) {
    private val client = ApiClient(ctx)
    private val terminal = setOf("completed", "failed", "cancelled")

    fun dispatch(args: List<String>) {
        val id = args.firstOrNull()?.takeIf { !it.startsWith("-") }
        when {
            args.firstOrNull() == "--help" || args.firstOrNull() == "-h" -> printHelp()
            id != null -> streamPipeline(id)
            else -> streamAll()
        }
    }

    private fun streamAll() {
        System.err.println("Streaming events (Ctrl+C to stop)...")
        for (data in client.getStream("/api/v1/events")) {
            if (ctx.outputFormat == OutputFormat.JSON) {
                Formatter.printJson(data)
            } else {
                try {
                    val obj = Json.parseToJsonElement(data).jsonObject
                    val count = obj["pipelines"]?.jsonArray?.size ?: 0
                    println("Updated: $count pipeline(s)")
                } catch (_: Exception) {
                    Formatter.printLine(data)
                }
            }
        }
    }

    private fun streamPipeline(id: String) {
        System.err.println("Streaming events for $id (exits on terminal state)...")
        for (data in client.getStream("/api/v1/events/$id")) {
            if (ctx.outputFormat == OutputFormat.JSON) {
                Formatter.printJson(data)
            } else {
                try {
                    val obj = Json.parseToJsonElement(data).jsonObject
                    val pipeline = obj["pipeline"]?.jsonObject ?: obj["pipelines"]?.jsonArray?.firstOrNull {
                        it.jsonObject["id"]?.jsonPrimitive?.content == id
                    }?.jsonObject
                    if (pipeline != null) {
                        val status = pipeline["status"]?.jsonPrimitive?.content ?: "unknown"
                        val current = pipeline["currentNode"]?.let { if (it is JsonNull) "" else it.jsonPrimitive.content } ?: ""
                        if (current.isNotBlank()) {
                            println("status: $status | current: $current")
                        } else {
                            println("status: $status")
                        }
                        if (status in terminal) return
                    }
                } catch (_: Exception) {
                    Formatter.printLine(data)
                }
            }
        }
    }

    private fun printHelp() {
        println("""
attractor events - Stream real-time pipeline events (SSE)

Usage:
  attractor events              Stream events for all pipelines (Ctrl+C to stop)
  attractor events <id>         Stream events for a single pipeline (exits on terminal state)

The events stream receives an update whenever any pipeline changes state.
        """.trimIndent())
    }
}
