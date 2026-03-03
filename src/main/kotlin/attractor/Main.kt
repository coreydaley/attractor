package attractor

import attractor.db.DatabaseConfig
import attractor.db.RunStoreFactory
import attractor.web.PipelineRegistry
import attractor.web.WebMonitorServer

private const val DEFAULT_WEB_PORT = 7070

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val webPort = parsed.webPort ?: DEFAULT_WEB_PORT

    val dbConfig = DatabaseConfig.fromEnv()
    println("[attractor] Database: ${dbConfig.displayName}")
    val store = RunStoreFactory.create(dbConfig)
    val registry = PipelineRegistry(store)
    val webServer = WebMonitorServer(webPort, registry, store)
    registry.loadFromDB { webServer.broadcastUpdate() }
    webServer.start()

    println("[attractor] Web interface running at http://localhost:$webPort")
    println("[attractor] Press Ctrl+C to exit.")
    Runtime.getRuntime().addShutdownHook(Thread {
        webServer.stop()
        store.close()
    })
    Thread.currentThread().join()
}

data class ParsedArgs(
    val logsRoot: String? = null,
    val webPort: Int? = null
)

fun parseArgs(args: Array<String>): ParsedArgs {
    var logsRoot: String? = null
    var webPort: Int? = null

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--logs-root" -> {
                i++
                if (i < args.size) logsRoot = args[i]
            }
            "--web-port" -> {
                i++
                if (i < args.size) webPort = args[i].toIntOrNull()
            }
        }
        i++
    }

    return ParsedArgs(logsRoot, webPort)
}
