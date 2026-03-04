package attractor.cli.commands

import attractor.cli.*

class EventsCommand(private val ctx: CliContext) {
    private val client = ApiClient(ctx)

    fun dispatch(args: List<String>) {
        when {
            args.firstOrNull() in listOf("--help", "-h") -> printHelp()
            args.isEmpty() -> stream(null)
            else -> stream(args.first())
        }
    }

    private fun stream(projectId: String?) {
        val path = if (projectId != null) "/api/v1/events/$projectId" else "/api/v1/events"
        for (line in client.getStream(path)) {
            println(line)
        }
    }

    private fun printHelp() {
        println("""
attractor events - Stream real-time project events (SSE)

Usage:
  attractor events [<project-id>]

Arguments:
  <project-id>   Optional. Stream events for a specific project only.
                  Without an ID, streams all events. Press Ctrl+C to stop.
        """.trimIndent())
    }
}
