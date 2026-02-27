package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*

class SettingsCommands(private val ctx: CliContext) {
    private val client = ApiClient(ctx)

    fun dispatch(args: List<String>) {
        when (args.firstOrNull()) {
            "list" -> list()
            "get"  -> get(args.drop(1))
            "set"  -> set(args.drop(1))
            "--help", "-h", null -> printHelp()
            else -> throw CliException("Unknown settings verb: '${args.first()}'\nRun 'attractor settings --help' for usage.", 2)
        }
    }

    private fun list() {
        val json = client.get("/api/v1/settings")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val rows = obj.entries.map { (k, v) ->
            listOf(k, if (v is JsonPrimitive) v.content else v.toString())
        }.sortedBy { it[0] }
        Formatter.printTable(listOf("KEY", "VALUE"), rows)
    }

    private fun get(args: List<String>) {
        val key = args.firstOrNull() ?: throw CliException("Usage: attractor settings get <key>", 2)
        val json = client.get("/api/v1/settings/$key")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val k = obj["key"]?.jsonPrimitive?.content ?: key
        val v = obj["value"]?.jsonPrimitive?.content ?: "-"
        println("$k: $v")
    }

    private fun set(args: List<String>) {
        if (args.size < 2) throw CliException("Usage: attractor settings set <key> <value>", 2)
        val key = args[0]
        val value = args[1]
        val body = buildJsonObject { put("value", value) }.toString()
        val json = client.put("/api/v1/settings/$key", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val k = obj["key"]?.jsonPrimitive?.content ?: key
        val v = obj["value"]?.jsonPrimitive?.content ?: value
        println("$k: $v")
    }

    private fun printHelp() {
        println("""
attractor settings - Server settings management

Usage:
  attractor settings <verb> [options]

Verbs:
  list              List all settings and their current values
  get <key>         Get the value of a single setting
  set <key> <value> Update a setting value

Known settings:
  fireworks_enabled   Whether fireworks animation plays on pipeline completion
        """.trimIndent())
    }
}
