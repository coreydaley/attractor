package attractor

import attractor.db.RunStore
import attractor.web.PipelineRegistry
import attractor.web.PipelineRunner
import attractor.web.RunOptions
import attractor.web.WebMonitorServer
import java.io.File
import kotlin.system.exitProcess

private const val DEFAULT_WEB_PORT = 7070

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val webPort = parsed.webPort ?: DEFAULT_WEB_PORT

    // Always start the web interface
    val store = RunStore("attractor.db")
    val registry = PipelineRegistry(store)
    val webServer = WebMonitorServer(webPort, registry, store)
    registry.loadFromDB { webServer.broadcastUpdate() }
    webServer.start()

    // If a .dot file was provided on the CLI, submit it immediately
    if (parsed.dotFile != null) {
        val dotFile = File(parsed.dotFile)
        if (!dotFile.exists()) {
            System.err.println("Error: File not found: ${parsed.dotFile}")
            exitProcess(1)
        }
        val dotSource = dotFile.readText()
        val options = RunOptions(simulate = parsed.simulate, autoApprove = parsed.autoApprove)
        val id = PipelineRunner.submit(dotSource, dotFile.name, options, registry, store) {
            webServer.broadcastUpdate()
        }
        println("[attractor] Pipeline submitted: $id (${dotFile.name})")
    } else {
        println("[attractor] No pipeline specified. Upload a .dot file via the web interface.")
    }

    println("[attractor] Press Ctrl+C to exit.")
    Runtime.getRuntime().addShutdownHook(Thread {
        webServer.stop()
        store.close()
    })
    Thread.currentThread().join()
}

data class ParsedArgs(
    val dotFile: String? = null,
    val autoApprove: Boolean = false,
    val logsRoot: String? = null,
    val simulate: Boolean = false,
    val resume: Boolean = false,
    val webPort: Int? = null
)

fun parseArgs(args: Array<String>): ParsedArgs {
    var dotFile: String? = null
    var autoApprove = false
    var logsRoot: String? = null
    var simulate = false
    var resume = false
    var webPort: Int? = null

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--auto-approve" -> autoApprove = true
            "--simulate" -> simulate = true
            "--resume" -> resume = true
            "--logs-root" -> {
                i++
                if (i < args.size) logsRoot = args[i]
            }
            "--web-port" -> {
                i++
                if (i < args.size) webPort = args[i].toIntOrNull()
            }
            else -> {
                if (!arg.startsWith("--")) {
                    dotFile = arg
                }
            }
        }
        i++
    }

    return ParsedArgs(dotFile, autoApprove, logsRoot, simulate, resume, webPort)
}

fun printUsage() {
    println("""
        Attractor - DOT-based pipeline runner

        Usage: attractor [pipeline.dot] [options]

        Arguments:
          [pipeline.dot]     Optional path to a DOT pipeline file to run immediately

        Options:
          --auto-approve     Auto-approve all human gates (no interactive prompts)
          --simulate         Use simulation mode (no LLM API calls)
          --resume           Resume from the last checkpoint in --logs-root
          --logs-root <dir>  Output directory for logs and artifacts (default: logs/<name>-<timestamp>)
          --web-port <n>     Web interface port (default: $DEFAULT_WEB_PORT)

        Environment Variables:
          ANTHROPIC_API_KEY  API key for Anthropic Claude
          OPENAI_API_KEY     API key for OpenAI GPT
          GEMINI_API_KEY     API key for Google Gemini
          ATTRACTOR_DEBUG    Set to any value to enable debug output (stack traces)

        Examples:
          attractor                                   # start web interface only
          attractor pipeline.dot                      # run pipeline + open web interface
          attractor pipeline.dot --simulate           # simulation mode
          attractor pipeline.dot --auto-approve       # skip human gates
          attractor pipeline.dot --web-port 8080      # use a different port
    """.trimIndent())
}
