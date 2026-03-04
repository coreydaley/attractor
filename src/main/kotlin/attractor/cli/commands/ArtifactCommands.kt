package attractor.cli.commands

import attractor.cli.*
import kotlinx.serialization.json.*
import java.io.File

class ArtifactCommands(private val ctx: CliContext) {
    private val client = ApiClient(ctx)

    fun dispatch(args: List<String>) {
        when (args.firstOrNull()) {
            "list"            -> list(args.drop(1))
            "get"             -> get(args.drop(1))
            "download-zip"    -> downloadZip(args.drop(1))
            "stage-log"       -> stageLog(args.drop(1))
            "failure-report"  -> failureReport(args.drop(1))
            "export"          -> export(args.drop(1))
            "import"          -> import(args.drop(1))
            "--help", "-h", null -> printHelp()
            else -> throw CliException("Unknown artifact verb: '${args.first()}'\nRun 'attractor artifact --help' for usage.", 2)
        }
    }

    private fun list(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor artifact list <id>", 2)
        val json = client.get("/api/v1/projects/$id/artifacts")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(json); return }
        val obj = Json.parseToJsonElement(json).jsonObject
        val files = obj["files"]?.jsonArray ?: JsonArray(emptyList())
        val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
        val rows = files.map { f ->
            val fo = f.jsonObject
            val size = fo["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val sizeStr = when {
                size >= 1_048_576 -> "%.1fMB".format(size / 1_048_576.0)
                size >= 1_024 -> "%.1fKB".format(size / 1_024.0)
                else -> "${size}B"
            }
            listOf(
                fo["path"]?.jsonPrimitive?.content ?: "",
                sizeStr,
                if (fo["isText"]?.jsonPrimitive?.booleanOrNull == true) "text" else "binary"
            )
        }
        Formatter.printTable(listOf("PATH", "SIZE", "TYPE"), rows)
        if (truncated) println("(results truncated to 500 files)")
    }

    private fun get(args: List<String>) {
        if (args.size < 2) throw CliException("Usage: attractor artifact get <id> <path>", 2)
        val id = args[0]
        val path = args[1]
        val content = client.get("/api/v1/projects/$id/artifacts/$path")
        Formatter.printLine(content)
    }

    private fun downloadZip(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor artifact download-zip <id> [--output <file>]", 2)
        var outputPath: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--output" -> { i++; outputPath = args.getOrNull(i) ?: throw CliException("--output requires a path", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        val bytes = client.getBinary("/api/v1/projects/$id/artifacts.zip")
        val outFile = File(outputPath ?: "artifacts-$id.zip")
        outFile.writeBytes(bytes)
        println("Saved to ${outFile.name} (${bytes.size} bytes)")
    }

    private fun stageLog(args: List<String>) {
        if (args.size < 2) throw CliException("Usage: attractor artifact stage-log <id> <nodeId>", 2)
        val id = args[0]
        val nodeId = args[1]
        val content = client.get("/api/v1/projects/$id/stages/$nodeId/log")
        Formatter.printLine(content)
    }

    private fun failureReport(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor artifact failure-report <id>", 2)
        val json = client.get("/api/v1/projects/$id/failure-report")
        Formatter.printJson(json)
    }

    private fun export(args: List<String>) {
        val id = args.firstOrNull() ?: throw CliException("Usage: attractor artifact export <id> [--output <file>]", 2)
        var outputPath: String? = null
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--output" -> { i++; outputPath = args.getOrNull(i) ?: throw CliException("--output requires a path", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        val bytes = client.getBinary("/api/v1/projects/$id/export")
        val outFile = File(outputPath ?: "project-$id.zip")
        outFile.writeBytes(bytes)
        println("Saved to ${outFile.name} (${bytes.size} bytes)")
    }

    private fun import(args: List<String>) {
        val filePath = args.firstOrNull() ?: throw CliException("Usage: attractor artifact import <file> [--on-conflict skip|overwrite]", 2)
        var onConflict = "skip"
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--on-conflict" -> { i++; onConflict = args.getOrNull(i) ?: throw CliException("--on-conflict requires skip or overwrite", 2) }
                else -> throw CliException("Unknown option: ${args[i]}", 2)
            }
            i++
        }
        val bytes = File(filePath).also {
            if (!it.exists()) throw CliException("File not found: $filePath", 1)
        }.readBytes()
        val resp = client.postBinary("/api/v1/projects/import", bytes, "onConflict=$onConflict")
        if (ctx.outputFormat == OutputFormat.JSON) { Formatter.printJson(resp); return }
        val obj = Json.parseToJsonElement(resp).jsonObject
        println("status: ${obj["status"]?.jsonPrimitive?.content}")
        obj["id"]?.jsonPrimitive?.content?.let { println("id:     $it") }
    }

    private fun printHelp() {
        println("""
attractor artifact - Project artifact commands

Usage:
  attractor artifact <verb> [options]

Verbs:
  list <id>                         List all artifact files for a project
  get <id> <path>                   Print artifact file content to stdout
  download-zip <id>                 Download all artifacts as a ZIP file
    [--output <file>]                 Output filename (default: artifacts-<id>.zip)
  stage-log <id> <nodeId>           Print the stage log for a specific node
  failure-report <id>               Print the failure report JSON
  export <id>                       Export project as a ZIP archive
    [--output <file>]                 Output filename (default: project-<id>.zip)
  import <file>                     Import a project from a ZIP archive
    [--on-conflict skip|overwrite]    Conflict resolution (default: skip)
        """.trimIndent())
    }
}
