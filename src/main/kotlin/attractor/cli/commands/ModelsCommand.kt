package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*

class ModelsCommand(private val ctx: CliContext) {
    private val client = ApiClient(ctx)

    fun dispatch(args: List<String>) {
        when (args.firstOrNull()) {
            "list", null -> list()
            "--help", "-h" -> printHelp()
            else -> throw CliException("Unknown models verb: '${args.first()}'\nRun 'attractor models --help' for usage.", 2)
        }
    }

    private fun list() {
        val json = client.get("/api/v1/models")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val models = obj["models"]?.jsonArray ?: JsonArray(emptyList())
        val rows = models.map { m ->
            val mo = m.jsonObject
            val contextWindow = mo["contextWindow"]?.jsonPrimitive?.longOrNull
            val ctxStr = if (contextWindow != null) "${contextWindow / 1000}k" else "-"
            listOf(
                mo["id"]?.jsonPrimitive?.content ?: "",
                mo["provider"]?.jsonPrimitive?.content ?: "",
                mo["displayName"]?.jsonPrimitive?.content ?: "",
                ctxStr,
                if (mo["supportsTools"]?.jsonPrimitive?.booleanOrNull == true) "yes" else "no",
                if (mo["supportsVision"]?.jsonPrimitive?.booleanOrNull == true) "yes" else "no"
            )
        }
        Formatter.printTable(listOf("ID", "PROVIDER", "NAME", "CONTEXT", "TOOLS", "VISION"), rows)
    }

    private fun printHelp() {
        println("""
attractor models - List available LLM models

Usage:
  attractor models list
        """.trimIndent())
    }
}
