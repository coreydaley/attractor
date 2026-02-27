package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PipelineCommands(private val ctx: CliContext) {
    private val client = ApiClient(ctx)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun dispatch(args: List<String>) {
        when (args.firstOrNull()) {
            "list"      -> list(args.drop(1))
            "get"       -> get(args.drop(1))
            "create"    -> create(args.drop(1))
            "update"    -> update(args.drop(1))
            "delete"    -> delete(args.drop(1))
            "rerun"     -> lifecycle(args.drop(1), "rerun")
            "pause"     -> lifecycle(args.drop(1), "pause")
            "resume"    -> lifecycle(args.drop(1), "resume")
            "cancel"    -> lifecycle(args.drop(1), "cancel")
            "archive"   -> lifecycle(args.drop(1), "archive")
            "unarchive" -> lifecycle(args.drop(1), "unarchive")
            "stages"    -> stages(args.drop(1))
            "watch"     -> watch(args.drop(1))
            "iterate"   -> iterate(args.drop(1))
            "family"    -> family(args.drop(1))
            "--help", "-h", null -> printHelp()
            else -> throw CliException("Unknown pipeline verb: '${args.first()}'\nRun 'attractor pipeline --help' for usage.", 2)
        }
    }

    private fun list(@Suppress("UNUSED_PARAMETER") args: List<String>) {
        val json = client.get("/api/v1/pipelines")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val arr = Json.parseToJsonElement(json).jsonArray
        val rows = arr.map { p ->
            val obj = p.jsonObject
            val startedAt = obj["startedAt"]?.jsonPrimitive?.longOrNull
            val started = if (startedAt != null) fmt.format(Instant.ofEpochMilli(startedAt)) else "-"
            listOf(
                obj["id"]?.jsonPrimitive?.content ?: "",
                obj["displayName"]?.jsonPrimitive?.content ?: "",
                obj["status"]?.jsonPrimitive?.content ?: "",
                started
            )
        }
        Formatter.printTable(listOf("ID", "NAME", "STATUS", "STARTED"), rows)
    }

    private fun get(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline get <id>", 2)
        val json = client.get("/api/v1/pipelines/$id")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val fields = listOf("id","displayName","fileName","status","archived","hasFailureReport",
            "simulate","autoApprove","familyId","originalPrompt","currentNode")
        for (f in fields) {
            val v = obj[f]?.let {
                if (it is JsonPrimitive) it.content else it.toString()
            } ?: "-"
            println("%-20s %s".format(f+":", v))
        }
        val startedAt = obj["startedAt"]?.jsonPrimitive?.longOrNull
        println("%-20s %s".format("startedAt:", if (startedAt != null) fmt.format(Instant.ofEpochMilli(startedAt)) else "-"))
    }

    private fun create(args: List<String>) {
        var filePath: String? = null
        var name: String? = null
        var simulate = false
        var autoApprove = true
        var prompt: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file" -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--name" -> { i++; name = args.getOrNull(i) ?: throw CliException("--name requires a value", 2) }
                "--simulate" -> simulate = true
                "--no-auto-approve" -> autoApprove = false
                "--prompt" -> { i++; prompt = args.getOrNull(i) ?: throw CliException("--prompt requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor pipeline create --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject {
            put("dotSource", dotSource)
            put("fileName", name ?: File(filePath).name)
            put("simulate", simulate)
            put("autoApprove", autoApprove)
            if (prompt != null) put("originalPrompt", prompt)
        }.toString()
        val resp = client.post("/api/v1/pipelines", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println("id:     ${obj["id"]?.jsonPrimitive?.content}")
        println("status: ${obj["status"]?.jsonPrimitive?.content}")
    }

    private fun update(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline update <id> [--file <path>] [--prompt <text>]", 2)
        var filePath: String? = null
        var prompt: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--file" -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--prompt" -> { i++; prompt = args.getOrNull(i) ?: throw CliException("--prompt requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        val bodyObj = buildJsonObject {
            if (filePath != null) {
                val dotSource = File(filePath).also {
                    if (!it.exists()) throw CliException("File not found: $filePath", 1)
                }.readText()
                put("dotSource", dotSource)
            }
            if (prompt != null) put("originalPrompt", prompt)
        }
        val resp = client.patch("/api/v1/pipelines/$id", bodyObj.toString())
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println("id:     ${obj["id"]?.jsonPrimitive?.content}")
        println("status: ${obj["status"]?.jsonPrimitive?.content}")
    }

    private fun delete(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline delete <id>", 2)
        val resp = client.delete("/api/v1/pipelines/$id")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println("deleted: ${obj["deleted"]?.jsonPrimitive?.content}")
    }

    private fun lifecycle(args: List<String>, verb: String) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline $verb <id>", 2)
        val resp = client.post("/api/v1/pipelines/$id/$verb")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        // Print all fields in the response
        for ((k, v) in obj) {
            val value = if (v is JsonPrimitive) v.content else v.toString()
            println("$k: $value")
        }
    }

    private fun stages(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline stages <id>", 2)
        val json = client.get("/api/v1/pipelines/$id/stages")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val arr = Json.parseToJsonElement(json).jsonArray
        val rows = arr.map { s ->
            val obj = s.jsonObject
            val dur = obj["durationMs"]?.jsonPrimitive?.longOrNull
            val durStr = if (dur != null) "${dur}ms" else "-"
            val err = obj["error"]?.let { if (it is JsonNull) "-" else it.jsonPrimitive.content } ?: "-"
            listOf(
                (obj["index"]?.jsonPrimitive?.intOrNull?.plus(1) ?: 0).toString(),
                obj["nodeId"]?.jsonPrimitive?.content ?: "",
                obj["status"]?.jsonPrimitive?.content ?: "",
                durStr,
                err
            )
        }
        Formatter.printTable(listOf("#", "NODE", "STATUS", "DURATION", "ERROR"), rows)
    }

    private fun watch(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline watch <id> [--interval-ms <n>] [--timeout-ms <n>]", 2)
        var intervalMs = 2000L
        var timeoutMs: Long? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--interval-ms" -> { i++; intervalMs = args.getOrNull(i)?.toLongOrNull() ?: throw CliException("--interval-ms requires a number", 2) }
                "--timeout-ms"  -> { i++; timeoutMs = args.getOrNull(i)?.toLongOrNull() ?: throw CliException("--timeout-ms requires a number", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        val startTime = System.currentTimeMillis()
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val terminal = setOf("completed", "failed", "cancelled")
        while (true) {
            if (timeoutMs != null && System.currentTimeMillis() - startTime > timeoutMs) {
                throw CliException("Watch timed out after ${timeoutMs}ms", 1)
            }
            val json = client.get("/api/v1/pipelines/$id")
            val obj = Json.parseToJsonElement(json).jsonObject
            val status = obj["status"]?.jsonPrimitive?.content ?: "unknown"
            val current = obj["currentNode"]?.let { if (it is JsonNull) "" else it.jsonPrimitive.content } ?: ""
            val time = timeFmt.format(Instant.now())
            if (current.isNotBlank()) {
                println("[$time] status: $status | current: $current")
            } else {
                println("[$time] status: $status")
            }
            if (status in terminal) {
                if (status == "failed" || status == "cancelled") {
                    throw CliException("Pipeline $status", 1)
                }
                return
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun iterate(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline iterate <id> --file <path>", 2)
        var filePath: String? = null
        var prompt: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--file" -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--prompt" -> { i++; prompt = args.getOrNull(i) ?: throw CliException("--prompt requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor pipeline iterate <id> --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject {
            put("dotSource", dotSource)
            if (prompt != null) put("originalPrompt", prompt)
        }.toString()
        val resp = client.post("/api/v1/pipelines/$id/iterations", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println("id:       ${obj["id"]?.jsonPrimitive?.content}")
        println("status:   ${obj["status"]?.jsonPrimitive?.content}")
        println("familyId: ${obj["familyId"]?.jsonPrimitive?.content}")
    }

    private fun family(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor pipeline family <id>", 2)
        val json = client.get("/api/v1/pipelines/$id/family")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val members = obj["members"]?.jsonArray ?: JsonArray(emptyList())
        val rows = members.map { m ->
            val mo = m.jsonObject
            val createdAt = mo["createdAt"]?.jsonPrimitive?.longOrNull
            val created = if (createdAt != null) fmt.format(Instant.ofEpochMilli(createdAt)) else "-"
            listOf(
                mo["versionNum"]?.jsonPrimitive?.intOrNull?.toString() ?: "-",
                mo["id"]?.jsonPrimitive?.content ?: "",
                mo["displayName"]?.jsonPrimitive?.content ?: "",
                mo["status"]?.jsonPrimitive?.content ?: "",
                created
            )
        }
        Formatter.printTable(listOf("VER", "ID", "NAME", "STATUS", "CREATED"), rows)
    }

    private fun printHelp() {
        println("""
attractor pipeline - Pipeline management commands

Usage:
  attractor pipeline <verb> [options]

Verbs:
  list                              List all pipelines
  get <id>                          Get pipeline details
  create --file <path>              Create and run a pipeline from a DOT file
    [--name <name>]                   Display filename
    [--simulate]                      Run in simulation mode (no LLM calls)
    [--no-auto-approve]               Require manual approval at approval gates
    [--prompt <text>]                 Natural language prompt that generated the DOT
  update <id>                       Update a pipeline's DOT source or prompt
    [--file <path>]                   New DOT source file
    [--prompt <text>]                 Updated prompt
  delete <id>                       Delete a pipeline and its artifacts
  rerun <id>                        Rerun a pipeline from the start
  pause <id>                        Pause a running pipeline
  resume <id>                       Resume a paused pipeline
  cancel <id>                       Cancel a running or paused pipeline
  archive <id>                      Archive a pipeline
  unarchive <id>                    Unarchive a pipeline
  stages <id>                       List pipeline stages
  watch <id>                        Poll pipeline until it reaches terminal state
    [--interval-ms <n>]               Poll interval in ms (default: 2000)
    [--timeout-ms <n>]                Timeout in ms (no timeout by default)
  iterate <id> --file <path>        Create a new iteration in the same family
    [--prompt <text>]                 Updated prompt
  family <id>                       List all runs in the same family
        """.trimIndent())
    }
}
