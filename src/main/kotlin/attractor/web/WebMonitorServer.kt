package attractor.web

import attractor.db.RunStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebMonitorServer(private val port: Int, private val registry: PipelineRegistry, private val store: RunStore) {

    private val dotGenerator = DotGenerator(store)

    private companion object {
        val dotSanitizePass1 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"(?:[^"\\]|\\.)*"""")
        val dotSanitizePass2 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"[^"]*\z""")
        val dotSanitizePass3 = Regex("""\[\s*,\s*""")
    }

    private val httpServer = HttpServer.create(InetSocketAddress(port), 0)
    private val sseClients = CopyOnWriteArrayList<SseClient>()
    internal val restSseClients = CopyOnWriteArrayList<RestApiRouter.RestSseClient>()

    private inner class SseClient(val ex: HttpExchange) {
        val queue = LinkedBlockingQueue<String>(512)
        @Volatile var alive = true
        fun offer(json: String) { if (alive) queue.offer(json) }
    }

    init {
        httpServer.executor = Executors.newCachedThreadPool()

        // ── Dashboard HTML ───────────────────────────────────────────────────
        httpServer.createContext("/") { ex ->
            if (ex.requestMethod == "GET" && ex.requestURI.path == "/") {
                respond(ex, 200, "text/html; charset=utf-8", dashboardHtml().toByteArray())
            } else {
                ex.sendResponseHeaders(404, 0)
                ex.responseBody.close()
            }
        }

        // ── All pipelines JSON snapshot ──────────────────────────────────────
        httpServer.createContext("/api/pipelines") { ex ->
            if (ex.requestMethod == "GET") {
                val body = allPipelinesJson().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }

        // ── Single pipeline view (on-demand hydration from DB) ───────────────
        // GET /api/pipeline-view?id={runId}
        httpServer.createContext("/api/pipeline-view") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val id = ex.requestURI.query?.split("&")
                ?.firstOrNull { it.startsWith("id=") }
                ?.removePrefix("id=")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: ""
            if (id.isBlank()) {
                val err = """{"error":"missing id"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(400, err.size.toLong()); ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val entry = registry.getOrHydrate(id, store)
            if (entry == null) {
                val err = """{"error":"not found"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, err.size.toLong()); ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val body = "{\"id\":${js(entry.id)},\"fileName\":${js(entry.fileName)},\"dotSource\":${js(entry.dotSource)},\"originalPrompt\":${js(entry.originalPrompt)},\"familyId\":${js(entry.familyId)},\"simulate\":${entry.options.simulate},\"isHydratedViewOnly\":${entry.isHydratedViewOnly},\"state\":${entry.state.toJson()}}".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }

        // ── Submit and run a pipeline ────────────────────────────────────────
        httpServer.createContext("/api/run") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0)
                ex.responseBody.close()
                return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val dotSource = jsonField(body, "dotSource")
                val fileName = jsonField(body, "fileName").ifEmpty { "pipeline.dot" }
                val simulate = jsonBool(body, "simulate")
                val autoApprove = jsonBool(body, "autoApprove", default = true)
                val originalPrompt = jsonField(body, "originalPrompt")

                if (dotSource.isEmpty()) {
                    val err = """{"error":"dotSource is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                val options = RunOptions(simulate = simulate, autoApprove = autoApprove)
                val id = PipelineRunner.submit(dotSource, fileName, options, registry, store, originalPrompt) {
                    broadcastUpdate()
                }

                println("[attractor] Pipeline submitted: $id ($fileName)")
                val resp = """{"id":"$id"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Re-run an existing pipeline ──────────────────────────────────────
        httpServer.createContext("/api/rerun") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                if (registry.get(id) == null) {
                    val err = """{"error":"Pipeline not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                PipelineRunner.resubmit(id, registry, store) { broadcastUpdate() }
                println("[attractor] Pipeline re-run (in-place): $id")
                val resp = """{"id":"$id"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Cancel a running pipeline ─────────────────────────────────────────
        httpServer.createContext("/api/cancel") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val cancelled = registry.cancel(id)
                println("[attractor] Pipeline cancel requested: $id (cancelled=$cancelled)")
                val resp = """{"cancelled":$cancelled}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Pause a running pipeline ──────────────────────────────────────────
        httpServer.createContext("/api/pause") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val paused = registry.pause(id)
                println("[attractor] Pipeline pause requested: $id (paused=$paused)")
                val resp = """{"paused":$paused}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Resume a paused pipeline ──────────────────────────────────────────
        httpServer.createContext("/api/resume") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val entry = registry.get(id)
                if (entry == null) {
                    val err = """{"error":"Pipeline not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                if (entry.state.status.get() != "paused") {
                    val err = """{"error":"Pipeline is not paused"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                PipelineRunner.resumePipeline(id, registry, store) { broadcastUpdate() }
                println("[attractor] Pipeline resume requested: $id")
                val resp = """{"id":"$id"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Archive a pipeline run ────────────────────────────────────────────
        httpServer.createContext("/api/archive") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val archived = registry.archive(id)
                if (archived) broadcastUpdate()
                val resp = """{"archived":$archived}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Unarchive a pipeline run ──────────────────────────────────────────
        httpServer.createContext("/api/unarchive") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val unarchived = registry.unarchive(id)
                if (unarchived) broadcastUpdate()
                val resp = """{"unarchived":$unarchived}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Render DOT source to SVG via local graphviz ──────────────────────
        httpServer.createContext("/api/render") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val reqBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val dotSource = jsonField(reqBody, "dotSource")
                if (dotSource.isEmpty()) {
                    val err = """{"error":"dotSource is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong()); ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                val proc = ProcessBuilder("dot", "-Tsvg", "-Gbgcolor=transparent")
                    .redirectErrorStream(true)
                    .start()
                // Write DOT to stdin in a thread to avoid blocking on large output
                val renderDot = sanitizeDotForRender(dotSource)
                val writer = Thread {
                    try { proc.outputStream.use { it.write(renderDot.toByteArray(Charsets.UTF_8)) } }
                    catch (_: Exception) {}
                }
                writer.start()
                val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                writer.join()
                val exitCode = proc.waitFor()
                val respBytes = if (exitCode == 0)
                    """{"svg":${js(output)}}""".toByteArray()
                else
                    """{"error":${js(output.take(400))}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, respBytes.size.toLong())
                ex.responseBody.use { it.write(respBytes) }
            } catch (e: java.io.IOException) {
                val msg = if (e.message?.contains("error=2") == true || e.message?.contains("No such file") == true)
                    "Graphviz not installed. Run: brew install graphviz"
                else e.message ?: "IO error"
                val err = """{"error":${js(msg)}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, err.size.toLong()); ex.responseBody.use { it.write(err) }
            } catch (e: Exception) {
                val err = """{"error":${js(e.message ?: "unknown")}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong()); ex.responseBody.use { it.write(err) }
            }
        }

        // ── Permanently delete a pipeline run and its artifacts ──────────────
        httpServer.createContext("/api/delete") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val id = jsonField(body, "id")
                val entry = registry.get(id)
                if (entry == null) {
                    val err = """{"error":"Pipeline not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                val status = entry.state.status.get()
                if (status == "running" || status == "paused") {
                    val err = """{"error":"Cannot delete a running or paused pipeline"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                val (deleted, logsRoot) = registry.delete(id)
                if (deleted && logsRoot.isNotBlank()) {
                    runCatching { java.io.File(logsRoot).deleteRecursively() }
                }
                broadcastUpdate()
                println("[attractor] Pipeline deleted: $id (logsRoot=${logsRoot.ifBlank { "none" }})")
                val resp = """{"deleted":$deleted}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Generate DOT from natural language ───────────────────────────────
        httpServer.createContext("/api/generate") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0)
                ex.responseBody.close()
                return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val prompt = jsonField(body, "prompt")
                if (prompt.isEmpty()) {
                    val err = """{"error":"prompt is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                val dotSource = dotGenerator.generate(prompt)
                val resp = """{"dotSource":${js(dotSource)}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Streaming generate DOT (SSE deltas) ─────────────────────────────
        httpServer.createContext("/api/generate/stream") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val prompt = jsonField(body, "prompt")
                if (prompt.isEmpty()) {
                    val err = """{"error":"prompt is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.responseHeaders.add("X-Accel-Buffering", "no")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                try {
                    val cleanDot = dotGenerator.generateStream(prompt) { delta ->
                        val line = "data: {\"delta\":${js(delta)}}\n\n"
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val done = "data: {\"done\":true,\"dotSource\":${js(cleanDot)}}\n\n"
                    out.write(done.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: Exception) {
                    val errLine = "data: {\"error\":${js(e.message ?: "unknown")}}\n\n"
                    runCatching { out.write(errLine.toByteArray(Charsets.UTF_8)); out.flush() }
                } finally {
                    runCatching { out.close() }
                }
            } catch (e: Exception) {
                runCatching {
                    val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(500, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                }
            }
        }

        // ── Fix broken DOT via LLM (SSE) ────────────────────────────────────
        httpServer.createContext("/api/fix-dot") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val dotSource = jsonField(body, "dotSource")
                val error    = jsonField(body, "error")
                if (dotSource.isEmpty()) {
                    val err = """{"error":"dotSource is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.responseHeaders.add("X-Accel-Buffering", "no")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                try {
                    val cleanDot = dotGenerator.fixStream(dotSource, error) { delta ->
                        val line = "data: {\"delta\":${js(delta)}}\n\n"
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val done = "data: {\"done\":true,\"dotSource\":${js(cleanDot)}}\n\n"
                    out.write(done.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: Exception) {
                    val errLine = "data: {\"error\":${js(e.message ?: "unknown")}}\n\n"
                    runCatching { out.write(errLine.toByteArray(Charsets.UTF_8)); out.flush() }
                } finally {
                    runCatching { out.close() }
                }
            } catch (e: Exception) {
                runCatching {
                    val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(500, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                }
            }
        }

        // ── Iterate: modify existing DOT via LLM (SSE) ──────────────────────
        httpServer.createContext("/api/iterate/stream") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val baseDot = jsonField(body, "baseDot")
                val changes = jsonField(body, "changes")
                if (baseDot.isEmpty()) {
                    val err = """{"error":"baseDot is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                if (changes.isEmpty()) {
                    val err = """{"error":"changes is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.responseHeaders.add("Cache-Control", "no-cache")
                ex.responseHeaders.add("X-Accel-Buffering", "no")
                ex.sendResponseHeaders(200, 0)
                val out = ex.responseBody
                try {
                    val cleanDot = dotGenerator.iterateStream(baseDot, changes) { delta ->
                        val line = "data: {\"delta\":${js(delta)}}\n\n"
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val done = "data: {\"done\":true,\"dotSource\":${js(cleanDot)}}\n\n"
                    out.write(done.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (e: Exception) {
                    val errLine = "data: {\"error\":${js(e.message ?: "unknown")}}\n\n"
                    runCatching { out.write(errLine.toByteArray(Charsets.UTF_8)); out.flush() }
                } finally {
                    runCatching { out.close() }
                }
            } catch (e: Exception) {
                runCatching {
                    val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(500, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                }
            }
        }

        // ── Iterate: create a NEW run inheriting the source run's family ─────
        httpServer.createContext("/api/iterate") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val sourceId = jsonField(body, "id")
                val dotSource = jsonField(body, "dotSource")
                val originalPrompt = jsonField(body, "originalPrompt")
                val sourceEntry = registry.get(sourceId)
                if (sourceEntry == null) {
                    val err = """{"error":"Pipeline not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                if (dotSource.isEmpty()) {
                    val err = """{"error":"dotSource is required"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                if (sourceEntry.state.status.get() == "running") {
                    val err = """{"error":"Pipeline is currently running"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(409, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                // Determine family: inherit from source; for legacy runs (blank familyId), bootstrap from sourceId
                val familyId = sourceEntry.familyId.ifBlank { sourceId }
                // Backfill legacy source run's familyId so it becomes findable in the family
                if (sourceEntry.familyId.isBlank()) {
                    store.updateFamilyId(sourceId, sourceId)
                    registry.setFamilyId(sourceId, sourceId)
                }
                // Create a new run for this iteration — source run is preserved untouched
                val newId = PipelineRunner.submit(
                    dotSource = dotSource,
                    fileName = sourceEntry.fileName,
                    options = sourceEntry.options,
                    registry = registry,
                    store = store,
                    originalPrompt = originalPrompt,
                    familyId = familyId,
                    displayNameOverride = sourceEntry.displayName
                ) { broadcastUpdate() }
                println("[attractor] Pipeline iterated (new run): $sourceId -> $newId (family: $familyId)")
                val resp = """{"newId":${js(newId)}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Pipeline family: all runs sharing the same familyId ──────────────
        // GET /api/pipeline-family?id={runId}
        httpServer.createContext("/api/pipeline-family") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val runId = params["id"] ?: ""
            val entry = registry.get(runId)
            if (runId.isBlank() || entry == null) {
                val msg = """{"error":"Pipeline not found"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val fid = entry.familyId
            if (fid.isBlank()) {
                val body = """{"members":[]}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                return@createContext
            }
            val members = store.getByFamilyId(fid).take(100)
            val sb = StringBuilder()
            sb.append("""{"members":[""")
            members.forEachIndexed { i, m ->
                if (i > 0) sb.append(",")
                val vn = i + 1
                sb.append("{\"id\":${js(m.id)},\"displayName\":${js(m.displayName)},\"createdAt\":${m.createdAt},\"status\":${js(m.status)},\"dotSource\":${js(m.dotSource)},\"originalPrompt\":${js(m.originalPrompt)},\"versionNum\":$vn}")
            }
            sb.append("]}")
            val body = sb.toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        // ── Run artifact file listing ─────────────────────────────────────────
        // GET /api/run-artifacts?id={runId}
        httpServer.createContext("/api/run-artifacts") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val runId = params["id"] ?: ""
            val entry = registry.get(runId)
            if (runId.isBlank() || entry == null) {
                val msg = """{"error":"Pipeline not found"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val logsRootDir = if (entry.logsRoot.isNotBlank()) java.io.File(entry.logsRoot) else null
            if (logsRootDir == null || !logsRootDir.isDirectory) {
                val body = """{"files":[],"truncated":false}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                return@createContext
            }
            val textExts = setOf("log", "txt", "md", "json", "dot", "kt", "py", "js", "sh", "yaml", "yml")
            val files = mutableListOf<Triple<String, Long, Boolean>>()
            logsRootDir.walkTopDown().filter { it.isFile }.take(501).forEach { f ->
                val rel = f.relativeTo(logsRootDir).path
                val isText = f.extension.lowercase() in textExts
                files.add(Triple(rel, f.length(), isText))
            }
            val truncated = files.size > 500
            val trimmed = if (truncated) files.take(500) else files
            val sb = StringBuilder()
            sb.append("""{"files":[""")
            trimmed.forEachIndexed { i, (rel, size, isText) ->
                if (i > 0) sb.append(",")
                sb.append("{\"path\":${js(rel)},\"size\":$size,\"isText\":$isText}")
            }
            sb.append("""],"truncated":$truncated}""")
            val body = sb.toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        // ── Serve a single artifact file ──────────────────────────────────────
        // GET /api/run-artifact-file?id={runId}&path={relPath}
        httpServer.createContext("/api/run-artifact-file") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val runId = params["id"] ?: ""
            val relPath = params["path"] ?: ""
            val entry = registry.get(runId)
            if (runId.isBlank() || entry == null || entry.logsRoot.isBlank()) {
                ex.sendResponseHeaders(404, 0); ex.responseBody.close(); return@createContext
            }
            val logsRootCanon = java.io.File(entry.logsRoot).canonicalFile
            val targetFile = java.io.File(logsRootCanon, relPath).canonicalFile
            // Security: strict path-prefix check to prevent traversal
            if (!targetFile.path.startsWith(logsRootCanon.path + java.io.File.separator) &&
                targetFile.path != logsRootCanon.path) {
                ex.sendResponseHeaders(403, 0); ex.responseBody.close(); return@createContext
            }
            if (!targetFile.exists() || !targetFile.isFile) {
                ex.sendResponseHeaders(404, 0); ex.responseBody.close(); return@createContext
            }
            val textExts = setOf("log", "txt", "md", "json", "dot", "kt", "py", "js", "sh", "yaml", "yml")
            val contentType = if (targetFile.extension.lowercase() in textExts)
                "text/plain; charset=utf-8" else "application/octet-stream"
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(200, targetFile.length())
            ex.responseBody.use { out -> targetFile.inputStream().use { inp -> inp.copyTo(out) } }
        }

        // ── Stage live log ───────────────────────────────────────────────────
        // GET /api/stage-log?id={pipelineId}&stage={nodeId}
        httpServer.createContext("/api/stage-log") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val pipelineId = params["id"] ?: ""
            val stageNodeId = params["stage"] ?: ""
            val entry = registry.get(pipelineId)
            val logsRoot = entry?.logsRoot ?: ""
            val logFile = if (logsRoot.isNotBlank() && stageNodeId.isNotBlank())
                java.io.File(logsRoot, "$stageNodeId/live.log") else null
            val content = when {
                pipelineId.isBlank() || stageNodeId.isBlank() -> "(missing id or stage parameter)"
                logFile == null || !logFile.exists() -> "(no log yet — stage may not have started)"
                else -> logFile.readText()
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        // ── Download artifacts as ZIP ────────────────────────────────────────
        // GET /api/download-artifacts?id={pipelineId}
        // Zips the entire logsRoot directory (workspace files + stage planning docs).
        httpServer.createContext("/api/download-artifacts") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val pipelineId = params["id"] ?: ""
            val entry = registry.get(pipelineId)
            val logsRoot = entry?.logsRoot ?: ""
            if (pipelineId.isBlank() || entry == null || logsRoot.isBlank()) {
                val msg = "No artifacts available".toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val artifactRoot = java.io.File(logsRoot)
            val files = artifactRoot.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) {
                val msg = "No files found for this pipeline run".toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val safeName = (entry.state.pipelineName.get().ifEmpty { entry.fileName })
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"artifacts-$safeName.zip\"")
            ex.sendResponseHeaders(200, 0)
            try {
                java.util.zip.ZipOutputStream(ex.responseBody).use { zip ->
                    files.forEach { file ->
                        val entryName = artifactRoot.toPath().relativize(file.toPath()).toString()
                        zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } catch (_: Exception) { /* client disconnected */ }
        }

        // ── Export a pipeline run as a self-contained ZIP ────────────────────
        // GET /api/export-run?id={pipelineId}
        httpServer.createContext("/api/export-run") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val query = ex.requestURI.query ?: ""
            val params = query.split("&").associate { p ->
                val kv = p.split("=", limit = 2)
                (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val pipelineId = params["id"] ?: ""
            val entry = registry.get(pipelineId)
            if (pipelineId.isBlank() || entry == null) {
                val msg = """{"error":"Pipeline not found"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val run = store.getById(pipelineId)
            if (run == null) {
                val msg = """{"error":"Run not found in database"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val metaJson = buildString {
                append("{")
                append("\"fileName\":${js(run.fileName)},")
                append("\"dotSource\":${js(run.dotSource)},")
                append("\"originalPrompt\":${js(run.originalPrompt)},")
                append("\"familyId\":${js(run.familyId)},")
                append("\"simulate\":${run.simulate},")
                append("\"autoApprove\":${run.autoApprove}")
                append("}")
            }
            val safeName = (entry.state.pipelineName.get().ifEmpty { entry.fileName })
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val idSuffix = pipelineId.takeLast(8)
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"pipeline-$safeName-$idSuffix.zip\"")
            ex.sendResponseHeaders(200, 0)
            try {
                java.util.zip.ZipOutputStream(ex.responseBody).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("pipeline-meta.json"))
                    zip.write(metaJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            } catch (_: Exception) { /* client disconnected */ }
        }

        // ── Import a pipeline run from an exported ZIP ───────────────────────
        // POST /api/import-run?onConflict=overwrite|skip
        httpServer.createContext("/api/import-run") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1); return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val metaText = try {
                    val tempDir = java.nio.file.Files.createTempDirectory("attractor-import-").toFile()
                    var text: String? = null
                    try {
                        java.util.zip.ZipInputStream(ex.requestBody).use { zis ->
                            var zipEntry = zis.nextEntry
                            while (zipEntry != null) {
                                if (zipEntry.name.trimStart('/') == "pipeline-meta.json") {
                                    text = zis.readBytes().toString(Charsets.UTF_8)
                                }
                                zis.closeEntry()
                                zipEntry = zis.nextEntry
                            }
                        }
                    } finally {
                        tempDir.deleteRecursively()
                    }
                    text
                } catch (e: Exception) {
                    val err = """{"error":"Invalid or corrupt zip: ${e.message?.take(120)?.replace("\"", "'")}"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                if (metaText == null) {
                    val err = """{"error":"pipeline-meta.json not found in zip"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                val fileName  = jsonField(metaText, "fileName")
                val dotSource = jsonField(metaText, "dotSource")
                if (fileName.isBlank() || dotSource.isBlank()) {
                    val err = """{"error":"Missing required field(s) in pipeline-meta.json: fileName, dotSource"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                val simulate       = jsonBool(metaText, "simulate")
                val autoApprove    = jsonBool(metaText, "autoApprove", default = true)
                val originalPrompt = jsonField(metaText, "originalPrompt")
                val importFamilyId = jsonField(metaText, "familyId")

                val newId = PipelineRunner.submit(
                    dotSource      = dotSource,
                    fileName       = fileName,
                    options        = RunOptions(simulate = simulate, autoApprove = autoApprove),
                    registry       = registry,
                    store          = store,
                    originalPrompt = originalPrompt,
                    familyId       = importFamilyId,
                    onUpdate       = { broadcastUpdate() }
                )
                println("[attractor] Pipeline imported and started: $newId ($fileName)")
                val resp = """{"status":"started","id":${js(newId)}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.take(200)?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── Get settings ─────────────────────────────────────────────────────
        httpServer.createContext("/api/settings") { ex ->
            if (ex.requestMethod == "GET") {
                val fireworks  = store.getSetting("fireworks_enabled") ?: "true"
                val execMode   = store.getSetting("execution_mode") ?: "api"
                val anthEnabled = store.getSetting("provider_anthropic_enabled") ?: "true"
                val oaiEnabled  = store.getSetting("provider_openai_enabled") ?: "true"
                val gemEnabled  = store.getSetting("provider_gemini_enabled") ?: "true"
                val anthCmd    = store.getSetting("cli_anthropic_command") ?: "claude -p {prompt}"
                val oaiCmd     = store.getSetting("cli_openai_command") ?: "codex -p {prompt}"
                val gemCmd     = store.getSetting("cli_gemini_command") ?: "gemini -p {prompt}"
                val body = """{
                    "fireworks_enabled":$fireworks,
                    "execution_mode":${js(execMode)},
                    "provider_anthropic_enabled":$anthEnabled,
                    "provider_openai_enabled":$oaiEnabled,
                    "provider_gemini_enabled":$gemEnabled,
                    "cli_anthropic_command":${js(anthCmd)},
                    "cli_openai_command":${js(oaiCmd)},
                    "cli_gemini_command":${js(gemCmd)}
                }""".trimIndent().replace("\n", "").replace("    ", "").toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                ex.sendResponseHeaders(405, 0)
                ex.responseBody.close()
            }
        }

        // ── CLI binary detection ──────────────────────────────────────────────
        httpServer.createContext("/api/settings/cli-status") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            fun detectBinary(template: String): Boolean {
                val binary = template.trim().split("\\s+".toRegex()).firstOrNull() ?: return false
                return try {
                    val proc = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
                    proc.waitFor() == 0
                } catch (_: Exception) { false }
            }
            val anthCmd = store.getSetting("cli_anthropic_command") ?: "claude -p {prompt}"
            val oaiCmd  = store.getSetting("cli_openai_command") ?: "codex -p {prompt}"
            val gemCmd  = store.getSetting("cli_gemini_command") ?: "gemini -p {prompt}"
            val body = """{
                "anthropic":${detectBinary(anthCmd)},
                "openai":${detectBinary(oaiCmd)},
                "gemini":${detectBinary(gemCmd)}
            }""".trimIndent().replace("\n", "").replace("    ", "").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        // ── Update a setting ──────────────────────────────────────────────────
        httpServer.createContext("/api/settings/update") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("Access-Control-Allow-Methods", "POST, OPTIONS")
            ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
            if (ex.requestMethod == "OPTIONS") {
                ex.sendResponseHeaders(204, -1)
                return@createContext
            }
            if (ex.requestMethod != "POST") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            try {
                val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val key   = jsonField(body, "key")
                val value = jsonField(body, "value")
                if (key.isNotEmpty()) store.setSetting(key, value)
                val resp = """{"ok":true}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, resp.size.toLong())
                ex.responseBody.use { it.write(resp) }
            } catch (e: Exception) {
                val err = """{"error":"${e.message?.replace("\"", "'")}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(500, err.size.toLong())
                ex.responseBody.use { it.write(err) }
            }
        }

        // ── SSE live event stream ────────────────────────────────────────────
        httpServer.createContext("/events") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.responseHeaders.add("Connection", "keep-alive")
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.add("X-Accel-Buffering", "no")
            ex.sendResponseHeaders(200, 0)
            val client = SseClient(ex)
            sseClients.add(client)
            client.offer(allPipelinesJson())          // initial snapshot
            try {
                while (client.alive) {
                    val json = client.queue.poll(2, TimeUnit.SECONDS)
                    if (json != null) {
                        val bytes = "data: $json\n\n: \n\n".toByteArray(Charsets.UTF_8)
                        ex.responseBody.write(bytes)
                        ex.responseBody.flush()
                    } else {
                        // timeout — send SSE comment as keep-alive
                        val bytes = ": heartbeat\n\n".toByteArray(Charsets.UTF_8)
                        ex.responseBody.write(bytes)
                        ex.responseBody.flush()
                    }
                }
            } catch (_: Exception) {
                // client disconnected — normal
            } finally {
                client.alive = false
                sseClients.remove(client)
                runCatching { ex.responseBody.close() }
            }
        }

        val restApi = RestApiRouter(registry, store, { broadcastUpdate() }, { allPipelinesJson() }, restSseClients)
        httpServer.createContext("/api/v1/") { ex -> restApi.handle(ex) }
    }

    fun broadcastUpdate() {
        val json = allPipelinesJson()
        val dead = mutableListOf<SseClient>()
        for (client in sseClients) {
            if (!client.alive) dead.add(client) else client.offer(json)
        }
        sseClients.removeAll(dead.toSet())
        val deadRest = mutableListOf<RestApiRouter.RestSseClient>()
        for (client in restSseClients) {
            if (!client.alive) deadRest.add(client) else client.offer(json)
        }
        restSseClients.removeAll(deadRest.toSet())
    }

    fun start() {
        httpServer.start()
        println("[attractor] Web interface: http://localhost:$port/")
    }

    fun stop() {
        for (client in sseClients) client.alive = false
        httpServer.stop(0)
    }

    private fun allPipelinesJson(): String {
        val sb = StringBuilder()
        sb.append("{\"pipelines\":[")
        val all = registry.getAll()
        all.forEachIndexed { i, entry ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":${js(entry.id)},\"fileName\":${js(entry.fileName)},\"dotSource\":${js(entry.dotSource)},\"originalPrompt\":${js(entry.originalPrompt)},\"familyId\":${js(entry.familyId)},\"simulate\":${entry.options.simulate},\"isHydratedViewOnly\":${entry.isHydratedViewOnly},\"state\":${entry.state.toJson()}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun respond(ex: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private val requestJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Extract a string field from a JSON object. Uses a proper parser to avoid regex stack overflow on large values. */
    private fun jsonField(json: String, key: String): String = try {
        requestJson.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.contentOrNull ?: ""
    } catch (_: Exception) { "" }

    /** Extract a boolean field from a JSON object. */
    private fun jsonBool(json: String, key: String, default: Boolean = false): Boolean = try {
        requestJson.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.booleanOrNull ?: default
    } catch (_: Exception) { default }

    /** Extract a long field from a JSON object. */
    private fun jsonLong(json: String, key: String, default: Long = 0L): Long = try {
        requestJson.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content?.toLongOrNull() ?: default
    } catch (_: Exception) { default }

    /**
     * Strip pipeline-semantics attributes (prompt, goal, goal_gate) from DOT before passing
     * to graphviz. These attributes can contain multi-line text, unescaped quotes, or values
     * exceeding graphviz's 16 KB string buffer — none of which affect visual rendering.
     *
     * Two passes:
     *  1. Well-formed quoted values  — matched and removed normally.
     *  2. Unterminated quoted values — produced when the LLM response is truncated mid-string;
     *     the regex looks for the attribute opening and consumes everything to end-of-input.
     */
    private fun sanitizeDotForRender(dot: String): String {
        // Pass 1: well-formed value — stops at the closing unescaped "
        var result = dot.replace(dotSanitizePass1, "")
        // Pass 2: unterminated value — consumes from the opening " to end of string
        result = result.replace(dotSanitizePass2, "")
        // Pass 3: clean up leading commas left when the removed attribute was first in the list
        // e.g. [goal="...", label="x"] → [, label="x"] → [label="x"]
        result = result.replace(dotSanitizePass3, "[")
        return result
    }

    private fun js(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""

    private fun dashboardHtml(): String = """<!DOCTYPE html>
<html lang="en">
<head>
<script>(function(){var t=localStorage.getItem('attractor-theme')||'dark';document.documentElement.setAttribute('data-theme',t);document.documentElement.style.colorScheme=t;})();</script>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Corey's Attractor</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Figtree:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
[data-theme="dark"] {
  --bg: #0d1117;
  --surface: #161b22;
  --surface-raised: #1c2128;
  --surface-muted: #21262d;
  --border: #30363d;
  --text: #c9d1d9;
  --text-strong: #f0f6fc;
  --text-muted: #8b949e;
  --text-faint: #6e7681;
  --text-dim: #484f58;
  --accent: #388bfd;
  --code-bg: #0d1117;
  --code-text: #c9d1d9;
  --dot-bg: #0d1117;
  --dot-text: #79c0ff;
  --graph-bg: #ffffff;
  --badge-idle-bg: #21262d;
  --badge-idle-fg: #6e7681;
  --badge-running-bg: #3d1f00;
  --badge-running-fg: #f78166;
  --badge-completed-bg: #0f2d16;
  --badge-completed-fg: #3fb950;
  --badge-failed-bg: #300d0d;
  --badge-failed-fg: #f85149;
  --badge-cancelled-bg: #2d2d2d;
  --badge-cancelled-fg: #8b949e;
  --badge-paused-bg: #2d2008;
  --badge-paused-fg: #e3b341;
}
[data-theme="light"] {
  --bg: #f4f4f6;
  --surface: #ffffff;
  --surface-raised: #ededf0;
  --surface-muted: #e4e4e8;
  --border: #d1d1d8;
  --text: #27272a;
  --text-strong: #18181b;
  --text-muted: #52525b;
  --text-faint: #71717a;
  --text-dim: #a1a1aa;
  --accent: #4f46e5;
  --code-bg: #f0f0f3;
  --code-text: #27272a;
  --dot-bg: #f4f4f6;
  --dot-text: #3730a3;
  --graph-bg: #ffffff;
  --badge-idle-bg: #e9e9ec;
  --badge-idle-fg: #71717a;
  --badge-running-bg: #fef3c7;
  --badge-running-fg: #92400e;
  --badge-completed-bg: #dcfce7;
  --badge-completed-fg: #166534;
  --badge-failed-bg: #fee2e2;
  --badge-failed-fg: #991b1b;
  --badge-cancelled-bg: #e9e9ec;
  --badge-cancelled-fg: #52525b;
  --badge-paused-bg: #ede9fe;
  --badge-paused-fg: #5b21b6;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Figtree', 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; }
button { font-variant-emoji: text; }

/* Header */
header { background: var(--surface); border-bottom: 1px solid var(--border); padding: 12px 20px; display: flex; align-items: center; gap: 12px; }
header h1 { font-size: 1.05rem; font-weight: 600; color: var(--text-strong); flex: 1; }
.conn-indicator { display: flex; align-items: center; gap: 5px; font-size: 0.72rem; color: var(--text-faint); }
.conn-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-faint); }
.conn-dot.live { background: #3fb950; animation: pulse 2s infinite; }

/* Tab bar */
.tab-bar { background: var(--surface); border-bottom: 1px solid var(--border); padding: 0 20px; display: flex; align-items: flex-end; gap: 2px; overflow-x: auto; min-height: 40px; }
.tab { padding: 8px 14px; border-radius: 6px 6px 0 0; font-size: 0.78rem; cursor: pointer; border: 1px solid transparent; border-bottom: none; color: var(--text-muted); white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.tab:hover { color: var(--text); background: var(--surface-raised); }
.tab.active { background: var(--bg); border-color: var(--border); color: var(--text-strong); }
.tab-empty { padding: 10px 16px; font-size: 0.78rem; color: var(--text-faint); font-style: italic; align-self: center; }

/* Badge */
.badge { display: inline-flex; align-items: center; gap: 5px; padding: 2px 7px; border-radius: 10px; font-size: 0.65rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
.badge-idle      { background: var(--badge-idle-bg); color: var(--badge-idle-fg); }
.badge-running   { background: var(--badge-running-bg); color: var(--badge-running-fg); }
.badge-completed { background: var(--badge-completed-bg); color: var(--badge-completed-fg); }
.badge-failed    { background: var(--badge-failed-bg); color: var(--badge-failed-fg); }
.badge-cancelled { background: var(--badge-cancelled-bg); color: var(--badge-cancelled-fg); }
.badge-paused    { background: var(--badge-paused-bg); color: var(--badge-paused-fg); }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
.pulse { animation: pulse 1.4s infinite; }

/* Main content */
main { max-width: 1200px; margin: 0 auto; padding: 20px; display: grid; grid-template-columns: 1fr 340px; gap: 20px; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 18px; }
.card h2 { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; margin-bottom: 14px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 4px; }
.pipeline-title { font-size: 1.4rem; font-weight: 700; color: var(--text-strong); word-break: break-word; }
.pipeline-meta  { font-size: 0.78rem; color: var(--text-faint); margin-bottom: 0; }
.action-bar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 0; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); margin: 14px 0 18px; min-height: 42px; }
.action-bar-primary { display: flex; gap: 8px; align-items: center; }
.action-bar-secondary { display: flex; gap: 8px; align-items: center; }
.action-bar button { line-height: 1; }
.pipeline-desc-block { background: var(--surface-muted); border: 1px solid var(--border); border-radius: 6px; padding: 10px 14px; margin-bottom: 10px; }
.pipeline-desc-label { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; margin-bottom: 6px; }
.pipeline-desc-block #pipelineDescText { font-size: 0.8rem; color: var(--text); line-height: 1.6; white-space: pre-wrap; word-break: break-word; cursor: default; user-select: text; }
.stage-list { display: flex; flex-direction: column; gap: 6px; }
.stage { display: flex; flex-direction: column; align-items: stretch; gap: 0; border-radius: 6px; background: var(--surface-raised); border: 1px solid var(--border); }
.stage.running   { border-color: #f78166; }
.stage.completed { border-color: #238636; }
.stage.failed    { border-color: #da3633; }
.stage.retrying   { border-color: #d29922; }
.stage.diagnosing { border-color: #e3b341; }
.stage.repairing  { border-color: #58a6ff; }
.stage-row { display: flex; align-items: center; gap: 10px; padding: 9px 13px; }
.stage-icon { font-size: 0.95rem; width: 20px; text-align: center; flex-shrink: 0; }
.stage-name { flex: 1; font-size: 0.88rem; }
.stage-dur  { font-size: 0.72rem; color: var(--text-faint); white-space: nowrap; }
.stage-err  { font-size: 0.72rem; color: #f85149; white-space: nowrap; max-width: 120px; overflow: hidden; text-overflow: ellipsis; }
.stage-err-btn { background: none; border: none; padding: 0 0 0 6px; cursor: pointer; color: #f85149; font-size: 0.88rem; line-height: 1; vertical-align: middle; opacity: 0.85; }
.stage-err-btn:hover { opacity: 1; color: #ff7b72; }
.stage-log-btn { background: var(--surface-muted); border: 1px solid var(--border); color: var(--text-muted); padding: 2px 8px; border-radius: 4px; font-size: 0.68rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
.stage-log-btn:hover { background: var(--border); color: var(--text); }
.stage-log-btn.active { background: #1c2d3e; border-color: #388bfd66; color: #79c0ff; }
[data-theme="light"] .stage-log-btn.active { background: #e1f0f5; border-color: #2dadca88; color: #006876; }
.stage-log-inline { border-top: 1px solid var(--surface-muted); }
.stage-log-pre { margin: 0; background: var(--code-bg); color: var(--code-text); font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.72rem; line-height: 1.6; max-height: 320px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; padding: 10px 13px; border-radius: 0 0 5px 5px; }
.log-panel  { font-family: 'Consolas', 'Cascadia Code', 'Courier New', monospace; font-size: 0.72rem; line-height: 1.7; flex: 1; min-height: 0; overflow-y: auto; background: var(--code-bg); border-radius: 4px; padding: 12px; }
.log-line   { color: var(--text-faint); border-bottom: 1px solid rgba(33,38,45,0.2); padding: 1px 0; word-break: break-all; }
.log-line:last-child { color: var(--text); border-bottom: none; }
.empty-note { color: var(--text-faint); font-size: 0.82rem; padding: 4px 0; }
.no-pipeline { grid-column: 1 / -1; text-align: center; padding: 60px 20px; color: var(--text-faint); }
.no-pipeline h2 { font-size: 1.1rem; margin-bottom: 8px; color: var(--text-muted); }
.no-pipeline p { font-size: 0.85rem; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.7); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal-overlay.hidden { display: none; pointer-events: none; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 28px 32px; width: 420px; max-width: 95vw; }
.modal h2 { font-size: 1rem; color: var(--text-strong); margin-bottom: 20px; }
.checkbox-row { display: flex; align-items: center; gap: 8px; font-size: 0.82rem; color: var(--text); cursor: pointer; }
.checkbox-row input { accent-color: var(--accent); width: 14px; height: 14px; }
.modal-actions { display: flex; gap: 10px; margin-top: 22px; justify-content: flex-end; }
.btn-primary { background: #238636; color: #fff; border: none; padding: 8px 18px; border-radius: 6px; font-size: 0.84rem; font-weight: 600; cursor: pointer; }
.btn-primary:hover { background: #2ea043; }
.btn-primary:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-cancel { background: var(--surface-muted); color: var(--text); border: 1px solid var(--border); padding: 8px 18px; border-radius: 6px; font-size: 0.84rem; cursor: pointer; }
.btn-cancel:hover { background: var(--border); }
.btn-rerun { background: #1f6feb; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-rerun:hover { background: #388bfd; }
.btn-rerun:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-cancel-run { background: #da3633; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-cancel-run:hover { background: #f85149; }
.btn-cancel-run:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-pause-run  { background: #9a6700; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-pause-run:hover { background: #b07d00; }
.btn-pause-run:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-resume-run { background: #1f6feb; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-resume-run:hover { background: #388bfd; }
.btn-resume-run:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-download { background: #238636; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-download:hover { background: #2ea043; }
.btn-download:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-archive { background: #6e7681; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-archive:hover { background: #8b949e; }
.btn-archive:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-unarchive { background: #1f6feb; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-unarchive:hover { background: #388bfd; }
.btn-unarchive:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
.btn-delete { background: #da3633; color: #fff; border: none; padding: 5px 13px; border-radius: 6px; font-size: 0.78rem; font-weight: 600; cursor: pointer; }
.btn-delete:hover { background: #f85149; }
.btn-delete:disabled { background: var(--surface-muted); color: var(--text-faint); cursor: not-allowed; }
/* Dashboard view */
.dashboard-layout { max-width: 1200px; margin: 0 auto; padding: 20px; }
.dash-stats { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 24px; }
@media (max-width: 800px) { .dash-stats { grid-template-columns: repeat(3, 1fr); } }
.dash-stat { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px 18px; }
.dash-stat-label { font-size: 0.65rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 8px; }
.dash-stat-value { font-size: 1.9rem; font-weight: 700; color: var(--text-strong); font-variant-numeric: tabular-nums; line-height: 1; display: flex; align-items: center; gap: 6px; }
.dash-stat-value.s-running { color: #f78166; }
.dash-stat-value.s-completed { color: #3fb950; }
.dash-stat-value.s-failed { color: #f85149; }
.dash-running-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #f78166; animation: pulse 1.4s infinite; flex-shrink: 0; }
.dashboard-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.dash-card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; cursor: pointer; overflow: hidden; display: flex; flex-direction: column; transition: border-color 0.12s, box-shadow 0.12s; }
.dash-card:hover { border-color: #388bfd; box-shadow: 0 0 0 1px #388bfd22; }
.dash-card-top { height: 3px; flex-shrink: 0; }
.dash-card-top.s-running { background: #f78166; }
.dash-card-top.s-completed { background: #238636; }
.dash-card-top.s-failed { background: #da3633; }
.dash-card-top.s-cancelled { background: #6e7681; }
.dash-card-top.s-paused { background: #d29922; }
.dash-card-top.s-idle { background: var(--border); }
.dash-card-body { padding: 14px 16px; flex: 1; display: flex; flex-direction: column; gap: 10px; }
.dash-card-title-row { display: flex; align-items: center; gap: 8px; min-width: 0; }
.dash-card-name { font-size: 0.92rem; font-weight: 600; color: var(--text-strong); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.dash-sim-badge { font-size: 0.6rem; background: #1c2d3e; color: #79c0ff; padding: 2px 6px; border-radius: 3px; white-space: nowrap; flex-shrink: 0; font-weight: 600; letter-spacing: 0.04em; }
.dash-status-row { display: flex; align-items: center; justify-content: space-between; }
.dash-elapsed { font-family: 'Consolas','Cascadia Code',monospace; font-size: 0.88rem; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--text-muted); }
.dash-elapsed.s-running { color: #e3b341; }
.dash-elapsed.s-paused { color: #d29922; }
.dash-elapsed.s-failed { color: #f85149; }
.dash-progress-track { height: 4px; background: var(--border); border-radius: 2px; overflow: hidden; }
.dash-progress-fill { height: 100%; border-radius: 2px; transition: width 0.4s; }
.dash-progress-fill.s-running { background: repeating-linear-gradient(90deg,#f78166 0,#f78166 14px,#c85140 14px,#c85140 22px); background-size: 22px 100%; animation: progress-stripe 0.5s linear infinite; }
.dash-progress-fill.s-completed { background: #238636; }
.dash-progress-fill.s-failed { background: #da3633; }
.dash-progress-fill.s-cancelled { background: #6e7681; }
.dash-progress-fill.s-paused { background: #d29922; }
@keyframes progress-stripe { to { background-position: 22px 0; } }
.dash-stage-label { font-size: 0.78rem; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-height: 1.1em; }
.dash-card-footer { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; border-top: 1px solid var(--border); }
.dash-stage-count { font-size: 0.7rem; color: var(--text-faint); }
.dash-started { font-size: 0.7rem; color: var(--text-faint); }
.dash-empty { text-align: center; padding: 60px 24px; color: var(--text-faint); }
.dash-empty-icon { font-size: 2.5rem; opacity: 0.2; margin-bottom: 14px; }
.dash-empty p { margin-top: 8px; font-size: 0.88rem; }
/* Archived view */
.archived-layout { max-width: 900px; margin: 0 auto; padding: 20px; }
.archived-table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.archived-table th { text-align: left; padding: 8px 12px; font-size: 0.72rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; border-bottom: 1px solid var(--border); }
.archived-table td { padding: 10px 12px; border-bottom: 1px solid var(--surface-muted); vertical-align: middle; }
.archived-table tr:hover td { background: var(--surface); }
.archived-empty { color: var(--text-faint); text-align: center; padding: 40px 0; }
.tab.archived-tab { opacity: 0.55; }
.tab.archived-tab.active { opacity: 1.0; }

/* Version History accordion */
.version-history { margin-top: 14px; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.vh-header { cursor: pointer; display: flex; align-items: center; gap: 6px; padding: 7px 12px; background: var(--surface-muted); color: var(--text-muted); font-size: 0.8rem; font-weight: 600; user-select: none; border: none; width: 100%; text-align: left; }
.vh-header:hover { background: var(--surface-raised); color: var(--text); }
.vh-list { max-height: 220px; overflow-y: auto; border-top: 1px solid var(--border); }
.vh-row { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-bottom: 1px solid var(--border); font-size: 0.78rem; }
.vh-row:last-child { border-bottom: none; }
.vh-row.vh-current { background: var(--surface-raised); }
.vh-ver { font-family: 'Consolas','Cascadia Code','Courier New',monospace; background: var(--surface-muted); border-radius: 4px; padding: 1px 5px; font-size: 0.72rem; color: var(--text-strong); flex-shrink: 0; }
.vh-row-name { flex: 1; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.vh-row-ts { font-size: 0.72rem; color: var(--text-faint); flex-shrink: 0; white-space: nowrap; }
.vh-row-actions { display: flex; gap: 4px; flex-shrink: 0; }
.btn-vh { font-size: 0.72rem; padding: 2px 7px; border-radius: 4px; border: 1px solid var(--border); background: var(--surface-muted); color: var(--text-dim); cursor: pointer; white-space: nowrap; }
.btn-vh:hover { border-color: var(--accent); color: var(--text); }
.version-nav { display: flex; align-items: center; gap: 8px; padding: 4px 0 8px 0; margin-bottom: 4px; }
.vn-arrow { background: none; border: 1px solid var(--border); border-radius: 4px; color: var(--text-muted); font-size: 1rem; padding: 1px 8px; cursor: pointer; line-height: 1; }
.vn-arrow:hover:not(:disabled) { border-color: var(--accent); color: var(--text); }
.vn-arrow:disabled { opacity: 0.3; cursor: default; }
.vn-label { font-size: 0.8rem; color: var(--text-muted); font-family: monospace; }
.view-err { font-size: 0.75rem; color: var(--danger, #f85149); margin-left: 8px; opacity: 1; transition: opacity 0.3s; }

/* Artifact modal */
.artifact-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.75); z-index: 200; display: none; align-items: center; justify-content: center; }
.artifact-overlay.open { display: flex; }
.artifact-dialog { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; width: 820px; max-width: 92vw; height: 540px; max-height: 85vh; display: flex; flex-direction: column; }
.artifact-dialog-hdr { padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; flex-shrink: 0; }
.artifact-dialog-hdr span { font-weight: 600; color: var(--text-strong); font-size: 0.88rem; }
.artifact-dialog-close { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 1.1rem; padding: 2px 6px; border-radius: 4px; }
.artifact-dialog-close:hover { background: var(--surface-muted); color: var(--text); }
.artifact-body { display: flex; flex: 1; overflow: hidden; }
.artifact-files { width: 220px; overflow-y: auto; border-right: 1px solid var(--border); padding: 6px 0; flex-shrink: 0; }
.artifact-file { padding: 4px 12px; cursor: pointer; font-size: 0.78rem; color: var(--text-muted); font-family: 'Consolas','Cascadia Code','Courier New',monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artifact-file.active,.artifact-file:hover { background: var(--surface-muted); color: var(--text); }
.artifact-view { flex: 1; overflow-y: auto; padding: 12px 16px; font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.75rem; white-space: pre-wrap; color: var(--text); background: var(--bg); word-break: break-all; }

.error-detail-modal { width: 600px; }
.error-detail-pre { background: var(--code-bg); border: 1px solid var(--border); border-radius: 6px; padding: 12px 14px; color: #f85149; font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.75rem; line-height: 1.6; max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; margin: 0; }

/* Navigation */
.nav-btn { background: transparent; border: none; color: var(--text-muted); padding: 5px 13px; border-radius: 6px; font-size: 0.82rem; font-weight: 600; cursor: pointer; }
.nav-btn:hover { background: var(--surface-muted); color: var(--text); }
.nav-btn.active { background: var(--surface-muted); color: var(--text-strong); }

/* Theme toggle button */
.theme-toggle-btn { background: transparent; border: none; color: var(--text-muted); padding: 5px 9px; border-radius: 6px; font-size: 1.05rem; cursor: pointer; line-height: 1; }
.theme-toggle-btn:hover { background: var(--surface-muted); }

/* Create view */
.create-layout { max-width: 1440px; margin: 0 auto; padding: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 20px; height: calc(100vh - 45px); box-sizing: border-box; }
.create-col { display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.create-col h2 { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; flex-shrink: 0; }
.create-section { flex: 1; display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.create-section .nl-textarea { flex: 1; height: 0; min-height: 80px; resize: none; }
.create-section .dot-textarea { flex: 1; height: 0; min-height: 80px; resize: none; }
.nl-textarea { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; color: var(--text); font-family: 'Figtree', 'Segoe UI', system-ui, sans-serif; font-size: 0.88rem; line-height: 1.65; padding: 14px 16px; outline: none; }
.nl-textarea:focus { border-color: var(--accent); }
.nl-textarea::placeholder { color: var(--text-dim); }
.dot-textarea { background: var(--dot-bg); border: 1px solid var(--border); border-radius: 8px; color: var(--dot-text); font-family: 'Consolas', 'Cascadia Code', 'Courier New', monospace; font-size: 0.78rem; line-height: 1.6; padding: 14px 16px; outline: none; tab-size: 4; }
.dot-textarea:focus { border-color: var(--accent); }
.dot-textarea::placeholder { color: var(--text-dim); }
.dot-header-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-shrink: 0; }
.create-options-row { display: flex; gap: 18px; flex-shrink: 0; }
.run-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-shrink: 0; }
.gen-hint { font-size: 0.72rem; color: var(--text-faint); }
.gen-status { font-size: 0.75rem; color: var(--text-faint); display: inline-flex; align-items: center; gap: 6px; }
.gen-status.loading { color: #d29922; }
.gen-status.loading::before {
  content: '';
  display: inline-block;
  width: 11px;
  height: 11px;
  border: 2px solid #d29922;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}
.gen-status.error   { color: #f85149; }
.gen-status.ok      { color: #3fb950; }
@keyframes spin { to { transform: rotate(360deg); } }
.run-btn { background: #1f6feb; color: #fff; border: none; padding: 9px 22px; border-radius: 6px; font-size: 0.86rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
.run-btn:hover:not(:disabled) { background: #388bfd; }
.run-btn:disabled { background: var(--surface-muted); color: var(--text-dim); cursor: not-allowed; }
.btn-cancel-iterate { background: transparent; color: var(--text-muted); border: 1px solid var(--border); padding: 9px 16px; border-radius: 6px; font-size: 0.86rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
.btn-cancel-iterate:hover { background: var(--surface-muted); color: var(--text); border-color: var(--text-muted); }

/* DOT preview tabs */
.preview-tabs { display: flex; gap: 2px; }
.preview-tab { background: transparent; border: none; color: var(--text-muted); padding: 4px 11px; border-radius: 5px; font-size: 0.75rem; font-weight: 600; cursor: pointer; }
.preview-tab:hover { background: var(--surface-muted); color: var(--text); }
.preview-tab.active { background: var(--surface-muted); color: var(--text-strong); }
.graph-preview { flex: 1; min-height: 200px; background: var(--graph-bg); border: 1px solid var(--border); border-radius: 8px; overflow: auto; display: flex; align-items: flex-start; }
.graph-content { padding: 20px; width: 100%; display: flex; align-items: center; justify-content: center; min-height: 200px; box-sizing: border-box; }
.graph-content svg { max-width: 100%; height: auto; display: block; }
.graph-placeholder { color: #aaa; font-size: 0.82rem; text-align: center; }
.graph-loading { color: #888; font-size: 0.82rem; text-align: center; }
.graph-error { color: #c0392b; font-size: 0.78rem; font-family: monospace; white-space: pre-wrap; word-break: break-word; text-align: center; max-width: 480px; }

/* Right-panel tabs (Log / Graph) */
.right-panel-tabs { display: flex; gap: 2px; margin-bottom: 14px; border-bottom: 1px solid var(--border); padding-bottom: 10px; align-items: center; }
.right-tab-btn { background: transparent; border: none; color: var(--text-muted); padding: 4px 12px; border-radius: 5px; font-size: 0.75rem; font-weight: 600; cursor: pointer; text-transform: uppercase; letter-spacing: 0.07em; }
.right-tab-btn:hover { background: var(--surface-muted); color: var(--text); }
.right-tab-btn.active { background: var(--surface-muted); color: var(--text-strong); }

/* Pipeline graph panel (monitor view) */
.pipeline-graph-view { overflow: auto; flex: 1; min-height: 0; background: var(--graph-bg); border-radius: 4px; }
#rightPanel { display: flex; flex-direction: column; }
.pipeline-graph-view > div { width: 100%; }
.pipeline-graph-view svg { display: block; width: 100%; height: auto; }
.pipeline-graph-placeholder { color: var(--text-faint); font-size: 0.78rem; padding: 20px; text-align: center; width: 100%; }
.pipeline-graph-error { color: #f85149; font-size: 0.75rem; padding: 10px; font-family: monospace; white-space: pre-wrap; }

/* Zoom controls */
.graph-toolbar { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.graph-toolbar-label { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; flex: 1; }
.graph-zoom-btn { background: var(--surface-muted); border: 1px solid var(--border); color: var(--text); width: 24px; height: 24px; border-radius: 4px; font-size: 0.9rem; cursor: pointer; padding: 0; display: inline-flex; align-items: center; justify-content: center; }
.graph-zoom-btn:hover { background: var(--border); color: var(--text-strong); }
.graph-zoom-label { font-size: 0.72rem; color: var(--text-muted); min-width: 36px; text-align: center; font-variant-numeric: tabular-nums; }

/* Settings view */
.setting-row { display:flex; align-items:center; justify-content:space-between; padding: 12px 0; border-bottom: 1px solid var(--border); }
.setting-row:last-child { border-bottom: none; }
.setting-info { flex: 1; }
.setting-label { font-size: 0.95rem; color: var(--text-strong); font-weight: 500; }
.setting-desc { font-size: 0.8rem; color: var(--text-muted); margin-top: 2px; }
.toggle-switch { position:relative; display:inline-block; width:44px; height:24px; flex-shrink:0; }
.toggle-switch input { opacity:0; width:0; height:0; }
.toggle-slider { position:absolute; cursor:pointer; inset:0; background: var(--border); border-radius:24px; transition:.2s; }
.toggle-slider:before { content:''; position:absolute; width:18px; height:18px; left:3px; bottom:3px; background:#fff; border-radius:50%; transition:.2s; }
input:checked + .toggle-slider { background:#238636; }
input:checked + .toggle-slider:before { transform:translateX(20px); }

/* Light theme depth & style overrides */
[data-theme="light"] header { box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
[data-theme="light"] .tab-bar { box-shadow: 0 1px 3px rgba(0,0,0,0.05); }
[data-theme="light"] .card { box-shadow: 0 1px 3px rgba(0,0,0,0.07), 0 1px 2px rgba(0,0,0,0.04); border-color: var(--border); }
[data-theme="light"] .modal { box-shadow: 0 12px 40px rgba(0,0,0,0.14), 0 2px 8px rgba(0,0,0,0.08); }
[data-theme="light"] .modal-overlay { background: rgba(15,15,20,0.40); }
[data-theme="light"] .dash-card { box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
[data-theme="light"] .dash-card:hover { border-color: #4f46e5; box-shadow: 0 2px 10px rgba(79,70,229,0.14); }
[data-theme="light"] .stage.running   { border-color: #ea580c; }
[data-theme="light"] .stage.completed { border-color: #16a34a; }
[data-theme="light"] .stage.failed    { border-color: #dc2626; }
[data-theme="light"] .stage.retrying   { border-color: #d97706; }
[data-theme="light"] .stage.diagnosing { border-color: #d97706; }
[data-theme="light"] .stage.repairing  { border-color: #1f6feb; }
[data-theme="light"] .stage-log-btn.active { background: #ede9fe; border-color: #4f46e566; color: #4338ca; }
[data-theme="light"] .log-line { border-bottom-color: rgba(0,0,0,0.05); }
[data-theme="light"] .conn-dot.live { background: #16a34a; }
[data-theme="light"] .pipeline-graph-error { color: #dc2626; }
[data-theme="light"] .dash-elapsed { color: #7c3aed; }
</style>
</head>
<body>

<header>
  <h1>&#9889; Corey's Attractor</h1>
  <nav style="display:flex;gap:3px;">
    <button class="nav-btn active" id="navMonitor" onclick="showView('monitor')">Monitor</button>
    <button class="nav-btn" id="navCreate" onclick="showView('create')">&#10024; Create</button>
    <button class="nav-btn" id="navArchived" onclick="showView('archived')">&#128193; Archived</button>
    <button class="nav-btn" onclick="openImportModal()">&#128229; Import</button>
    <button class="nav-btn" id="navSettings" onclick="showView('settings')">&#9881; Settings</button>
  </nav>
  <button class="theme-toggle-btn" id="themeToggle" onclick="toggleTheme()" aria-label="Switch to light theme" title="Switch to light theme">&#9728;</button>
  <div class="conn-indicator">
    <span id="connDot" class="conn-dot"></span>
    <span id="connLabel">connecting</span>
  </div>
</header>

<div id="viewMonitor">
<div class="tab-bar" id="tabBar">
  <div class="tab-empty" id="tabEmpty">No pipelines yet &mdash; use Create to start a pipeline</div>
</div>

<main id="mainContent">
  <div class="no-pipeline" id="noPipeline">
    <h2>No pipeline selected</h2>
    <p>Use <strong>Create</strong> to generate and run a pipeline.</p>
  </div>
</main>
</div>

<!-- Create view -->
<div id="viewCreate" style="display:none;">
  <div class="create-layout">
    <div class="create-col">
      <div class="create-section">
        <h2>Describe your pipeline</h2>
        <textarea id="nlInput" class="nl-textarea"
          placeholder="e.g. &quot;Write comprehensive unit tests for a Python web app, run them, fix any failures, then generate a coverage report&quot;&#10;&#10;Describe what you want in plain English. The pipeline will be generated automatically as you type."></textarea>
        <div class="create-options-row">
          <label class="checkbox-row"><input type="checkbox" id="createSimulate"> Simulate (no LLM calls)</label>
          <label class="checkbox-row"><input type="checkbox" id="createAutoApprove" checked> Auto-approve gates</label>
        </div>
      </div>
      <div class="create-section">
        <div class="dot-header-row">
          <h2>Generated DOT</h2>
          <span class="gen-status" id="genStatus">Start typing to generate&hellip;</span>
        </div>
        <textarea id="dotPreview" class="dot-textarea" spellcheck="false"
          placeholder="Generated pipeline DOT source will appear here&hellip;"></textarea>
        <div class="run-row">
          <span class="gen-hint" id="genHint">You can edit the DOT source before running.</span>
          <div style="display:flex;gap:8px;align-items:center;">
            <button class="btn-cancel-iterate" id="cancelIterateBtn" style="display:none;" onclick="cancelIterate()">&#x2715;&ensp;Cancel</button>
            <button class="run-btn" id="runBtn" disabled onclick="runGenerated()">&#9654;&ensp;Run Pipeline</button>
          </div>
        </div>
      </div>
    </div>
    <div class="create-col">
      <div class="graph-toolbar">
        <span class="graph-toolbar-label">Graph Preview</span>
        <button class="graph-zoom-btn" title="Zoom out (or Ctrl+scroll)" onclick="zoomCreate(-1)">&#x2212;</button>
        <span class="graph-zoom-label" id="createZoomLabel">100%</span>
        <button class="graph-zoom-btn" title="Zoom in (or Ctrl+scroll)" onclick="zoomCreate(1)">+</button>
        <button class="graph-zoom-btn" title="Reset zoom" onclick="resetCreateZoom()">&#x21BA;</button>
      </div>
      <div id="graphPreview" class="graph-preview">
        <div id="graphContent" class="graph-content">
          <div class="graph-placeholder">Generate a pipeline first to see the graph.</div>
        </div>
      </div>
    </div>
  </div>
</div>

<!-- Cancel iterate confirmation modal -->
<div class="modal-overlay hidden" id="cancelIterateModal" onclick="closeCancelIterateModal()">
  <div class="modal" onclick="event.stopPropagation()">
    <h2>Discard Changes?</h2>
    <p style="color:#c9d1d9;margin:12px 0 20px;line-height:1.5;">Your modifications will be lost. Are you sure you want to cancel and return to the monitor?</p>
    <div class="modal-actions">
      <button class="btn-cancel" onclick="closeCancelIterateModal()">Keep Editing</button>
      <button class="btn-delete" onclick="confirmCancelIterate()">Discard Changes</button>
    </div>
  </div>
</div>

<!-- Archived view -->
<div id="viewArchived" style="display:none;">
  <div class="archived-layout">
    <div class="card">
      <h2>Archived Runs</h2>
      <div id="archivedContent"><div class="archived-empty">No archived runs.</div></div>
    </div>
  </div>
</div>

<!-- Settings view -->
<div id="viewSettings" style="display:none; padding: 24px; max-width: 640px; margin: 0 auto;">
  <div class="card">
    <h2>Settings</h2>

    <!-- Fireworks -->
    <div class="setting-row">
      <div class="setting-info">
        <div class="setting-label">Fireworks</div>
        <div class="setting-desc">Show fireworks animation when a pipeline completes</div>
      </div>
      <label class="toggle-switch">
        <input type="checkbox" id="settingFireworks" onchange="saveSetting('fireworks_enabled', this.checked)">
        <span class="toggle-slider"></span>
      </label>
    </div>

    <!-- Execution Mode -->
    <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:10px;">
      <div class="setting-info">
        <div class="setting-label">Execution Mode</div>
        <div class="setting-desc">How AI providers are invoked for generation and pipeline stages</div>
      </div>
      <div style="display:flex; gap:8px;">
        <button id="modeApiBtn" onclick="setExecutionMode('api')" style="padding:6px 18px; border-radius:6px; border:1px solid var(--border); cursor:pointer; font-size:0.9rem; background:var(--surface-muted); color:var(--text);">Direct API</button>
        <button id="modeCliBtn" onclick="setExecutionMode('cli')" style="padding:6px 18px; border-radius:6px; border:1px solid var(--border); cursor:pointer; font-size:0.9rem; background:var(--surface-muted); color:var(--text);">CLI subprocess</button>
      </div>
    </div>

    <!-- Providers -->
    <div style="padding: 12px 0 4px 0;">
      <div class="setting-label" style="margin-bottom:8px;">Providers</div>
      <div class="setting-desc" style="margin-bottom:12px;">Enable or disable individual AI providers. CLI command templates support <code>{prompt}</code> substitution.</div>

      <!-- Anthropic -->
      <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:6px; padding:10px 0;">
        <div style="display:flex; align-items:center; justify-content:space-between; width:100%;">
          <div style="display:flex; align-items:center; gap:10px;">
            <label class="toggle-switch" style="margin:0;">
              <input type="checkbox" id="settingAnthropicEnabled" onchange="saveSetting('provider_anthropic_enabled', this.checked)">
              <span class="toggle-slider"></span>
            </label>
            <span class="setting-label">Anthropic (claude)</span>
          </div>
          <span id="cliBadgeAnthropic" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdAnthropic" type="text" placeholder="claude -p {prompt}"
          style="display:none; width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
          onblur="saveSetting('cli_anthropic_command', this.value)">
      </div>

      <!-- OpenAI -->
      <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:6px; padding:10px 0;">
        <div style="display:flex; align-items:center; justify-content:space-between; width:100%;">
          <div style="display:flex; align-items:center; gap:10px;">
            <label class="toggle-switch" style="margin:0;">
              <input type="checkbox" id="settingOpenAIEnabled" onchange="saveSetting('provider_openai_enabled', this.checked)">
              <span class="toggle-slider"></span>
            </label>
            <span class="setting-label">OpenAI (codex)</span>
          </div>
          <span id="cliBadgeOpenAI" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdOpenAI" type="text" placeholder="codex -p {prompt}"
          style="display:none; width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
          onblur="saveSetting('cli_openai_command', this.value)">
      </div>

      <!-- Gemini -->
      <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:6px; padding:10px 0;">
        <div style="display:flex; align-items:center; justify-content:space-between; width:100%;">
          <div style="display:flex; align-items:center; gap:10px;">
            <label class="toggle-switch" style="margin:0;">
              <input type="checkbox" id="settingGeminiEnabled" onchange="saveSetting('provider_gemini_enabled', this.checked)">
              <span class="toggle-slider"></span>
            </label>
            <span class="setting-label">Google (gemini)</span>
          </div>
          <span id="cliBadgeGemini" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdGemini" type="text" placeholder="gemini -p {prompt}"
          style="display:none; width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
          onblur="saveSetting('cli_gemini_command', this.value)">
      </div>
    </div>
  </div>
</div>

<!-- Error detail modal -->
<div class="modal-overlay hidden" id="errorDetailModal">
  <div class="modal error-detail-modal">
    <h2 id="errorDetailTitle">Stage Error</h2>
    <pre class="error-detail-pre" id="errorDetailPre"></pre>
    <div class="modal-actions">
      <button class="btn-cancel" onclick="closeErrorModal()">Close</button>
    </div>
  </div>
</div>

<!-- Delete confirmation modal -->
<div class="modal-overlay hidden" id="deleteModal" onclick="closeDeleteModal()">
  <div class="modal" onclick="event.stopPropagation()">
    <h2>Delete Pipeline Run?</h2>
    <p style="color:#c9d1d9;margin:12px 0 6px;line-height:1.5;">Pipeline: <strong id="deleteModalName" style="color:#f0f6fc;"></strong></p>
    <p style="color:#8b949e;margin:0 0 20px;font-size:0.82rem;line-height:1.5;">This will permanently delete the run record, all logs, and all artifacts from disk. This cannot be undone.</p>
    <div class="modal-actions">
      <button class="btn-cancel" onclick="closeDeleteModal()">Cancel</button>
      <button class="btn-delete" id="deleteConfirmBtn" onclick="executeDelete()">&#10005;&ensp;Delete Permanently</button>
    </div>
  </div>
</div>

<!-- Import run modal -->
<div class="modal-overlay hidden" id="importModal" onclick="closeImportModal()">
  <div class="modal" onclick="event.stopPropagation()">
    <h2>&#128229;&ensp;Import Pipeline</h2>
    <p style="color:#8b949e;font-size:0.82rem;margin-bottom:16px;line-height:1.5;">Select an exported pipeline ZIP to start a new run from its definition.</p>
    <div class="field">
      <label style="display:block;font-size:0.8rem;color:#8b949e;margin-bottom:6px;">Pipeline ZIP file</label>
      <input type="file" id="importZipInput" accept=".zip" style="color:#c9d1d9;font-size:0.82rem;width:100%;" onchange="onImportFileChange()">
    </div>
    <div id="importMsg" style="margin-top:10px;font-size:0.8rem;min-height:1.2em;"></div>
    <div class="modal-actions" style="margin-top:16px;">
      <button class="btn-cancel" onclick="closeImportModal()">Cancel</button>
      <button class="btn-primary" id="importSubmitBtn" onclick="submitImport()" disabled>Start Run</button>
    </div>
  </div>
</div>

<script>
var DASHBOARD_TAB_ID = '__dashboard__';
var pipelines = {};     // id -> {id, fileName, state}
var _storedTab = localStorage.getItem('attractor-selected-tab');
var selectedId = _storedTab || DASHBOARD_TAB_ID;
var panelBuiltFor = null;  // which id the main panel DOM was built for
var logRenderedCount = {}; // id -> number of log lines already appended to DOM
var elapsedTimer = null;   // interval that ticks the elapsed counter every second
var dashboardTimer = null; // interval that ticks elapsed counters on the dashboard
var stageErrors = {};      // stageIndex -> full error string for the selected pipeline
var stageLogTimer = null;   // interval for polling stage live log
var stageLogNodeId = null;  // nodeId of the currently-expanded inline stage log
var stageLogContent = '';   // last fetched log text for the expanded stage
var graphSigFor = {};       // id -> last stage-status signature used to render the graph
var graphRenderGen = {};    // id -> render generation; stale in-flight responses are discarded

var ICONS = { running: '&#9889;', completed: '&#10003;', failed: '&#10007;', retrying: '&#8635;', diagnosing: '&#128269;', repairing: '&#9874;', pending: '&middot;' };

// ── Utility ────────────────────────────────────────────────────────────────
function esc(s) {
  return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

function elapsed(state) {
  if (!state.startedAt) return '';
  var ms = (state.finishedAt || Date.now()) - state.startedAt;
  var sec = Math.floor(ms / 1000);
  if (sec < 60) return sec + 's';
  return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
}

function fmtDur(ms) {
  if (ms < 1000) return ms + 'ms';
  var sec = Math.round(ms / 1000);
  if (sec < 60) return sec + 's';
  return Math.floor(sec / 60) + 'm ' + (sec % 60) + 's';
}

// ── Dashboard ────────────────────────────────────────────────────────────────
function startDashboardTimer() {
  dashboardTimer = setInterval(tickDashboardElapsed, 1000);
}

function stopDashboardTimer() {
  clearInterval(dashboardTimer); dashboardTimer = null;
}

function getDashStageLabel(p) {
  var st = p.state || {};
  var status = st.status || '';
  if (status === 'paused') return 'Paused';
  if (status === 'completed') return 'Done';
  if (status === 'failed') return 'Failed';
  if (status === 'cancelled') return 'Cancelled';
  if (status === 'running') {
    var stages = st.stages || [];
    for (var i = 0; i < stages.length; i++) {
      if (stages[i].status === 'running') return esc(stages[i].name);
    }
    return 'Waiting\u2026';
  }
  return '';
}

function getDashStageIcon(status) {
  if (status === 'running') return '\u26a1';
  if (status === 'paused') return '\u23f8';
  if (status === 'completed') return '\u2713';
  if (status === 'failed') return '\u2717';
  if (status === 'cancelled') return '\u2014';
  return '';
}

function getDashElapsed(st) {
  if (!st.startedAt) return '\u2014';
  if (st.status === 'running' || st.status === 'paused') return elapsed(st);
  var total = (st.finishedAt || Date.now()) - st.startedAt;
  return fmtDur(total);
}

function tickDashboardElapsed() {
  var spans = document.querySelectorAll('.dash-elapsed[data-pipeline-id]');
  for (var i = 0; i < spans.length; i++) {
    var id = spans[i].getAttribute('data-pipeline-id');
    var p = pipelines[id];
    if (!p || !p.state) continue;
    var st = p.state;
    if (st.status !== 'running' && st.status !== 'paused') continue;
    spans[i].textContent = elapsed(st);
  }
}

function renderDashboard() {
  stopDashboardTimer();
  var ids = Object.keys(pipelines);
  var visibleIds = ids.filter(function(id) {
    return !(pipelines[id].state && pipelines[id].state.archived);
  });

  // Compute stats
  var totalCount = visibleIds.length;
  var runningCount = 0, completedCount = 0, failedCount = 0, cancelledCount = 0;
  for (var i = 0; i < visibleIds.length; i++) {
    var s = (pipelines[visibleIds[i]].state || {}).status || '';
    if (s === 'running') runningCount++;
    else if (s === 'completed') completedCount++;
    else if (s === 'failed') failedCount++;
    else if (s === 'cancelled') cancelledCount++;
  }
  var terminal = completedCount + failedCount + cancelledCount;
  var successRate = terminal > 0 ? Math.round(completedCount / terminal * 100) : null;

  // Sort: running → paused → failed → cancelled → completed → idle; within same status newest first
  var statusOrder = { running: 0, paused: 1, failed: 2, cancelled: 3, completed: 4, idle: 5 };
  visibleIds.sort(function(a, b) {
    var sa = (pipelines[a].state || {}).status || 'idle';
    var sb = (pipelines[b].state || {}).status || 'idle';
    var oa = statusOrder[sa] !== undefined ? statusOrder[sa] : 5;
    var ob = statusOrder[sb] !== undefined ? statusOrder[sb] : 5;
    if (oa !== ob) return oa - ob;
    var ta = (pipelines[a].state || {}).startedAt || 0;
    var tb = (pipelines[b].state || {}).startedAt || 0;
    return tb - ta;
  });

  var mainEl = document.getElementById('mainContent');
  var runningDot = runningCount > 0 ? '<span class="dash-running-dot"></span>' : '';
  var statsHtml = '<div class="dash-stats">'
    + '<div class="dash-stat"><div class="dash-stat-label">Total Runs</div><div class="dash-stat-value">' + totalCount + '</div></div>'
    + '<div class="dash-stat"><div class="dash-stat-label">Running</div><div class="dash-stat-value s-running">' + runningCount + runningDot + '</div></div>'
    + '<div class="dash-stat"><div class="dash-stat-label">Completed</div><div class="dash-stat-value s-completed">' + completedCount + '</div></div>'
    + '<div class="dash-stat"><div class="dash-stat-label">Failed</div><div class="dash-stat-value s-failed">' + failedCount + '</div></div>'
    + '<div class="dash-stat"><div class="dash-stat-label">Success Rate</div><div class="dash-stat-value">' + (successRate !== null ? successRate + '%' : '\u2014') + '</div></div>'
    + '</div>';

  if (totalCount === 0) {
    mainEl.innerHTML = '<div class="dashboard-layout">' + statsHtml
      + '<div class="dash-empty"><div class="dash-empty-icon">\u26a1</div>'
      + '<div style="font-size:1rem;font-weight:600;color:var(--text-muted);">No pipelines yet</div>'
      + '<p>Use <strong>Create</strong> to generate and run a pipeline.</p></div></div>';
    return;
  }

  var cards = '';
  for (var i = 0; i < visibleIds.length; i++) {
    var id = visibleIds[i];
    var p = pipelines[id];
    var st = p.state || {};
    var status = st.status || 'idle';
    var sc = 's-' + status;
    var name = esc(st.pipeline || p.fileName || 'pipeline');

    // Stage progress
    var stages = st.stages || [];
    var totalStages = stages.length;
    var doneStages = 0;
    for (var j = 0; j < stages.length; j++) { if (stages[j].status === 'completed') doneStages++; }
    var pct = totalStages > 0 ? Math.min(100, Math.round(doneStages / totalStages * 100))
            : (status === 'completed' ? 100 : 0);

    // Stage label
    var stageLabel = '';
    if (status === 'running') {
      for (var j = 0; j < stages.length; j++) {
        if (stages[j].status === 'running') { stageLabel = '\u25b6\ufe0e\u2002' + esc(stages[j].name); break; }
      }
      if (!stageLabel) stageLabel = 'Waiting\u2026';
    } else if (status === 'paused')    { stageLabel = '\u23f8\ufe0e\u2002Paused'; }
      else if (status === 'completed') { stageLabel = '\u2713\u2002Completed'; }
      else if (status === 'failed')    { stageLabel = '\u2717\u2002Failed'; }
      else if (status === 'cancelled') { stageLabel = '\u2014\u2002Cancelled'; }

    // Elapsed
    var elapsedStr = getDashElapsed(st);
    var elapsedClass = 'dash-elapsed ' + sc;

    // Footer: started time
    var startedStr = '';
    if (st.startedAt) {
      var d = new Date(st.startedAt);
      var today = new Date();
      startedStr = d.toDateString() !== today.toDateString()
        ? d.toLocaleDateString([], {month:'short', day:'numeric'}) + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'})
        : d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    }
    var stageCountStr = totalStages > 0 ? doneStages + '\u2009/\u2009' + totalStages + ' stages' : '';
    var simBadge = p.simulate ? '<span class="dash-sim-badge">SIM</span>' : '';

    cards += '<div class="dash-card" onclick="selectTab(\'' + id + '\')">'
      + '<div class="dash-card-top ' + sc + '"></div>'
      + '<div class="dash-card-body">'
      +   '<div class="dash-card-title-row"><span class="dash-card-name">' + name + '</span>' + simBadge + '</div>'
      +   '<div class="dash-status-row">'
      +     '<span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
      +     '<span class="' + elapsedClass + '" id="dash-elapsed-' + id + '" data-pipeline-id="' + id + '">' + elapsedStr + '</span>'
      +   '</div>'
      +   '<div class="dash-progress-track"><div class="dash-progress-fill ' + sc + '" style="width:' + pct + '%"></div></div>'
      +   '<div class="dash-stage-label">' + stageLabel + '</div>'
      + '</div>'
      + '<div class="dash-card-footer">'
      +   '<span class="dash-stage-count">' + stageCountStr + '</span>'
      +   '<span class="dash-started">' + startedStr + '</span>'
      + '</div>'
      + '</div>';
  }

  mainEl.innerHTML = '<div class="dashboard-layout">' + statsHtml + '<div class="dashboard-grid">' + cards + '</div></div>';
  startDashboardTimer();
}

// ── Tabs ────────────────────────────────────────────────────────────────────
function renderTabs() {
  var bar = document.getElementById('tabBar');
  var ids = Object.keys(pipelines);
  // Show non-archived runs, plus the selected run even if archived
  var visibleIds = ids.filter(function(id) {
    return !pipelines[id].state.archived || id === selectedId;
  });
  var dashActive = selectedId === DASHBOARD_TAB_ID ? ' active' : '';
  var html = '<div class="tab dash-tab' + dashActive + '" onclick="selectTab(DASHBOARD_TAB_ID)">&#128202; Dashboard</div>';
  for (var i = 0; i < visibleIds.length; i++) {
    var id = visibleIds[i];
    var p = pipelines[id];
    var st = p.state;
    var name = (st && st.pipeline) ? st.pipeline : p.fileName;
    var status = (st && st.status) ? st.status : 'idle';
    var active = id === selectedId ? ' active' : '';
    var archivedCls = (st && st.archived) ? ' archived-tab' : '';
    html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
         +  esc(name)
         +  ' <span class="badge badge-' + esc(status) + '">' + esc(status) + '</span>'
         +  '</div>';
  }
  bar.innerHTML = html;
}

function selectTab(id) {
  localStorage.setItem('attractor-selected-tab', id);
  stopDashboardTimer();
  selectedId = id;
  if (id === DASHBOARD_TAB_ID) {
    panelBuiltFor = null;
    renderTabs();
    renderMain();
    return;
  }
  panelBuiltFor = null;     // force panel rebuild for the new tab
  graphSigFor[id] = null;   // force graph re-render on tab switch
  graphRenderGen[id] = (graphRenderGen[id] || 0) + 1; // invalidate any in-flight renders
  renderTabs();
  renderMain();
}

// ── Main panel ──────────────────────────────────────────────────────────────

// Build the static DOM scaffold for a pipeline tab — called once per tab selection.
function buildPanel(id) {
  stopDashboardTimer();
  clearInterval(elapsedTimer); elapsedTimer = null;
  clearInterval(stageLogTimer); stageLogTimer = null;
  stageLogNodeId = null; stageLogContent = '';
  panelBuiltFor = id;
  logRenderedCount[id] = 0;
  document.getElementById('mainContent').innerHTML =
    '<div id="panelLeft">'
    + '<div class="panel-header">'
    +   '<div class="pipeline-title" id="pTitle"></div>'
    +   '<span class="badge badge-idle" id="pStatusBadge">idle</span>'
    + '</div>'
    + '<div class="version-nav" id="versionNav" style="display:none;">'
    +   '<button class="vn-arrow" id="vnPrev" onclick="navVersion(-1)">&#8249;</button>'
    +   '<span class="vn-label" id="vnLabel"></span>'
    +   '<button class="vn-arrow" id="vnNext" onclick="navVersion(+1)">&#8250;</button>'
    + '</div>'
    + '<div class="pipeline-meta" id="pMeta"></div>'
    + '<div class="action-bar" id="actionBar">'
    +   '<div class="action-bar-primary">'
    +     '<button class="btn-cancel-run" id="cancelBtn" style="display:none;" onclick="cancelPipeline()">&#9632;&ensp;Cancel</button>'
    +     '<button class="btn-pause-run"  id="pauseBtn"  style="display:none;" onclick="pausePipeline()">&#9646;&#9646;&ensp;Pause</button>'
    +     '<button class="btn-resume-run" id="resumeBtn" style="display:none;" onclick="resumePipeline()">&#9654;&ensp;Resume</button>'
    +     '<button class="btn-rerun" id="rerunBtn" style="display:none;" onclick="rerunPipeline()">&#8635;&ensp;Re-run</button>'
    +     '<button class="btn-rerun" id="iterateBtn" style="display:none;" onclick="iteratePipeline()">&#9998;&ensp;Iterate</button>'
    +   '</div>'
    +   '<div class="action-bar-secondary">'
    +     '<button class="btn-download" id="downloadBtn" style="display:none;" onclick="downloadArtifacts()">&#8659;&ensp;Download Artifacts</button>'
    +     '<button class="btn-download" id="failureReportBtn" style="display:none;" onclick="openArtifacts(selectedId,\'Failure Report\')">&#128203;&ensp;View Failure Report</button>'
    +     '<button class="btn-download" id="exportBtn" style="display:none;" onclick="exportRun()">&#8599;&ensp;Export</button>'
    +     '<button class="btn-archive" id="archiveBtn" style="display:none;" onclick="archivePipeline()">&#8595;&ensp;Archive</button>'
    +     '<button class="btn-unarchive" id="unarchiveBtn" style="display:none;" onclick="unarchivePipeline()">&#8593;&ensp;Unarchive</button>'
    +     '<button class="btn-delete" id="deleteBtn" style="display:none;" onclick="showDeleteConfirm(selectedId)">&#10005;&ensp;Delete</button>'
    +   '</div>'
    + '</div>'
    + '<div id="pipelineDesc" style="display:none;" class="pipeline-desc-block"><div class="pipeline-desc-label">Prompt</div><div id="pipelineDescText"></div></div>'
    + '<div class="card"><h2>Stages</h2><div class="stage-list" id="stageList"><div class="empty-note">No stages yet.</div></div></div>'
    + '<div class="version-history" id="versionHistory" style="display:none;">'
    +   '<button class="vh-header" onclick="toggleVersionHistory()">'
    +     '<span id="vhChevron">&#9654;</span>&ensp;<span id="vhLabel">Version History</span>'
    +   '</button>'
    +   '<span class="view-err" id="viewError" style="display:none;"></span>'
    +   '<div class="vh-list" id="vhList" style="display:none;"></div>'
    + '</div>'
    + '</div>'
    + '<div class="card" id="rightPanel">'
    +   '<div class="right-panel-tabs">'
    +     '<button class="right-tab-btn" id="rightTabLog" onclick="switchRightPanel(\'log\')">Live Log</button>'
    +     '<button class="right-tab-btn active" id="rightTabGraph" onclick="switchRightPanel(\'graph\')">Graph</button>'
    +     '<div style="flex:1;"></div>'
    +     '<div id="graphZoomControls" style="display:flex;flex-direction:row;align-items:center;gap:3px;">'
    +       '<button class="graph-zoom-btn" title="Zoom out (or Ctrl+scroll)" onclick="zoomMonitor(-1)">&#x2212;</button>'
    +       '<span class="graph-zoom-label" id="monitorZoomLabel">100%</span>'
    +       '<button class="graph-zoom-btn" title="Zoom in (or Ctrl+scroll)" onclick="zoomMonitor(1)">+</button>'
    +       '<button class="graph-zoom-btn" title="Reset zoom" onclick="resetMonitorZoom()">&#x21BA;</button>'
    +     '</div>'
    +   '</div>'
    +   '<div class="log-panel" id="logPanel" style="display:none;"></div>'
    +   '<div class="pipeline-graph-view" id="graphView"><div id="graphViewInner"><div class="pipeline-graph-placeholder">Waiting for pipeline\u2026</div></div></div>'
    + '</div>';
  monitorZoom = 1.0;
  var gvEl = document.getElementById('graphView');
  if (gvEl) {
    gvEl.addEventListener('wheel', function(e) {
      if (e.ctrlKey || e.metaKey) { e.preventDefault(); zoomMonitor(e.deltaY < 0 ? 1 : -1); }
    }, { passive: false });
  }
  elapsedTimer = setInterval(tickElapsed, 1000);
  // Reset version history state on tab switch
  vhExpanded = false;
  vhData = null;
  vhMembersById = {};
  loadVersionHistory(id);
  var sl = document.getElementById('stageList');
  if (sl) {
    sl.addEventListener('mousedown', function(e) {
      if (e.button !== 0) return;
      var logBtn = e.target.closest('.stage-log-btn');
      if (logBtn) { showStageLog(logBtn.dataset.nodeId, logBtn.dataset.stageName); e.preventDefault(); return; }

      var errBtn = e.target.closest('.stage-err-btn');
      if (errBtn) { showStageError(parseInt(errBtn.dataset.pos, 10)); e.preventDefault(); }
    });
  }
}

// Tick the elapsed counter every second while a pipeline is running.
function tickElapsed() {
  var p = selectedId && pipelines[selectedId];
  if (!p) return;
  var d = p.state || {};

  // Overall pipeline elapsed
  var el = document.getElementById('pElapsed');
  if (el) el.textContent = d.startedAt ? elapsed(d) : '';

  // Per-stage live count-up for running stages
  var liveDurs = document.querySelectorAll('.stage-live-dur');
  for (var i = 0; i < liveDurs.length; i++) {
    var startedAt = parseInt(liveDurs[i].getAttribute('data-started-at'), 10);
    if (startedAt) {
      var sec = Math.floor((Date.now() - startedAt) / 1000);
      liveDurs[i].textContent = sec < 60 ? sec + 's' : Math.floor(sec/60) + 'm ' + (sec%60) + 's';
    }
  }

  if (d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled' || d.status === 'paused') {
    clearInterval(elapsedTimer); elapsedTimer = null;
  }
}

// Update only the data inside an already-built panel — no DOM structure rebuild.
function updatePanel(id) {
  var p = pipelines[id];
  if (!p) return;
  var d = p.state || {};

  var titleEl = document.getElementById('pTitle');
  if (titleEl) titleEl.textContent = d.pipeline || p.fileName;

  var statusBadge = document.getElementById('pStatusBadge');
  if (statusBadge) {
    var st = d.status || 'idle';
    statusBadge.className = 'badge badge-' + st;
    statusBadge.textContent = st;
  }

  var cancelBtn = document.getElementById('cancelBtn');
  if (cancelBtn) {
    var isRunning = d.status === 'running' || d.status === 'idle';
    cancelBtn.style.display = isRunning ? 'inline-block' : 'none';
    if (isRunning) { cancelBtn.disabled = false; cancelBtn.innerHTML = '\u25A0\u2002Cancel'; }
  }

  var pauseBtn = document.getElementById('pauseBtn');
  if (pauseBtn) {
    var isRunning = d.status === 'running' || d.status === 'idle';
    pauseBtn.style.display = isRunning ? 'inline-block' : 'none';
    if (isRunning) { pauseBtn.disabled = false; pauseBtn.innerHTML = '&#9646;&#9646;&ensp;Pause'; }
  }

  var resumeBtn = document.getElementById('resumeBtn');
  if (resumeBtn) {
    var isPaused = d.status === 'paused';
    resumeBtn.style.display = isPaused ? 'inline-block' : 'none';
    if (isPaused) { resumeBtn.disabled = false; resumeBtn.innerHTML = '&#9654;&ensp;Resume'; }
  }

  var downloadBtn = document.getElementById('downloadBtn');
  if (downloadBtn) {
    var isDone = d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled' || d.status === 'paused';
    downloadBtn.style.display = isDone ? 'inline-block' : 'none';
  }

  var exportBtn = document.getElementById('exportBtn');
  if (exportBtn) {
    exportBtn.style.display = !!(p && p.dotSource) ? 'inline-block' : 'none';
  }

  var rerunBtn = document.getElementById('rerunBtn');
  if (rerunBtn) {
    var isDone = d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled';
    rerunBtn.style.display = isDone ? 'inline-block' : 'none';
    if (isDone) { rerunBtn.disabled = false; rerunBtn.innerHTML = '&#8635;&ensp;Re-run'; }
  }

  var iterateBtn = document.getElementById('iterateBtn');
  if (iterateBtn) {
    var canIterate = (d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled') && !!(p && p.dotSource);
    iterateBtn.style.display = canIterate ? 'inline-block' : 'none';
    if (canIterate) { iterateBtn.disabled = false; iterateBtn.innerHTML = '&#9998;&ensp;Iterate'; }
  }

  var isTerminal = d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled' || d.status === 'paused';
  var isArchived = !!(d.archived);

  var archiveBtn = document.getElementById('archiveBtn');
  if (archiveBtn) {
    archiveBtn.style.display = (isTerminal && !isArchived) ? 'inline-block' : 'none';
    if (isTerminal && !isArchived) { archiveBtn.disabled = false; archiveBtn.innerHTML = '&#8595;&ensp;Archive'; }
  }

  var unarchiveBtn = document.getElementById('unarchiveBtn');
  if (unarchiveBtn) {
    unarchiveBtn.style.display = isArchived ? 'inline-block' : 'none';
    if (isArchived) { unarchiveBtn.disabled = false; unarchiveBtn.innerHTML = '&#8593;&ensp;Unarchive'; }
  }

  var deleteBtn = document.getElementById('deleteBtn');
  if (deleteBtn) {
    deleteBtn.style.display = isTerminal ? 'inline-block' : 'none';
    if (isTerminal) { deleteBtn.disabled = false; deleteBtn.innerHTML = '&#10005;&ensp;Delete'; }
  }

  var failureReportBtn = document.getElementById('failureReportBtn');
  if (failureReportBtn) {
    failureReportBtn.style.display = d.hasFailureReport ? 'inline-block' : 'none';
  }

  // Read-only gating: hide mutating controls for on-demand hydrated view-only runs
  var viewOnly = !!(p.isHydratedViewOnly);
  if (viewOnly) {
    var mutating = ['cancelBtn','pauseBtn','resumeBtn','rerunBtn','iterateBtn','archiveBtn','unarchiveBtn','deleteBtn'];
    for (var mi = 0; mi < mutating.length; mi++) {
      var mb = document.getElementById(mutating[mi]);
      if (mb) mb.style.display = 'none';
    }
  }

  // Version navigator — refresh if vhMembersById is already populated for this run
  if (vhMembersById[id]) updateVersionNav(id);

  var metaEl = document.getElementById('pMeta');
  if (metaEl) {
    var prefix = d.runId ? 'Run: ' + esc(d.runId) : '';
    if (d.startedAt) {
      metaEl.innerHTML = (prefix ? prefix + '\u00a0\u00a0\u00b7\u00a0\u00a0' : '')
        + 'Elapsed: <span id="pElapsed" style="font-variant-numeric:tabular-nums;color:#e3b341;font-weight:600;">' + elapsed(d) + '</span>';
    } else {
      metaEl.textContent = prefix;
    }
  }

  // Pipeline description (originalPrompt)
  var descEl = document.getElementById('pipelineDesc');
  if (descEl) {
    var desc = (p && p.originalPrompt) ? p.originalPrompt.trim() : '';
    if (desc) { var t = document.getElementById('pipelineDescText'); if (t) t.textContent = desc; descEl.style.display = ''; }
    else { descEl.style.display = 'none'; }
  }

  // Stages — replace inner html (small list, safe to rebuild)
  var stageList = document.getElementById('stageList');
  if (stageList) {
    if (d.stages && d.stages.length > 0) {
      var html = '';
      for (var i = 0; i < d.stages.length; i++) {
        var s = d.stages[i];
        var icon = ICONS[s.status] || ICONS.pending;
        var hasNodeId = s.nodeId && s.nodeId !== '';
        var isLogOpen = !!(stageLogNodeId && s.nodeId === stageLogNodeId);
        html += '<div class="stage ' + esc(s.status) + (isLogOpen ? ' log-open' : '') + '" data-node-id="' + esc(s.nodeId) + '">'
          + '<div class="stage-row">'
          + '<span class="stage-icon ' + (s.status === 'running' || s.status === 'diagnosing' || s.status === 'repairing' ? 'pulse' : '') + '">' + icon + '</span>'
          + '<span class="stage-name">' + esc(s.name) + '</span>';
        if (s.error) {
          html += '<button class="stage-err-btn" title="Click for full error details" data-pos="' + i + '">&#9888;</button>';
        }
        if (hasNodeId && s.hasLog) {
          html += '<button class="stage-log-btn' + (isLogOpen ? ' active' : '') + '" data-node-id="' + esc(s.nodeId) + '" data-stage-name="' + esc(s.name) + '">' + (isLogOpen ? '\u25bc\u2002Logs' : 'Logs') + '</button>';
        }
        if (s.status === 'running' && s.startedAt) {
          var sec = Math.floor((Date.now() - s.startedAt) / 1000);
          var liveStr = sec < 60 ? sec + 's' : Math.floor(sec/60) + 'm ' + (sec%60) + 's';
          html += '<span class="stage-dur stage-live-dur" data-started-at="' + s.startedAt + '">' + liveStr + '</span>';
        } else if (s.durationMs != null) {
          html += '<span class="stage-dur">' + fmtDur(s.durationMs) + '</span>';
        }
        html += '</div>'; // end stage-row
        if (isLogOpen) {
          html += '<div class="stage-log-inline">'
            + '<pre class="stage-log-pre" id="stage-log-pre-' + esc(s.nodeId) + '"></pre>'
            + '</div>';
        }
        html += '</div>'; // end stage
      }
      // Save log scroll position before rebuilding the stage list DOM
      var savedLogAtBottom = true;
      var savedLogScroll = -1;
      if (stageLogNodeId) {
        var existingLogPre = document.getElementById('stage-log-pre-' + stageLogNodeId);
        if (existingLogPre) {
          savedLogAtBottom = (existingLogPre.scrollHeight - existingLogPre.scrollTop) <= (existingLogPre.clientHeight + 60);
          savedLogScroll = existingLogPre.scrollTop;
        }
      }
      stageList.innerHTML = html;
      if (stageLogNodeId) {
        var logPre = document.getElementById('stage-log-pre-' + stageLogNodeId);
        if (logPre) {
          logPre.textContent = stageLogContent || 'Loading\u2026';
          if (savedLogAtBottom) { logPre.scrollTop = logPre.scrollHeight; }
          else if (savedLogScroll >= 0) { logPre.scrollTop = savedLogScroll; }
        }
      }
    } else if (d.status === 'failed') {
      stageList.innerHTML = '<div class="empty-note">Pipeline failed before any stages ran.\u00a0\u00a0'
        + '<button class="stage-log-btn" onclick="switchRightPanel(\'log\')" style="margin-left:4px;">View Logs</button></div>';
    }
  }

  // Logs — append only new lines to preserve scroll position
  var logPanel = document.getElementById('logPanel');
  if (logPanel && d.logs && d.logs.length > 0) {
    var prev = logRenderedCount[id] || 0;
    if (d.logs.length > prev) {
      var atBottom = (logPanel.scrollHeight - logPanel.scrollTop) <= (logPanel.clientHeight + 60);
      for (var j = prev; j < d.logs.length; j++) {
        var line = document.createElement('div');
        line.className = 'log-line';
        line.textContent = d.logs[j];
        logPanel.appendChild(line);
      }
      logRenderedCount[id] = d.logs.length;
      if (atBottom) logPanel.scrollTop = logPanel.scrollHeight;
    }
  }

  // Render (or re-render) the pipeline graph whenever stage statuses change
  renderPipelineGraph(id);

  // Refresh version history panel (re-render from cached data, no network request)
  if (vhData !== null) renderVersionHistory(id, vhData);
}

function renderMain() {
  var mainEl = document.getElementById('mainContent');
  if (selectedId === DASHBOARD_TAB_ID) { renderDashboard(); return; }
  if (!selectedId || !pipelines[selectedId]) {
    panelBuiltFor = null;
    mainEl.innerHTML = '<div class="no-pipeline"><h2>No pipeline selected</h2>'
      + '<p>Use <strong>Create</strong> to generate and run a pipeline.</p></div>';
    return;
  }
  // Rebuild scaffold only when switching tabs
  if (panelBuiltFor !== selectedId) buildPanel(selectedId);
  updatePanel(selectedId);
}

// ── Version History ──────────────────────────────────────────────────────────
var vhExpanded = false;
var vhData = null;
var vhMembersById = {};  // runId -> { versionNum, totalVersions, prevId, nextId }

function loadVersionHistory(runId) {
  var fid = pipelines[runId] && pipelines[runId].familyId;
  if (!fid) {
    var el = document.getElementById('versionHistory');
    if (el) el.style.display = 'none';
    return;
  }
  fetch('/api/pipeline-family?id=' + encodeURIComponent(runId))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (!data.members) return;
      vhData = data.members;
      vhMembersById = {};
      var total = data.members.length;
      for (var i = 0; i < total; i++) {
        var m = data.members[i];
        vhMembersById[m.id] = {
          versionNum: i + 1,
          totalVersions: total,
          prevId: i > 0 ? data.members[i - 1].id : null,
          nextId: i < total - 1 ? data.members[i + 1].id : null,
          dotSource: m.dotSource || '',
          originalPrompt: m.originalPrompt || ''
        };
      }
      renderVersionHistory(runId, data.members);
      updateVersionNav(runId);
    })
    .catch(function() {
      var el = document.getElementById('versionHistory');
      if (el) el.style.display = 'none';
    });
}

function renderVersionHistory(currentRunId, members) {
  var section = document.getElementById('versionHistory');
  var listEl  = document.getElementById('vhList');
  var label   = document.getElementById('vhLabel');
  if (!section || !listEl || !label) return;
  // Show if 2+ members, or if 1 member and this run is an iterated version (familyId ≠ runId)
  var p = pipelines[currentRunId];
  var isIterateMember = p && p.familyId && p.familyId !== currentRunId;
  if (!members || members.length < 1 || (members.length < 2 && !isIterateMember)) {
    section.style.display = 'none';
    return;
  }
  section.style.display = '';
  var vCount = members.length;
  label.textContent = 'Version History (' + vCount + (vCount === 1 ? ' version' : ' versions') + ')';
  if (!vhExpanded) {
    listEl.style.display = 'none';
    return;
  }
  listEl.style.display = '';
  var html = '';
  // Render newest-first
  for (var i = members.length - 1; i >= 0; i--) {
    var m = members[i];
    var vn = i + 1;
    var isCurrent = m.id === currentRunId ? ' vh-current' : '';
    var ts = m.createdAt ? new Date(m.createdAt).toLocaleString() : '';
    var label = (m.displayName || m.originalPrompt || '').substring(0, 50) || '(no description)';
    var statusCls = 'badge-' + esc(m.status || 'idle');
    html += '<div class="vh-row' + isCurrent + '">'
          + '<span class="vh-ver">v' + vn + '</span>'
          + '<span class="badge ' + statusCls + '" style="font-size:0.65rem;padding:1px 5px;">' + esc(m.status || 'idle') + '</span>'
          + '<span class="vh-row-name">' + esc(label) + '</span>'
          + '<span class="vh-row-ts">' + esc(ts) + '</span>'
          + '<div class="vh-row-actions">'
          + '<button class="btn-vh" onclick="selectOrHydrateRun(' + JSON.stringify(m.id) + ')">View</button>'
          + '<button class="btn-vh" onclick="openArtifacts(' + JSON.stringify(m.id) + ',' + JSON.stringify(m.displayName || '') + ')">Artifacts</button>'
          + '<button class="btn-vh" onclick="restoreVersion(' + JSON.stringify(m.id) + ')">Restore</button>'
          + '</div>'
          + '</div>';
  }
  listEl.innerHTML = html;
}

function toggleVersionHistory() {
  vhExpanded = !vhExpanded;
  var chevron = document.getElementById('vhChevron');
  if (chevron) chevron.innerHTML = vhExpanded ? '&#9660;' : '&#9654;';
  if (vhData !== null && selectedId) renderVersionHistory(selectedId, vhData);
  var listEl = document.getElementById('vhList');
  if (listEl) listEl.style.display = vhExpanded ? '' : 'none';
}

function restoreVersion(vhRunId) {
  var info = vhMembersById[vhRunId];
  if (!info) return;
  enterIterateMode(vhRunId, info.dotSource, info.originalPrompt);
  showView('create');
}

// ── History navigation ───────────────────────────────────────────────────────

function showViewError(msg) {
  var el = document.getElementById('viewError');
  if (!el) return;
  el.textContent = msg;
  el.style.display = '';
  el.style.opacity = '1';
  setTimeout(function() {
    el.style.opacity = '0';
    setTimeout(function() { el.style.display = 'none'; el.textContent = ''; el.style.opacity = '1'; }, 350);
  }, 3000);
}

function applyPipelineEntry(entry) {
  if (!pipelines[entry.id]) {
    // Append to end so the tab appears after existing tabs
    var ids = Object.keys(pipelines);
    // pipelines is a plain object; orderedIds is implicit from applyUpdate's loop
    // We mimic what applyUpdate does: just set pipelines[id] and let renderTabs sort
  }
  pipelines[entry.id] = entry;
}

function maybeAutoExpandVH(runId) {
  vhExpanded = true;
  var chevron = document.getElementById('vhChevron');
  if (chevron) chevron.innerHTML = '&#9660;';
  if (vhData !== null && selectedId === runId) renderVersionHistory(runId, vhData);
}

function selectOrHydrateRun(runId) {
  if (pipelines[runId]) {
    selectTab(runId);
    maybeAutoExpandVH(runId);
    return;
  }
  fetch('/api/pipeline-view?id=' + encodeURIComponent(runId))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.error) { showViewError('Load failed: ' + data.error); return; }
      applyPipelineEntry(data);
      renderTabs();
      selectTab(runId);
      maybeAutoExpandVH(runId);
    })
    .catch(function() { showViewError('Network error loading run'); });
}

function updateVersionNav(runId) {
  var el = document.getElementById('versionNav');
  if (!el) return;
  var info = vhMembersById[runId];
  if (!info || info.totalVersions < 2) { el.style.display = 'none'; return; }
  el.style.display = '';
  document.getElementById('vnLabel').textContent = 'v' + info.versionNum + ' of ' + info.totalVersions;
  document.getElementById('vnPrev').disabled = !info.prevId;
  document.getElementById('vnNext').disabled = !info.nextId;
}

function navVersion(delta) {
  var info = vhMembersById[selectedId];
  if (!info) return;
  var targetId = delta < 0 ? info.prevId : info.nextId;
  if (!targetId) return;
  selectOrHydrateRun(targetId);
}

// ── Artifact Modal ───────────────────────────────────────────────────────────
var artifactCurrentRunId = null;

function openArtifacts(runId, displayName) {
  artifactCurrentRunId = runId;
  document.getElementById('artifactTitle').textContent = 'Artifacts \u2014 ' + (displayName || runId);
  document.getElementById('artifactFiles').innerHTML = '<div style="padding:8px 12px;color:var(--text-faint);font-size:0.8rem;">Loading\u2026</div>';
  document.getElementById('artifactView').textContent = 'Select a file to view';
  document.getElementById('artifactOverlay').classList.add('open');
  fetch('/api/run-artifacts?id=' + encodeURIComponent(runId))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (!data.files) return;
      var files = data.files;
      if (files.length === 0) {
        document.getElementById('artifactFiles').innerHTML = '<div style="padding:8px 12px;color:var(--text-faint);font-size:0.8rem;">No artifacts</div>';
        return;
      }
      var html = '';
      for (var i = 0; i < files.length; i++) {
        var f = files[i];
        html += '<div class="artifact-file" onclick="loadArtifact(' + JSON.stringify(f.path) + ',this,' + f.isText + ')" data-path="' + esc(f.path) + '">'
              + esc(f.path) + '</div>';
      }
      if (data.truncated) html += '<div style="padding:4px 12px;color:var(--text-faint);font-size:0.72rem;">\u2026truncated at 500</div>';
      document.getElementById('artifactFiles').innerHTML = html;
      // Auto-load first text file
      var first = files.find(function(f) { return f.isText; });
      if (first) {
        var firstEl = document.querySelector('#artifactFiles .artifact-file');
        if (firstEl) loadArtifact(first.path, firstEl, true);
      }
    })
    .catch(function() {
      document.getElementById('artifactFiles').innerHTML = '<div style="padding:8px 12px;color:#f85149;font-size:0.8rem;">Failed to load</div>';
    });
}

function loadArtifact(relPath, clickedEl, isText) {
  // Mark active
  document.querySelectorAll('#artifactFiles .artifact-file').forEach(function(el) { el.classList.remove('active'); });
  if (clickedEl) clickedEl.classList.add('active');
  var viewEl = document.getElementById('artifactView');
  viewEl.textContent = 'Loading\u2026';
  if (!isText) {
    viewEl.innerHTML = '<em style="color:var(--text-muted)">Binary file \u2014 </em>'
      + '<a href="/api/run-artifact-file?id=' + encodeURIComponent(artifactCurrentRunId)
      + '&path=' + encodeURIComponent(relPath) + '" target="_blank" style="color:var(--accent)">Download</a>';
    return;
  }
  fetch('/api/run-artifact-file?id=' + encodeURIComponent(artifactCurrentRunId) + '&path=' + encodeURIComponent(relPath))
    .then(function(r) { return r.text(); })
    .then(function(text) { viewEl.textContent = text; })
    .catch(function() { viewEl.textContent = 'Failed to load file.'; });
}

function closeArtifacts() {
  document.getElementById('artifactOverlay').classList.remove('open');
  artifactCurrentRunId = null;
}

// ── Data update ─────────────────────────────────────────────────────────────
function applyUpdate(data) {
  if (!data.pipelines) return;
  var incoming = {};
  for (var i = 0; i < data.pipelines.length; i++) {
    var p = data.pipelines[i];
    var isNew = !pipelines[p.id];
    var prevStatus = pipelines[p.id] && pipelines[p.id].state && pipelines[p.id].state.status;
    pipelines[p.id] = p;
    incoming[p.id] = true;
    if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null) selectedId = p.id;
    // Fireworks when the viewed pipeline transitions to completed
    if (!isNew && prevStatus !== 'completed' && p.state && p.state.status === 'completed' && p.id === selectedId) {
      var monitorView = document.getElementById('viewMonitor');
      if (monitorView && monitorView.style.display !== 'none' && appSettings.fireworks_enabled !== false) triggerFireworks();
    }
  }
  // Remove any local entries no longer reported by the server (e.g. deleted runs).
  for (var existingId in pipelines) {
    if (!incoming[existingId]) {
      delete pipelines[existingId];
      if (selectedId === existingId) {
        selectedId = DASHBOARD_TAB_ID;
        panelBuiltFor = null;
      }
    }
  }
  renderTabs();
  if (selectedId === DASHBOARD_TAB_ID || (selectedId && pipelines[selectedId])) renderMain();
  var archivedView = document.getElementById('viewArchived');
  if (archivedView && archivedView.style.display !== 'none') renderArchivedView();
}

// ── SSE connection ───────────────────────────────────────────────────────────
function setConnected(live) {
  document.getElementById('connDot').className = 'conn-dot' + (live ? ' live' : '');
  document.getElementById('connLabel').textContent = live ? 'live' : 'reconnecting';
}

var sseDelay = 500;
var appSettings = { fireworks_enabled: true };

function loadSettings() {
  fetch('/api/settings')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      appSettings = s;
      var el = document.getElementById('settingFireworks');
      if (el) el.checked = s.fireworks_enabled !== false;
      var anthEl = document.getElementById('settingAnthropicEnabled');
      if (anthEl) anthEl.checked = s.provider_anthropic_enabled !== false;
      var oaiEl = document.getElementById('settingOpenAIEnabled');
      if (oaiEl) oaiEl.checked = s.provider_openai_enabled !== false;
      var gemEl = document.getElementById('settingGeminiEnabled');
      if (gemEl) gemEl.checked = s.provider_gemini_enabled !== false;
      var anthCmd = document.getElementById('cliCmdAnthropic');
      if (anthCmd) anthCmd.value = s.cli_anthropic_command || 'claude -p {prompt}';
      var oaiCmd = document.getElementById('cliCmdOpenAI');
      if (oaiCmd) oaiCmd.value = s.cli_openai_command || 'codex -p {prompt}';
      var gemCmd = document.getElementById('cliCmdGemini');
      if (gemCmd) gemCmd.value = s.cli_gemini_command || 'gemini -p {prompt}';
      applyExecutionModeUi(s.execution_mode || 'api');
    })
    .catch(function() {});
}

function setExecutionMode(mode) {
  saveSetting('execution_mode', mode);
  applyExecutionModeUi(mode);
  if (mode === 'cli') loadCliStatus();
}

function applyExecutionModeUi(mode) {
  var apiBtn = document.getElementById('modeApiBtn');
  var cliBtn = document.getElementById('modeCliBtn');
  var cliFields = ['cliCmdAnthropic', 'cliCmdOpenAI', 'cliCmdGemini'];
  var cliBadges = ['cliBadgeAnthropic', 'cliBadgeOpenAI', 'cliBadgeGemini'];
  if (apiBtn) {
    apiBtn.style.background = mode === 'api' ? 'var(--accent, #4f8ef7)' : 'var(--surface-muted)';
    apiBtn.style.color = mode === 'api' ? '#fff' : 'var(--text)';
    apiBtn.style.borderColor = mode === 'api' ? 'var(--accent, #4f8ef7)' : 'var(--border)';
  }
  if (cliBtn) {
    cliBtn.style.background = mode === 'cli' ? 'var(--accent, #4f8ef7)' : 'var(--surface-muted)';
    cliBtn.style.color = mode === 'cli' ? '#fff' : 'var(--text)';
    cliBtn.style.borderColor = mode === 'cli' ? 'var(--accent, #4f8ef7)' : 'var(--border)';
  }
  cliFields.forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.style.display = mode === 'cli' ? 'block' : 'none';
  });
  cliBadges.forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.style.display = mode === 'cli' ? 'inline' : 'none';
  });
}

function loadCliStatus() {
  fetch('/api/settings/cli-status')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      function badge(id, detected) {
        var el = document.getElementById(id);
        if (!el) return;
        el.textContent = detected ? '\u25cf detected' : '\u2717 not found';
        el.style.color = detected ? '#3c9e5f' : '#c0392b';
      }
      badge('cliBadgeAnthropic', s.anthropic);
      badge('cliBadgeOpenAI', s.openai);
      badge('cliBadgeGemini', s.gemini);
    })
    .catch(function() {});
}

function saveSetting(key, value) {
  appSettings[key] = value;
  fetch('/api/settings/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: key, value: String(value) })
  }).catch(function() {});
}

function connectSSE() {
  var es = new EventSource('/events');
  es.onopen = function() {
    setConnected(true);
    sseDelay = 500;
    // Explicit snapshot fetch on (re)connect for state convergence.
    fetch('/api/pipelines')
      .then(function(r) { return r.json(); })
      .then(applyUpdate)
      .catch(function() {});
  };
  es.onmessage = function(e) { try { applyUpdate(JSON.parse(e.data)); } catch (x) {} };
  es.onerror = function() {
    setConnected(false);
    es.close();
    setTimeout(connectSSE, sseDelay);
    sseDelay = Math.min(sseDelay * 2, 5000);
  };
}
connectSSE();

// ── Iterate mode state ───────────────────────────────────────────────────────
var iterateSourceId = null;

function enterIterateMode(id, dot, prompt) {
  iterateSourceId = id;
  var dotPreview = document.getElementById('dotPreview');
  var nlInput = document.getElementById('nlInput');
  var runBtn = document.getElementById('runBtn');
  var genHint = document.getElementById('genHint');
  var cancelBtn = document.getElementById('cancelIterateBtn');
  if (dotPreview) dotPreview.value = dot;
  if (nlInput) {
    nlInput.value = prompt || '';
    nlInput.placeholder = 'Describe modifications to make to the existing pipeline\u2026';
  }
  if (runBtn) { runBtn.disabled = false; }
  if (cancelBtn) cancelBtn.style.display = 'inline-block';
  setGenStatus('', 'Iterate mode \u2014 edit description to regenerate automatically, or edit DOT directly.');
  renderGraph();
}

function exitIterateMode() {
  iterateSourceId = null;
  var nlInput = document.getElementById('nlInput');
  var genHint = document.getElementById('genHint');
  var cancelBtn = document.getElementById('cancelIterateBtn');
  if (nlInput) nlInput.placeholder = 'e.g. \u201cWrite comprehensive unit tests for a Python web app, run them, fix any failures, then generate a coverage report\u201d\n\nDescribe what you want in plain English. The pipeline will be generated automatically as you type.';
  if (genHint) genHint.textContent = 'You can edit the DOT source before running.';
  if (cancelBtn) cancelBtn.style.display = 'none';
  setGenStatus('', 'Start typing to generate\u2026');
}

function cancelIterate() {
  var modal = document.getElementById('cancelIterateModal');
  if (modal) { modal.classList.remove('hidden'); modal.style.display = 'flex'; }
}

function closeCancelIterateModal() {
  var modal = document.getElementById('cancelIterateModal');
  if (modal) { modal.classList.add('hidden'); modal.style.display = ''; }
}

function confirmCancelIterate() {
  closeCancelIterateModal();
  exitIterateMode();
  showView('monitor');
}

function iteratePipeline() {
  var id = selectedId;
  if (!id || !pipelines[id] || !pipelines[id].dotSource) return;
  enterIterateMode(id, pipelines[id].dotSource, pipelines[id].originalPrompt || '');
  showView('create');
}

function modifyDot(changes, baseDot) {
  var runBtn = document.getElementById('runBtn');
  var dotPreview = document.getElementById('dotPreview');
  if (runBtn) runBtn.disabled = true;
  dotPreview.value = '';
  fetch('/api/iterate/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ baseDot: baseDot, changes: changes })
  }).then(function(resp) {
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = '';
    function read() {
      reader.read().then(function(chunk) {
        if (chunk.done) {
          if (runBtn) runBtn.disabled = false;
          renderGraph();
          return;
        }
        buf += decoder.decode(chunk.value, { stream: true });
        var lines = buf.split('\n');
        buf = lines.pop();
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i].trim();
          if (!line.startsWith('data: ')) continue;
          try {
            var evt = JSON.parse(line.slice(6));
            if (evt.delta) {
              dotPreview.value += evt.delta;
              dotPreview.scrollTop = dotPreview.scrollHeight;
            } else if (evt.done && evt.dotSource) {
              dotPreview.value = evt.dotSource;
              setGenStatus('ok', 'Modified \u2014 review and run.');
            } else if (evt.error) {
              setGenStatus('error', 'Error: ' + evt.error);
              if (runBtn) runBtn.disabled = false;
            }
          } catch(x) {}
        }
        read();
      }).catch(function() {
        setGenStatus('error', 'Stream error.');
        if (runBtn) runBtn.disabled = false;
      });
    }
    read();
  }).catch(function(err) {
    setGenStatus('error', 'Request failed: ' + String(err));
    if (runBtn) runBtn.disabled = false;
  });
}

function runIterated() {
  var id = iterateSourceId;
  var dotSource = document.getElementById('dotPreview').value.trim();
  var originalPrompt = document.getElementById('nlInput').value.trim();
  var runBtn = document.getElementById('runBtn');
  if (!dotSource || !id) return;
  if (runBtn) { runBtn.disabled = true; runBtn.innerHTML = 'Running\u2026'; }
  fetch('/api/iterate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id, dotSource: dotSource, originalPrompt: originalPrompt })
  }).then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.error) {
        setGenStatus('error', data.error);
        if (runBtn) { runBtn.disabled = false; runBtn.innerHTML = '&#9654;&ensp;Run Pipeline'; }
        return;
      }
      exitIterateMode();
      kickPoll();
      showView('monitor');
    })
    .catch(function(e) {
      setGenStatus('error', 'Failed: ' + String(e));
      if (runBtn) { runBtn.disabled = false; runBtn.innerHTML = '&#9654;&ensp;Run Pipeline'; }
    });
}

// ── Adaptive polling ─────────────────────────────────────────────────────────
// 300ms while any pipeline is running, 2s otherwise.
// Fallback and safety net; SSE is the primary fast-path.
// pollGen invalidates stale timers when kickPoll() restarts the loop.
var pollGen = 0;

function poll(gen) {
  if (gen !== pollGen) return;
  fetch('/api/pipelines')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (gen !== pollGen) return;
      applyUpdate(data);
      // Poll fast while any pipeline is idle or running (not yet in a terminal or paused state).
      var active = Object.keys(pipelines).some(function(id) {
        var s = pipelines[id].state && pipelines[id].state.status;
        return s === 'running' || s === 'idle';
      });
      setTimeout(function() { poll(pollGen); }, active ? 300 : 2000);
    })
    .catch(function() {
      if (gen === pollGen) setTimeout(function() { poll(pollGen); }, 2000);
    });
}

function kickPoll() {
  pollGen++;
  poll(pollGen);
}
kickPoll();

// ── View navigation ──────────────────────────────────────────────────────────
function clearCreateForm() {
  var nlInput      = document.getElementById('nlInput');
  var dotPreview   = document.getElementById('dotPreview');
  var runBtn       = document.getElementById('runBtn');
  var graphContent = document.getElementById('graphContent');
  if (nlInput)    { nlInput.value = ''; }
  if (dotPreview) { dotPreview.value = ''; }
  if (runBtn)     { runBtn.disabled = true; runBtn.innerHTML = '&#9654;&ensp;Run Pipeline'; }
  if (graphContent) { graphContent.innerHTML = '<div class="graph-placeholder">Generate a pipeline first to see the graph.</div>'; }
  setGenStatus('', 'Start typing to generate\u2026');
}

function showView(name) {
  var isMonitor  = name === 'monitor';
  var isCreate   = name === 'create';
  var isArchived = name === 'archived';
  var isSettings = name === 'settings';
  if (!isCreate && iterateSourceId) exitIterateMode();
  if (isCreate && !iterateSourceId) clearCreateForm();
  document.getElementById('viewMonitor').style.display  = isMonitor  ? '' : 'none';
  document.getElementById('viewCreate').style.display   = isCreate   ? '' : 'none';
  document.getElementById('viewArchived').style.display = isArchived ? '' : 'none';
  document.getElementById('viewSettings').style.display = isSettings ? '' : 'none';
  document.getElementById('navMonitor').classList.toggle('active', isMonitor);
  document.getElementById('navCreate').classList.toggle('active', isCreate);
  document.getElementById('navArchived').classList.toggle('active', isArchived);
  document.getElementById('navSettings').classList.toggle('active', isSettings);
  if (isArchived) renderArchivedView();
  if (isSettings) {
    loadSettings();
    if ((appSettings.execution_mode || 'api') === 'cli') loadCliStatus();
  }
}

function switchPreview() {} // no-op — both panels are always visible

var renderRetries = 0;
var MAX_RENDER_RETRIES = 2;

function renderGraph() {
  var dotSource = document.getElementById('dotPreview').value.trim();
  var content = document.getElementById('graphContent');
  if (!dotSource) {
    content.innerHTML = '<div class="graph-placeholder">No DOT source yet.</div>';
    return;
  }
  content.innerHTML = '<div class="graph-loading">Rendering\u2026</div>';
  fetch('/api/render', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dotSource: dotSource })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.error) {
      if (renderRetries < MAX_RENDER_RETRIES) {
        renderRetries++;
        setGenStatus('loading', 'Fixing DOT syntax\u2026 (attempt ' + renderRetries + ')');
        fixDotAndRerender(dotSource, resp.error);
      } else {
        renderRetries = 0;
        content.innerHTML = '<div class="graph-error">' + esc(resp.error) + '</div>';
      }
    } else {
      renderRetries = 0;
      content.innerHTML = resp.svg;
      applyCreateZoom();
    }
  })
  .catch(function(err) {
    renderRetries = 0;
    content.innerHTML = '<div class="graph-error">Render failed: ' + esc(String(err)) + '</div>';
  });
}

function fixDotAndRerender(brokenDot, errorMsg) {
  var preview = document.getElementById('dotPreview');
  preview.value = '';
  fetch('/api/fix-dot', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dotSource: brokenDot, error: errorMsg })
  })
  .then(function(resp) {
    if (!resp.ok) {
      return resp.json().then(function(j) { throw new Error(j.error || 'HTTP ' + resp.status); });
    }
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = '';
    var finished = false;
    function read() {
      reader.read().then(function(result) {
        if (result.done) {
          if (!finished) { setGenStatus('error', 'Fix stream ended unexpectedly'); renderRetries = 0; }
          return;
        }
        buf += decoder.decode(result.value, { stream: true });
        var lines = buf.split('\n');
        buf = lines.pop();
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i];
          if (!line.startsWith('data: ')) continue;
          try {
            var evt = JSON.parse(line.slice(6));
            if (evt.delta !== undefined) {
              preview.value += evt.delta;
              preview.scrollTop = preview.scrollHeight;
            } else if (evt.done) {
              finished = true;
              preview.value = evt.dotSource;
              document.getElementById('runBtn').disabled = false;
              setGenStatus('ok', 'Fixed \u2713');
              renderGraph();
            } else if (evt.error) {
              setGenStatus('error', 'Fix failed: ' + evt.error);
              renderRetries = 0;
              finished = true;
              preview.value = brokenDot;
            }
          } catch (e) {}
        }
        if (!finished) read();
      }).catch(function(err) {
        setGenStatus('error', 'Fix stream failed: ' + String(err));
        renderRetries = 0;
        preview.value = brokenDot;
      });
    }
    read();
  })
  .catch(function(err) {
    setGenStatus('error', 'Fix request failed: ' + String(err));
    renderRetries = 0;
    preview.value = brokenDot;
  });
}

// ── Create view ──────────────────────────────────────────────────────────────
var genDebounce = null;
var genCountdown = null;
var GEN_DELAY_SECS = 3;

function applyTheme(t) {
  document.documentElement.setAttribute('data-theme', t);
  document.documentElement.style.colorScheme = t;
  var btn = document.getElementById('themeToggle');
  if (btn) {
    var isLight = t === 'light';
    btn.textContent = isLight ? '\u263e' : '\u2600';
    btn.setAttribute('aria-label', isLight ? 'Switch to dark theme' : 'Switch to light theme');
    btn.setAttribute('title',      isLight ? 'Switch to dark theme' : 'Switch to light theme');
  }
}
function toggleTheme() {
  var cur = document.documentElement.getAttribute('data-theme') || 'dark';
  var next = cur === 'light' ? 'dark' : 'light';
  localStorage.setItem('attractor-theme', next);
  applyTheme(next);
}
function initTheme() {
  var saved = localStorage.getItem('attractor-theme') || 'dark';
  applyTheme(saved);
}

document.addEventListener('DOMContentLoaded', function() {
  initTheme();
  loadSettings();
  document.getElementById('nlInput').addEventListener('input', function() {
    clearTimeout(genDebounce);
    clearInterval(genCountdown);
    var prompt = this.value.trim();
    var verb = iterateSourceId ? 'Modifying' : 'Generating';
    if (!prompt) {
      if (iterateSourceId) {
        setGenStatus('', 'Iterate mode \u2014 edit description to regenerate automatically, or edit DOT directly.');
      } else {
        setGenStatus('', 'Start typing to generate\u2026');
        document.getElementById('runBtn').disabled = true;
      }
      return;
    }
    document.getElementById('runBtn').disabled = true;

    // Show countdown while user has stopped typing
    var remaining = GEN_DELAY_SECS;
    setGenStatus('', verb + ' in ' + remaining + 's\u2026');
    genCountdown = setInterval(function() {
      remaining--;
      if (remaining > 0) {
        setGenStatus('', verb + ' in ' + remaining + 's\u2026');
      } else {
        clearInterval(genCountdown);
      }
    }, 1000);

    genDebounce = setTimeout(function() {
      clearInterval(genCountdown);
      setGenStatus('loading', verb + '\u2026');
      if (iterateSourceId) {
        modifyDot(prompt, document.getElementById('dotPreview').value.trim());
      } else {
        generateDot(prompt);
      }
    }, GEN_DELAY_SECS * 1000);
  });

  document.getElementById('dotPreview').addEventListener('input', function() {
    var hasContent = !!this.value.trim();
    document.getElementById('runBtn').disabled = !hasContent;
    if (hasContent) {
      setGenStatus('ok', 'Ready \u2713');
      clearTimeout(genDebounce);
      genDebounce = setTimeout(renderGraph, 600);
    }
  });

  var gpEl = document.getElementById('graphPreview');
  if (gpEl) {
    gpEl.addEventListener('wheel', function(e) {
      if (e.ctrlKey || e.metaKey) { e.preventDefault(); zoomCreate(e.deltaY < 0 ? 1 : -1); }
    }, { passive: false });
  }
});

function setGenStatus(cls, msg) {
  var el = document.getElementById('genStatus');
  el.className = 'gen-status' + (cls ? ' ' + cls : '');
  el.textContent = msg;
}

function generateDot(prompt) {
  renderRetries = 0;
  var preview = document.getElementById('dotPreview');
  preview.value = '';
  document.getElementById('runBtn').disabled = true;

  fetch('/api/generate/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: prompt })
  })
  .then(function(resp) {
    if (!resp.ok) {
      return resp.json().then(function(j) { throw new Error(j.error || 'HTTP ' + resp.status); });
    }
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = '';
    var finished = false;

    function read() {
      reader.read().then(function(result) {
        if (result.done) {
          if (!finished) setGenStatus('error', 'Stream ended unexpectedly');
          return;
        }
        buf += decoder.decode(result.value, { stream: true });

        var lines = buf.split('\n');
        buf = lines.pop();

        for (var i = 0; i < lines.length; i++) {
          var line = lines[i];
          if (!line.startsWith('data: ')) continue;
          try {
            var evt = JSON.parse(line.slice(6));
            if (evt.delta !== undefined) {
              preview.value += evt.delta;
              preview.scrollTop = preview.scrollHeight;
            } else if (evt.done) {
              finished = true;
              preview.value = evt.dotSource;
              document.getElementById('runBtn').disabled = false;
              setGenStatus('ok', 'Ready \u2713');
              renderGraph();
            } else if (evt.error) {
              setGenStatus('error', 'Error: ' + evt.error);
              finished = true;
            }
          } catch (e) {}
        }

        if (!finished) read();
      }).catch(function(err) {
        setGenStatus('error', 'Stream failed: ' + err);
      });
    }
    read();
  })
  .catch(function(err) {
    setGenStatus('error', 'Request failed: ' + err);
  });
}

// ── Stage error detail modal ─────────────────────────────────────────────────
function showStageError(arrayPos) {
  var p = selectedId && pipelines[selectedId];
  if (!p || !p.state || !p.state.stages) return;
  var stage = p.state.stages[arrayPos];
  if (!stage || !stage.error) return;
  var titleEl = document.getElementById('errorDetailTitle');
  var preEl   = document.getElementById('errorDetailPre');
  var modal   = document.getElementById('errorDetailModal');
  if (!titleEl || !preEl || !modal) return;
  titleEl.textContent = stage.name + ' \u2014 Error';
  preEl.textContent   = stage.error;
  modal.classList.remove('hidden');
}

function closeErrorModal() {
  document.getElementById('errorDetailModal').classList.add('hidden');
}

// ── Stage inline log ─────────────────────────────────────────────────────────
function showStageLog(nodeId, stageName) {
  if (!selectedId) return;
  if (stageLogNodeId === nodeId) { collapseStageLog(); return; }
  // Close any previously open inline log
  if (stageLogNodeId) {
    var prevDiv = document.querySelector('.stage[data-node-id="' + stageLogNodeId + '"]');
    if (prevDiv) {
      prevDiv.classList.remove('log-open');
      var prevInline = prevDiv.querySelector('.stage-log-inline');
      if (prevInline) prevInline.remove();
      var prevBtn = prevDiv.querySelector('.stage-log-btn');
      if (prevBtn) { prevBtn.classList.remove('active'); prevBtn.innerHTML = 'Logs'; }
    }
    clearInterval(stageLogTimer);
    stageLogTimer = null;
  }
  stageLogNodeId = nodeId;
  stageLogContent = '';
  // Expand inline immediately via DOM manipulation
  var stageDiv = document.querySelector('.stage[data-node-id="' + nodeId + '"]');
  if (stageDiv) {
    stageDiv.classList.add('log-open');
    var btn = stageDiv.querySelector('.stage-log-btn');
    if (btn) { btn.classList.add('active'); btn.innerHTML = '\u25bc\u2002Logs'; }
    var logDiv = document.createElement('div');
    logDiv.className = 'stage-log-inline';
    logDiv.innerHTML = '<pre class="stage-log-pre" id="stage-log-pre-' + esc(nodeId) + '">Loading\u2026</pre>';
    stageDiv.appendChild(logDiv);
  }
  clearInterval(stageLogTimer);
  stageLogTimer = null;
  pollStageLog();
  stageLogTimer = setInterval(pollStageLog, 600);
}

function pollStageLog() {
  if (!selectedId || !stageLogNodeId) return;
  fetch('/api/stage-log?id=' + encodeURIComponent(selectedId) + '&stage=' + encodeURIComponent(stageLogNodeId))
    .then(function(r) { return r.text(); })
    .then(function(text) {
      stageLogContent = text || '';
      var pre = document.getElementById('stage-log-pre-' + stageLogNodeId);
      if (!pre) return;
      var atBottom = (pre.scrollHeight - pre.scrollTop) <= (pre.clientHeight + 60);
      pre.textContent = stageLogContent || '(no output yet)';
      if (atBottom) pre.scrollTop = pre.scrollHeight;
      // Stop polling once stage is done
      var p = selectedId && pipelines[selectedId];
      if (p && p.state && p.state.stages) {
        for (var i = 0; i < p.state.stages.length; i++) {
          var s = p.state.stages[i];
          if (s.nodeId === stageLogNodeId && s.status !== 'running' && s.status !== 'retrying' && s.status !== 'diagnosing' && s.status !== 'repairing') {
            clearInterval(stageLogTimer);
            stageLogTimer = null;
            break;
          }
        }
      }
    })
    .catch(function() {});
}

function collapseStageLog() {
  clearInterval(stageLogTimer);
  stageLogTimer = null;
  var prevNodeId = stageLogNodeId;
  stageLogNodeId = null;
  stageLogContent = '';
  if (prevNodeId) {
    var stageDiv = document.querySelector('.stage[data-node-id="' + prevNodeId + '"]');
    if (stageDiv) {
      stageDiv.classList.remove('log-open');
      var inline = stageDiv.querySelector('.stage-log-inline');
      if (inline) inline.remove();
      var btn = stageDiv.querySelector('.stage-log-btn');
      if (btn) { btn.classList.remove('active'); btn.innerHTML = 'Logs'; }
    }
  }
}

// ── Right-panel tab switcher (Log / Graph) ────────────────────────────────────
function switchRightPanel(tab) {
  var isLog = tab === 'log';
  var logPanel   = document.getElementById('logPanel');
  var graphView  = document.getElementById('graphView');
  var btnLog     = document.getElementById('rightTabLog');
  var btnGraph   = document.getElementById('rightTabGraph');
  var zoomCtrl   = document.getElementById('graphZoomControls');
  if (logPanel)  logPanel.style.display  = isLog ? '' : 'none';
  if (graphView) graphView.style.display = isLog ? 'none' : '';
  if (btnLog)    btnLog.classList.toggle('active', isLog);
  if (btnGraph)  btnGraph.classList.toggle('active', !isLog);
  if (zoomCtrl)  zoomCtrl.style.display  = isLog ? 'none' : 'flex';
  if (!isLog && selectedId) renderPipelineGraph(selectedId);
}

// ── Pipeline graph visualization ─────────────────────────────────────────────
function renderPipelineGraph(id) {
  var p = pipelines[id];
  if (!p || !p.dotSource) return;

  var stages = (p.state && p.state.stages) || [];

  // Only re-render when stage statuses change (avoid hammering /api/render)
  var sig = stages.map(function(s) { return s.nodeId + ':' + s.status; }).join('|');
  if (graphSigFor[id] === sig) return;
  graphSigFor[id] = sig;

  // Stamp this request so stale responses from a previous render are discarded
  var gen = (graphRenderGen[id] || 0) + 1;
  graphRenderGen[id] = gen;

  var dot = p.dotSource;

  // Inject white background + per-stage color overrides before the final closing brace
  var colorLines = '\n  graph [bgcolor="white"]';

  for (var i = 0; i < stages.length; i++) {
    var s = stages[i];
    if (!s.nodeId) continue;
    var fill, font, penwidth;
    if      (s.status === 'running')   { fill = '#e3b341'; font = '#0d1117'; penwidth = 3; }
    else if (s.status === 'completed') { fill = '#238636'; font = '#f0f6fc'; penwidth = 2; }
    else if (s.status === 'failed')    { fill = '#da3633'; font = '#f0f6fc'; penwidth = 2; }
    else if (s.status === 'retrying')   { fill = '#9e6a03'; font = '#f0f6fc'; penwidth = 2; }
    else if (s.status === 'diagnosing') { fill = '#e3b341'; font = '#0d1117'; penwidth = 3; }
    else if (s.status === 'repairing')  { fill = '#58a6ff'; font = '#0d1117'; penwidth = 3; }
    else continue;
    var safeId = s.nodeId.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    colorLines += '\n  "' + safeId + '" [style=filled fillcolor="' + fill + '" fontcolor="' + font + '" penwidth=' + penwidth + ']';
  }

  var lastBrace = dot.lastIndexOf('}');
  var coloredDot = lastBrace >= 0
    ? dot.slice(0, lastBrace) + colorLines + '\n' + dot.slice(lastBrace)
    : dot + colorLines;

  fetch('/api/render', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dotSource: coloredDot })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    // Discard if a newer render has been requested since this one was sent
    if (graphRenderGen[id] !== gen) return;
    var inner = document.getElementById('graphViewInner');
    if (!inner) return;
    if (resp.error) {
      inner.innerHTML = '<div class="pipeline-graph-error">' + esc(resp.error) + '</div>';
    } else {
      inner.innerHTML = resp.svg;
      var svg = inner.querySelector('svg');
      if (svg) { svg.removeAttribute('width'); svg.removeAttribute('height'); svg.style.width = '100%'; svg.style.height = 'auto'; svg.style.display = 'block'; }
      applyMonitorZoom();
    }
  })
  .catch(function() {});
}

// ── Graph zoom ───────────────────────────────────────────────────────────────
var createZoom = 1.0;
var monitorZoom = 1.0;
var ZOOM_STEP = 0.10;
var ZOOM_MIN = 0.25;
var ZOOM_MAX = 4.0;

function applyCreateZoom() {
  var svg = document.querySelector('#graphContent svg');
  if (!svg) return;
  svg.style.zoom = createZoom;
  svg.style.maxWidth = createZoom <= 1 ? '100%' : 'none';
  svg.style.height = 'auto';
  var label = document.getElementById('createZoomLabel');
  if (label) label.textContent = Math.round(createZoom * 100) + '%';
}

function zoomCreate(dir) {
  createZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, createZoom + dir * ZOOM_STEP));
  applyCreateZoom();
}

function resetCreateZoom() {
  createZoom = 1.0;
  applyCreateZoom();
}

function applyMonitorZoom() {
  var inner = document.getElementById('graphViewInner');
  if (!inner) return;
  var svg = inner.querySelector('svg');
  if (!svg) return;
  svg.style.width = (monitorZoom * 100) + '%';
  svg.style.height = 'auto';
  var label = document.getElementById('monitorZoomLabel');
  if (label) label.textContent = Math.round(monitorZoom * 100) + '%';
}

function zoomMonitor(dir) {
  monitorZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, monitorZoom + dir * ZOOM_STEP));
  applyMonitorZoom();
}

function resetMonitorZoom() {
  monitorZoom = 1.0;
  applyMonitorZoom();
}

function downloadArtifacts() {
  if (!selectedId) return;
  var a = document.createElement('a');
  a.href = '/api/download-artifacts?id=' + encodeURIComponent(selectedId);
  a.download = '';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function exportRun() {
  if (!selectedId) return;
  var a = document.createElement('a');
  a.href = '/api/export-run?id=' + encodeURIComponent(selectedId);
  a.download = '';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function cancelPipeline() {
  var btn = document.getElementById('cancelBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Cancelling\u2026';
  fetch('/api/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: selectedId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (!resp.cancelled) {
      btn.disabled = false;
      btn.innerHTML = '\u25A0\u2002Cancel';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '\u25A0\u2002Cancel';
  });
}

function pausePipeline() {
  var btn = document.getElementById('pauseBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Pausing\u2026';
  fetch('/api/pause', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: selectedId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (!resp.paused) {
      btn.disabled = false;
      btn.innerHTML = '&#9646;&#9646;&ensp;Pause';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '&#9646;&#9646;&ensp;Pause';
  });
}

function resumePipeline() {
  var btn = document.getElementById('resumeBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Resuming\u2026';
  fetch('/api/resume', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: selectedId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (!resp.id) {
      btn.disabled = false;
      btn.innerHTML = '&#9654;&ensp;Resume';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '&#9654;&ensp;Resume';
  });
}

function rerunPipeline() {
  var btn = document.getElementById('rerunBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Submitting\u2026';
  fetch('/api/rerun', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: selectedId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.id) {
      selectTab(resp.id);
    } else {
      btn.disabled = false;
      btn.innerHTML = '&#8635;&ensp;Re-run';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '&#8635;&ensp;Re-run';
  });
}

function archivePipeline() {
  var btn = document.getElementById('archiveBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Archiving\u2026';
  var archiveId = selectedId;
  fetch('/api/archive', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: archiveId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.archived) {
      // Optimistically mark as archived in local state so renderTabs hides it
      if (pipelines[archiveId] && pipelines[archiveId].state) {
        pipelines[archiveId].state.archived = true;
      }
      // Advance selectedId to next visible non-archived tab
      var ids = Object.keys(pipelines);
      var nextId = null;
      for (var i = 0; i < ids.length; i++) {
        if (ids[i] !== archiveId && !pipelines[ids[i]].state.archived) {
          nextId = ids[i];
        }
      }
      selectedId = nextId;
      panelBuiltFor = null;
      renderTabs();
      renderMain();
      kickPoll();
    } else {
      btn.disabled = false;
      btn.innerHTML = '&#8595;&ensp;Archive';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '&#8595;&ensp;Archive';
  });
}

function unarchivePipeline() {
  var btn = document.getElementById('unarchiveBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Unarchiving\u2026';
  var unarchiveId = selectedId;
  fetch('/api/unarchive', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: unarchiveId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.unarchived) {
      if (pipelines[unarchiveId] && pipelines[unarchiveId].state) {
        pipelines[unarchiveId].state.archived = false;
      }
      renderTabs();
      renderMain();
      kickPoll();
    } else {
      btn.disabled = false;
      btn.innerHTML = '&#8593;&ensp;Unarchive';
    }
  })
  .catch(function() {
    btn.disabled = false;
    btn.innerHTML = '&#8593;&ensp;Unarchive';
  });
}

function renderArchivedView() {
  var content = document.getElementById('archivedContent');
  if (!content) return;
  var ids = Object.keys(pipelines);
  var archived = ids.filter(function(id) { return pipelines[id].state && pipelines[id].state.archived; });
  if (archived.length === 0) {
    content.innerHTML = '<div class="archived-empty">No archived runs.</div>';
    return;
  }
  var html = '<table class="archived-table"><thead><tr>'
    + '<th>Pipeline</th><th>Status</th><th>Finished</th><th>Actions</th>'
    + '</tr></thead><tbody>';
  for (var i = archived.length - 1; i >= 0; i--) {
    var id = archived[i];
    var p = pipelines[id];
    var st = p.state || {};
    var name = st.pipeline || p.fileName;
    var status = st.status || 'idle';
    var finishedAt = st.finishedAt ? new Date(st.finishedAt).toLocaleString() : '\u2014';
    html += '<tr>'
      + '<td>' + esc(name) + '</td>'
      + '<td><span class="badge badge-' + esc(status) + '">' + esc(status) + '</span></td>'
      + '<td>' + esc(finishedAt) + '</td>'
      + '<td style="display:flex;gap:6px;flex-wrap:wrap;">'
      + '<button class="btn-rerun" onclick="viewArchivedRun(\'' + id + '\')">View</button>'
      + '<button class="btn-unarchive" onclick="unarchiveFromList(\'' + id + '\')">Unarchive</button>'
      + '<button class="btn-delete" onclick="showDeleteConfirm(\'' + id + '\')">&#10005;&ensp;Delete</button>'
      + '</td>'
      + '</tr>';
  }
  html += '</tbody></table>';
  content.innerHTML = html;
}

function viewArchivedRun(id) {
  selectedId = id;
  panelBuiltFor = null;
  showView('monitor');
  renderTabs();
  renderMain();
}

function unarchiveFromList(id) {
  fetch('/api/unarchive', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.unarchived) {
      if (pipelines[id] && pipelines[id].state) {
        pipelines[id].state.archived = false;
      }
      renderArchivedView();
      renderTabs();
      kickPoll();
    }
  })
  .catch(function() {});
}

// ── Delete pipeline run ───────────────────────────────────────────────────────
var pendingDeleteId = null;

function showDeleteConfirm(id) {
  pendingDeleteId = id;
  var p = pipelines[id];
  var name = (p && p.state && p.state.pipeline) ? p.state.pipeline : (p ? p.fileName : id);
  var nameEl = document.getElementById('deleteModalName');
  if (nameEl) nameEl.textContent = name;
  var btn = document.getElementById('deleteConfirmBtn');
  if (btn) { btn.disabled = false; btn.innerHTML = '&#10005;&ensp;Delete Permanently'; }
  var modal = document.getElementById('deleteModal');
  if (modal) modal.classList.remove('hidden');
}

function closeDeleteModal() {
  pendingDeleteId = null;
  var modal = document.getElementById('deleteModal');
  if (modal) modal.classList.add('hidden');
}

function executeDelete() {
  var id = pendingDeleteId;
  if (!id) return;
  var btn = document.getElementById('deleteConfirmBtn');
  if (btn) { btn.disabled = true; btn.textContent = 'Deleting\u2026'; }
  fetch('/api/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: id })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    closeDeleteModal();
    if (resp.deleted) {
      // Pick next visible tab before removing from local state
      var ids = Object.keys(pipelines);
      var nextId = null;
      for (var i = 0; i < ids.length; i++) {
        if (ids[i] !== id) nextId = ids[i];
      }
      delete pipelines[id];
      if (selectedId === id) {
        selectedId = nextId;
        panelBuiltFor = null;
      }
      renderTabs();
      renderMain();
      var archivedView = document.getElementById('viewArchived');
      if (archivedView && archivedView.style.display !== 'none') renderArchivedView();
    }
  })
  .catch(function() {
    closeDeleteModal();
  });
}

function runGenerated() {
  if (iterateSourceId) { runIterated(); return; }
  var dotSource = document.getElementById('dotPreview').value.trim();
  if (!dotSource) return;
  var simulate    = document.getElementById('createSimulate').checked;
  var autoApprove = document.getElementById('createAutoApprove').checked;
  var originalPrompt = document.getElementById('nlInput').value.trim();
  var btn = document.getElementById('runBtn');
  btn.disabled = true;
  btn.textContent = 'Submitting\u2026';

  fetch('/api/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dotSource: dotSource, fileName: 'generated.dot', simulate: simulate, autoApprove: autoApprove, originalPrompt: originalPrompt })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.error) {
      btn.disabled = false;
      btn.textContent = '\u25B6\u2002Run Pipeline';
      setGenStatus('error', 'Run failed: ' + resp.error);
    } else {
      resetCreatePage();
      selectedId = resp.id;
      showView('monitor');
      kickPoll();
    }
  })
  .catch(function(err) {
    btn.disabled = false;
    btn.textContent = '\u25B6\u2002Run Pipeline';
    setGenStatus('error', 'Request failed: ' + err);
  });
}

function resetCreatePage() {
  exitIterateMode();
  createZoom = 1.0;
  renderRetries = 0;
  clearTimeout(genDebounce);
  clearInterval(genCountdown);
  genDebounce = null;
  genCountdown = null;
  document.getElementById('nlInput').value = '';
  document.getElementById('dotPreview').value = '';
  document.getElementById('runBtn').disabled = true;
  document.getElementById('runBtn').textContent = '\u25B6\u2002Run Pipeline';
  setGenStatus('', 'Start typing to generate\u2026');
  document.getElementById('graphContent').innerHTML = '<div class="graph-placeholder">Generate a pipeline first to see the graph.</div>';
}

// ── Import run modal ─────────────────────────────────────────────────────────
function openImportModal() {
  var input = document.getElementById('importZipInput');
  if (input) input.value = '';
  var msg = document.getElementById('importMsg');
  if (msg) { msg.textContent = ''; msg.style.color = ''; }
  var btn = document.getElementById('importSubmitBtn');
  if (btn) btn.disabled = true;
  var modal = document.getElementById('importModal');
  if (modal) modal.classList.remove('hidden');
}

function closeImportModal() {
  var modal = document.getElementById('importModal');
  if (modal) modal.classList.add('hidden');
}

function onImportFileChange() {
  var input = document.getElementById('importZipInput');
  var btn = document.getElementById('importSubmitBtn');
  var msg = document.getElementById('importMsg');
  if (btn) btn.disabled = !(input && input.files && input.files.length > 0);
  if (msg) { msg.textContent = ''; msg.style.color = ''; }
}

function submitImport() {
  var input = document.getElementById('importZipInput');
  var btn = document.getElementById('importSubmitBtn');
  var msg = document.getElementById('importMsg');
  if (!input || !input.files || input.files.length === 0) return;
  var file = input.files[0];
  if (btn) { btn.disabled = true; btn.textContent = 'Starting\u2026'; }
  if (msg) { msg.textContent = ''; msg.style.color = ''; }
  var reader = new FileReader();
  reader.onload = function(e) {
    fetch('/api/import-run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: e.target.result
    })
    .then(function(r) { return r.json(); })
    .then(function(resp) {
      if (resp.error) {
        if (msg) { msg.textContent = 'Error: ' + resp.error; msg.style.color = '#f85149'; }
        if (btn) { btn.disabled = false; btn.textContent = 'Start Run'; }
      } else {
        closeImportModal();
        selectedId = resp.id;
        showView('monitor');
        kickPoll();
      }
    })
    .catch(function(err) {
      if (msg) { msg.textContent = 'Request failed: ' + String(err); msg.style.color = '#f85149'; }
      if (btn) { btn.disabled = false; btn.textContent = 'Start Run'; }
    });
  };
  reader.onerror = function() {
    if (msg) { msg.textContent = 'Failed to read file.'; msg.style.color = '#f85149'; }
    if (btn) { btn.disabled = false; btn.textContent = 'Start Run'; }
  };
  reader.readAsArrayBuffer(file);
}

// Close modal on overlay click
document.getElementById('errorDetailModal').addEventListener('click', function(e) {
  if (e.target === this) closeErrorModal();
});

// ── Fireworks ────────────────────────────────────────────────────────────────
function triggerFireworks() {
  if (document.getElementById('fireworksCanvas')) return;
  var canvas = document.createElement('canvas');
  canvas.id = 'fireworksCanvas';
  canvas.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;pointer-events:none;z-index:9999;';
  document.body.appendChild(canvas);
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  var ctx = canvas.getContext('2d');

  var COLORS = ['#ff3e3e','#ff7043','#ffd600','#ffe57f','#69ff47','#18ffff','#40c4ff','#e040fb','#ff4081','#ff6ec7','#b2ff59','#ffffff','#ff9100','#ea80fc','#00e5ff','#76ff03'];
  var rockets = [], sparks = [];
  var startTime = Date.now();
  var SHOW_MS = 5000;   // total show duration
  var LAUNCH_MS = 3400; // stop launching new rockets after this

  function randColor() { return COLORS[Math.floor(Math.random() * COLORS.length)]; }

  function Rocket() {
    this.x = canvas.width * (0.1 + Math.random() * 0.8);
    this.y = canvas.height;
    this.vx = (Math.random() - 0.5) * 2.5;
    this.vy = -(Math.random() * 4 + 6);
    this.color = randColor();
    this.trail = [];
  }

  function burst(x, y, color) {
    var n = Math.floor(Math.random() * 80 + 120);
    var secColor = randColor();
    for (var i = 0; i < n; i++) {
      var angle = (i / n) * Math.PI * 2 + Math.random() * 0.5;
      var speed = Math.random() * 5 + 2;
      var c = i % 3 === 0 ? secColor : (Math.random() < 0.15 ? randColor() : color);
      sparks.push({
        x: x, y: y,
        vx: Math.cos(angle) * speed,
        vy: Math.sin(angle) * speed,
        alpha: 1,
        decay: Math.random() * 0.012 + 0.007,
        size: Math.random() * 4 + 1.5,
        color: c
      });
    }
    // bright flash ring
    for (var j = 0; j < 28; j++) {
      var a2 = (j / 28) * Math.PI * 2;
      sparks.push({
        x: x, y: y,
        vx: Math.cos(a2) * (Math.random() * 3 + 9),
        vy: Math.sin(a2) * (Math.random() * 3 + 9),
        alpha: 1,
        decay: 0.022,
        size: 3,
        color: '#fff'
      });
    }
    // glitter layer — tiny fast sparks in a third color
    var glitColor = randColor();
    for (var k = 0; k < 40; k++) {
      var ga = Math.random() * Math.PI * 2;
      var gs = Math.random() * 6 + 3;
      sparks.push({
        x: x, y: y,
        vx: Math.cos(ga) * gs,
        vy: Math.sin(ga) * gs,
        alpha: 0.85,
        decay: Math.random() * 0.025 + 0.018,
        size: Math.random() * 1.5 + 0.5,
        color: glitColor
      });
    }
  }

  function launch() {
    rockets.push(new Rocket());
    if (Math.random() < 0.4) rockets.push(new Rocket()); // occasional double
  }

  launch();
  var elapsed = 0;
  var launchTimer = setInterval(function() {
    elapsed += 450;
    launch();
    if (elapsed >= LAUNCH_MS) clearInterval(launchTimer);
  }, 450);

  function frame() {
    var now = Date.now();
    var age = now - startTime;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Rockets
    for (var i = rockets.length - 1; i >= 0; i--) {
      var r = rockets[i];
      r.trail.push({x: r.x, y: r.y});
      if (r.trail.length > 10) r.trail.shift();
      r.x += r.vx;
      r.y += r.vy;
      r.vy += 0.12;
      // draw trail
      for (var t = 0; t < r.trail.length; t++) {
        ctx.beginPath();
        ctx.arc(r.trail[t].x, r.trail[t].y, 1.8 * (t / r.trail.length), 0, Math.PI * 2);
        ctx.fillStyle = r.color;
        ctx.globalAlpha = (t / r.trail.length) * 0.55;
        ctx.fill();
      }
      // rocket head
      ctx.beginPath();
      ctx.arc(r.x, r.y, 2.5, 0, Math.PI * 2);
      ctx.fillStyle = '#fff';
      ctx.globalAlpha = 1;
      ctx.fill();
      // explode at apex
      if (r.vy >= -0.5) {
        burst(r.x, r.y, r.color);
        rockets.splice(i, 1);
      }
    }

    // Sparks
    for (var j = sparks.length - 1; j >= 0; j--) {
      var s = sparks[j];
      s.x += s.vx;
      s.y += s.vy;
      s.vy += 0.07;
      s.vx *= 0.97;
      s.alpha -= s.decay;
      if (s.alpha <= 0) { sparks.splice(j, 1); continue; }
      ctx.beginPath();
      ctx.arc(s.x, s.y, s.size, 0, Math.PI * 2);
      ctx.fillStyle = s.color;
      ctx.globalAlpha = s.alpha;
      ctx.fill();
    }

    ctx.globalAlpha = 1;

    if (age < SHOW_MS || rockets.length > 0 || sparks.length > 0) {
      requestAnimationFrame(frame);
    } else {
      canvas.remove();
    }
  }
  requestAnimationFrame(frame);
}
</script>
<!-- Artifact browser modal (outside all panels, always in DOM) -->
<div class="artifact-overlay" id="artifactOverlay" onclick="if(event.target===this)closeArtifacts()">
  <div class="artifact-dialog">
    <div class="artifact-dialog-hdr">
      <span id="artifactTitle">Artifacts</span>
      <button class="artifact-dialog-close" onclick="closeArtifacts()">&#x2715;</button>
    </div>
    <div class="artifact-body">
      <div class="artifact-files" id="artifactFiles"></div>
      <div class="artifact-view" id="artifactView">Select a file to view</div>
    </div>
  </div>
</div>
</body>
</html>"""
}
