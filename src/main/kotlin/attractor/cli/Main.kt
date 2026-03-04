package attractor.cli

import attractor.cli.commands.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        run(args.toList())
    } catch (e: CliException) {
        System.err.println("Error: ${e.message}")
        exitProcess(e.exitCode)
    }
}

internal fun run(args: List<String>) {
    // Parse global flags
    var host = "http://localhost:8080"
    var outputFormat = OutputFormat.TEXT
    val remaining = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> {
                i++
                host = args.getOrNull(i)
                    ?: throw CliException("--host requires a value", 2)
            }
            "--output" -> {
                i++
                outputFormat = when (args.getOrNull(i)?.lowercase()) {
                    "json" -> OutputFormat.JSON
                    "text", "table" -> OutputFormat.TEXT
                    null -> throw CliException("--output requires a value (text|json)", 2)
                    else -> throw CliException("Unknown output format: ${args[i]} (use text or json)", 2)
                }
            }
            "--help", "-h" -> {
                printGlobalHelp()
                return
            }
            "--version" -> {
                printVersion()
                return
            }
            else -> remaining.add(args[i])
        }
        i++
    }

    val ctx = CliContext(baseUrl = host, outputFormat = outputFormat)
    val cmdArgs = remaining.drop(1)

    when (remaining.firstOrNull()) {
        "project" -> ProjectCommands(ctx).dispatch(cmdArgs)
        "artifact" -> ArtifactCommands(ctx).dispatch(cmdArgs)
        "dot"      -> DotCommands(ctx).dispatch(cmdArgs)
        "settings" -> SettingsCommands(ctx).dispatch(cmdArgs)
        "models"   -> ModelsCommand(ctx).dispatch(cmdArgs)
        "events"   -> EventsCommand(ctx).dispatch(cmdArgs)
        null, "--help", "-h" -> {
            printGlobalHelp()
        }
        else -> throw CliException("Unknown resource: '${remaining.first()}'\nRun 'attractor --help' for usage.", 2)
    }
}

private fun printGlobalHelp() {
    println("""
attractor - Attractor project orchestration CLI

Usage:
  attractor [--host <url>] [--output <text|json>] <resource> <verb> [options]

Global options:
  --host <url>        Attractor server URL (default: http://localhost:8080)
  --output <format>   Output format: text (default) or json
  --help              Show this help
  --version           Print version

Resources:
  project    Manage projects (list, get, create, update, delete, lifecycle)
  artifact   Access project artifacts (logs, files, ZIPs)
  dot        DOT generation, validation, fixing, iteration
  settings   Manage server settings
  models     List available LLM models
  events     Stream real-time project events (SSE)

Examples:
  attractor project list
  attractor project create --file my-project.dot
  attractor project watch run-1700000000000-1
  attractor artifact stage-log run-1700000000000-1 writeTests
  attractor dot generate --prompt "Build a CI project"
  attractor settings set fireworks_enabled false
  attractor events

Run 'attractor <resource> --help' for resource-specific help.
    """.trimIndent())
}

private fun printVersion() {
    val version = object {}::class.java.`package`?.implementationVersion ?: "unknown"
    println("attractor-cli $version")
}
