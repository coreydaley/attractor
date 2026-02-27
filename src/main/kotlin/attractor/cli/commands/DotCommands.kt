package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLEncoder

class DotCommands(private val ctx: CliContext) {
    private val client = ApiClient(ctx)

    fun dispatch(args: List<String>) {
        when (args.firstOrNull()) {
            "generate"        -> generate(args.drop(1))
            "generate-stream" -> generateStream(args.drop(1))
            "validate"        -> validate(args.drop(1))
            "render"          -> render(args.drop(1))
            "fix"             -> fix(args.drop(1))
            "fix-stream"      -> fixStream(args.drop(1))
            "iterate"         -> iterate(args.drop(1))
            "iterate-stream"  -> iterateStream(args.drop(1))
            "--help", "-h", null -> printHelp()
            else -> throw CliException("Unknown dot verb: '${args.first()}'\nRun 'attractor dot --help' for usage.", 2)
        }
    }

    private fun generate(args: List<String>) {
        var prompt: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--prompt" -> { i++; prompt = args.getOrNull(i) ?: throw CliException("--prompt requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (prompt == null) throw CliException("Usage: attractor dot generate --prompt <text>", 2)
        val body = buildJsonObject { put("prompt", prompt) }.toString()
        val resp = client.post("/api/v1/dot/generate", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println(obj["dotSource"]?.jsonPrimitive?.content ?: "")
    }

    private fun generateStream(args: List<String>) {
        var prompt: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--prompt" -> { i++; prompt = args.getOrNull(i) ?: throw CliException("--prompt requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (prompt == null) throw CliException("Usage: attractor dot generate-stream --prompt <text>", 2)
        val encoded = URLEncoder.encode(prompt, "UTF-8")
        for (data in client.getStream("/api/v1/dot/generate/stream?prompt=$encoded")) {
            val obj = try { Json.parseToJsonElement(data).jsonObject } catch (_: Exception) { continue }
            when {
                obj["delta"] != null -> print(obj["delta"]!!.jsonPrimitive.content)
                obj["done"]?.jsonPrimitive?.booleanOrNull == true -> {
                    println()
                    val dotSource = obj["dotSource"]?.jsonPrimitive?.content
                    if (dotSource != null) {
                        System.err.println("--- generated DOT source ---")
                        println(dotSource)
                    }
                }
                obj["error"] != null -> throw CliException(obj["error"]!!.jsonPrimitive.content, 1)
            }
        }
    }

    private fun validate(args: List<String>) {
        var filePath: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file" -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot validate --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject { put("dotSource", dotSource) }.toString()
        val resp = client.post("/api/v1/dot/validate", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        val valid = obj["valid"]?.jsonPrimitive?.booleanOrNull ?: false
        println(if (valid) "valid" else "invalid")
        val diagnostics = obj["diagnostics"]?.jsonArray ?: JsonArray(emptyList())
        if (diagnostics.isNotEmpty()) {
            val rows = diagnostics.map { d ->
                val do_ = d.jsonObject
                listOf(
                    do_["severity"]?.jsonPrimitive?.content ?: "",
                    do_["nodeId"]?.let { if (it is JsonNull) "-" else it.jsonPrimitive.content } ?: "-",
                    do_["message"]?.jsonPrimitive?.content ?: ""
                )
            }
            Formatter.printTable(listOf("SEVERITY", "NODE", "MESSAGE"), rows)
        }
    }

    private fun render(args: List<String>) {
        var filePath: String? = null
        var outputPath: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file"   -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--output" -> { i++; outputPath = args.getOrNull(i) ?: throw CliException("--output requires a path", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot render --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject { put("dotSource", dotSource) }.toString()
        val resp = client.post("/api/v1/dot/render", body)
        val obj = Json.parseToJsonElement(resp).jsonObject
        val svg = obj["svg"]?.jsonPrimitive?.content ?: throw CliException("No SVG in response", 1)
        val outFile = File(outputPath ?: "output.svg")
        outFile.writeText(svg)
        println("Saved to ${outFile.name} (${svg.length} chars)")
    }

    private fun fix(args: List<String>) {
        var filePath: String? = null
        var error: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file"  -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--error" -> { i++; error = args.getOrNull(i) ?: throw CliException("--error requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot fix --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject {
            put("dotSource", dotSource)
            if (error != null) put("error", error)
        }.toString()
        val resp = client.post("/api/v1/dot/fix", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println(obj["dotSource"]?.jsonPrimitive?.content ?: "")
    }

    private fun fixStream(args: List<String>) {
        var filePath: String? = null
        var error: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file"  -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--error" -> { i++; error = args.getOrNull(i) ?: throw CliException("--error requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot fix-stream --file <path>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val dsEncoded = URLEncoder.encode(dotSource, "UTF-8")
        val query = StringBuilder("dotSource=$dsEncoded")
        if (error != null) query.append("&error=${URLEncoder.encode(error, "UTF-8")}")
        for (data in client.getStream("/api/v1/dot/fix/stream?$query")) {
            val obj = try { Json.parseToJsonElement(data).jsonObject } catch (_: Exception) { continue }
            when {
                obj["delta"] != null -> print(obj["delta"]!!.jsonPrimitive.content)
                obj["done"]?.jsonPrimitive?.booleanOrNull == true -> {
                    println()
                    val fixed = obj["dotSource"]?.jsonPrimitive?.content
                    if (fixed != null) println(fixed)
                }
                obj["error"] != null -> throw CliException(obj["error"]!!.jsonPrimitive.content, 1)
            }
        }
    }

    private fun iterate(args: List<String>) {
        var filePath: String? = null
        var changes: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file"    -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--changes" -> { i++; changes = args.getOrNull(i) ?: throw CliException("--changes requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot iterate --file <path> --changes <text>", 2)
        if (changes == null) throw CliException("Usage: attractor dot iterate --file <path> --changes <text>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val body = buildJsonObject {
            put("baseDot", dotSource)
            put("changes", changes)
        }.toString()
        val resp = client.post("/api/v1/dot/iterate", body)
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println(obj["dotSource"]?.jsonPrimitive?.content ?: "")
    }

    private fun iterateStream(args: List<String>) {
        var filePath: String? = null
        var changes: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--file"    -> { i++; filePath = args.getOrNull(i) ?: throw CliException("--file requires a path", 2) }
                "--changes" -> { i++; changes = args.getOrNull(i) ?: throw CliException("--changes requires a value", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        if (filePath == null) throw CliException("Usage: attractor dot iterate-stream --file <path> --changes <text>", 2)
        if (changes == null) throw CliException("Usage: attractor dot iterate-stream --file <path> --changes <text>", 2)
        val dotSource = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readText()
        val dsEncoded = URLEncoder.encode(dotSource, "UTF-8")
        val chEncoded = URLEncoder.encode(changes, "UTF-8")
        for (data in client.getStream("/api/v1/dot/iterate/stream?baseDot=$dsEncoded&changes=$chEncoded")) {
            val obj = try { Json.parseToJsonElement(data).jsonObject } catch (_: Exception) { continue }
            when {
                obj["delta"] != null -> print(obj["delta"]!!.jsonPrimitive.content)
                obj["done"]?.jsonPrimitive?.booleanOrNull == true -> {
                    println()
                    val updated = obj["dotSource"]?.jsonPrimitive?.content
                    if (updated != null) println(updated)
                }
                obj["error"] != null -> throw CliException(obj["error"]!!.jsonPrimitive.content, 1)
            }
        }
    }

    private fun printHelp() {
        println("""
attractor dot - DOT source management commands

Usage:
  attractor dot <verb> [options]

Verbs:
  generate --prompt <text>          Generate DOT from a natural language prompt (blocking)
  generate-stream --prompt <text>   Generate DOT with streaming token output
  validate --file <path>            Validate and lint a DOT file
  render --file <path>              Render DOT to SVG via Graphviz
    [--output <file>]                 Output SVG file (default: output.svg)
  fix --file <path>                 Fix syntax errors in a DOT file (blocking)
    [--error <message>]               Error message from parser/Graphviz
  fix-stream --file <path>          Fix DOT with streaming token output
    [--error <message>]               Error message from parser/Graphviz
  iterate --file <path>             Modify an existing DOT file (blocking)
    --changes <text>                  Description of desired changes
  iterate-stream --file <path>      Modify DOT with streaming token output
    --changes <text>                  Description of desired changes
        """.trimIndent())
    }
}
