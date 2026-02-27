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

    private fun stream(pipelineId: String?) {
        val path = if (pipelineId != null) "/api/v1/events/$pipelineId" else "/api/v1/events"
        for (line in client.getStream(path)) {
            println(line)
        }
    }

    private fun printHelp() {
        println("""
attractor events - Stream real-time pipeline events (SSE)

Usage:
  attractor events [<pipeline-id>]

Arguments:
  <pipeline-id>   Optional. Stream events for a specific pipeline only.
                  Without an ID, streams all events. Press Ctrl+C to stop.
        """.trimIndent())
    }
}
