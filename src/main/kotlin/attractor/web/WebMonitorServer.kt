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

class WebMonitorServer(private val requestedPort: Int, private val registry: ProjectRegistry, private val store: RunStore) {

    private val dotGenerator = DotGenerator(store)

    private companion object {
        val dotSanitizePass1 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"(?:[^"\\]|\\.)*"""")
        val dotSanitizePass2 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"[^"]*\z""")
        val dotSanitizePass3 = Regex("""\[\s*,\s*""")
    }

    private val httpServer = HttpServer.create(InetSocketAddress(requestedPort), 0)
    val port: Int get() = httpServer.address.port
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
        val appVersion   = WebMonitorServer::class.java.`package`?.implementationVersion ?: "dev"
        val baseVersion  = System.getenv("ATTRACTOR_BASE_VERSION")  ?: "dev"
        val imageVersion = System.getenv("ATTRACTOR_IMAGE_VERSION") ?: "dev"
        val cachedDashboard = dashboardHtml(appVersion, baseVersion, imageVersion).toByteArray()
        httpServer.createContext("/") { ex ->
            if (ex.requestMethod == "GET" && ex.requestURI.path == "/") {
                respond(ex, 200, "text/html; charset=utf-8", cachedDashboard)
            } else {
                ex.sendResponseHeaders(404, 0)
                ex.responseBody.close()
            }
        }

        // ── All projects JSON snapshot ──────────────────────────────────────
        httpServer.createContext("/api/projects") { ex ->
            if (ex.requestMethod == "GET") {
                val body = allProjectsJson().toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }

        // ── Single project view (on-demand hydration from DB) ───────────────
        // GET /api/project-view?id={runId}
        httpServer.createContext("/api/project-view") { ex ->
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
            val body = "{\"id\":${js(entry.id)},\"fileName\":${js(entry.fileName)},\"dotSource\":${js(entry.dotSource)},\"originalPrompt\":${js(entry.originalPrompt)},\"familyId\":${js(entry.familyId)},\"simulate\":${entry.options.simulate},\"autoApprove\":${entry.options.autoApprove},\"logsRoot\":${js(entry.logsRoot)},\"displayName\":${js(entry.displayName)},\"isHydratedViewOnly\":${entry.isHydratedViewOnly},\"state\":${entry.state.toJson()}}".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }

        // ── Submit and run a project ────────────────────────────────────────
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
                val fileName = jsonField(body, "fileName").ifEmpty { "project.dot" }
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
                val id = ProjectRunner.submit(dotSource, fileName, options, registry, store, originalPrompt) {
                    broadcastUpdate()
                }

                println("[attractor] Project submitted: $id ($fileName)")
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

        // ── Re-run an existing project ──────────────────────────────────────
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
                    val err = """{"error":"Project not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                ProjectRunner.resubmit(id, registry, store) { broadcastUpdate() }
                println("[attractor] Project re-run (in-place): $id")
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

        // ── Cancel a running project ─────────────────────────────────────────
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
                println("[attractor] Project cancel requested: $id (cancelled=$cancelled)")
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

        // ── Pause a running project ──────────────────────────────────────────
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
                println("[attractor] Project pause requested: $id (paused=$paused)")
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

        // ── Resume a paused project ──────────────────────────────────────────
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
                    val err = """{"error":"Project not found"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(404, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                if (entry.state.status.get() != "paused") {
                    val err = """{"error":"Project is not paused"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }
                ProjectRunner.resumeProject(id, registry, store) { broadcastUpdate() }
                println("[attractor] Project resume requested: $id")
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

        // ── Archive a project run ────────────────────────────────────────────
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
                val familyId = jsonField(body, "familyId")
                val archived = when {
                    familyId.isNotBlank() -> registry.archiveFamily(familyId)
                    else -> registry.archive(id)
                }
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

        // ── Unarchive a project run ──────────────────────────────────────────
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

        // ── Permanently delete a project run and its artifacts ──────────────
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
                val familyId = jsonField(body, "familyId")
                if (familyId.isNotBlank()) {
                    // Family delete: refuse if any member is running/paused
                    val members = registry.getAll().filter { it.familyId == familyId }
                    val blocked = members.any { it.state.status.get().let { s -> s == "running" || s == "paused" } }
                    if (blocked) {
                        val err = """{"error":"Cannot delete a running or paused project"}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(400, err.size.toLong())
                        ex.responseBody.use { it.write(err) }
                        return@createContext
                    }
                    val (deleted, logsRoots) = registry.deleteFamily(familyId)
                    logsRoots.forEach { lr ->
                        val lrFile = java.io.File(lr)
                        runCatching { lrFile.deleteRecursively() }
                        lrFile.parentFile?.listFiles { f -> f.name.startsWith(lrFile.name + "-restart-") }
                            ?.forEach { runCatching { it.deleteRecursively() } }
                    }
                    broadcastUpdate()
                    println("[attractor] Project family deleted: $familyId (${logsRoots.size} runs)")
                    val resp = """{"deleted":$deleted}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                } else {
                    val entry = registry.get(id)
                    if (entry == null) {
                        val err = """{"error":"Project not found"}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(404, err.size.toLong())
                        ex.responseBody.use { it.write(err) }
                        return@createContext
                    }
                    val status = entry.state.status.get()
                    if (status == "running" || status == "paused") {
                        val err = """{"error":"Cannot delete a running or paused project"}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(400, err.size.toLong())
                        ex.responseBody.use { it.write(err) }
                        return@createContext
                    }
                    val (deleted, logsRoot) = registry.delete(id)
                    if (deleted && logsRoot.isNotBlank()) {
                        val lrFile = java.io.File(logsRoot)
                        runCatching { lrFile.deleteRecursively() }
                        lrFile.parentFile?.listFiles { f -> f.name.startsWith(lrFile.name + "-restart-") }
                            ?.forEach { runCatching { it.deleteRecursively() } }
                    }
                    broadcastUpdate()
                    println("[attractor] Project deleted: $id (logsRoot=${logsRoot.ifBlank { "none" }})")
                    val resp = """{"deleted":$deleted}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, resp.size.toLong())
                    ex.responseBody.use { it.write(resp) }
                }
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

        // ── Describe DOT project in natural language (SSE) ─────────────────
        httpServer.createContext("/api/describe-dot/stream") { ex ->
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
                    dotGenerator.describeStream(dotSource) { delta ->
                        val line = "data: {\"delta\":${js(delta)}}\n\n"
                        out.write(line.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val done = "data: {\"done\":true}\n\n"
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
                    val err = """{"error":"Project not found"}""".toByteArray()
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
                    val err = """{"error":"Project is currently running"}""".toByteArray()
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
                val newId = ProjectRunner.submit(
                    dotSource = dotSource,
                    fileName = sourceEntry.fileName,
                    options = sourceEntry.options,
                    registry = registry,
                    store = store,
                    originalPrompt = originalPrompt,
                    familyId = familyId,
                    displayNameOverride = sourceEntry.displayName
                ) { broadcastUpdate() }
                println("[attractor] Project iterated (new run): $sourceId -> $newId (family: $familyId)")
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

        // ── Project family: all runs sharing the same familyId ──────────────
        // GET /api/project-family?id={runId}
        httpServer.createContext("/api/project-family") { ex ->
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
                val msg = """{"error":"Project not found"}""".toByteArray()
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
                val msg = """{"error":"Project not found"}""".toByteArray()
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
        // GET /api/stage-log?id={projectId}&stage={nodeId}
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
            val projectId = params["id"] ?: ""
            val stageNodeId = params["stage"] ?: ""
            val entry = registry.get(projectId)
            val logsRoot = entry?.logsRoot ?: ""
            val logFile = if (logsRoot.isNotBlank() && stageNodeId.isNotBlank())
                java.io.File(logsRoot, "$stageNodeId/live.log") else null
            val content = when {
                projectId.isBlank() || stageNodeId.isBlank() -> "(missing id or stage parameter)"
                logFile == null || !logFile.exists() -> "(no log yet — stage may not have started)"
                else -> logFile.readText()
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        // ── Export a project run as a self-contained ZIP ────────────────────
        // GET /api/export-run?id={projectId}
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
            val projectId = params["id"] ?: ""
            val entry = registry.get(projectId)
            if (projectId.isBlank() || entry == null) {
                val msg = """{"error":"Project not found"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val run = store.getById(projectId)
            if (run == null) {
                val msg = """{"error":"Run not found in database"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(404, msg.size.toLong())
                ex.responseBody.use { it.write(msg) }
                return@createContext
            }
            val metaJson = buildString {
                append("{")
                append("\"id\":${js(run.id)},")
                append("\"fileName\":${js(run.fileName)},")
                append("\"dotSource\":${js(run.dotSource)},")
                append("\"status\":${js(run.status)},")
                append("\"simulate\":${run.simulate},")
                append("\"autoApprove\":${run.autoApprove},")
                append("\"createdAt\":${run.createdAt},")
                append("\"projectLog\":${js(run.projectLog)},")
                append("\"archived\":${run.archived},")
                append("\"originalPrompt\":${js(run.originalPrompt)},")
                append("\"finishedAt\":${run.finishedAt},")
                append("\"displayName\":${js(run.displayName)},")
                append("\"familyId\":${js(run.familyId)}")
                append("}")
            }
            val safeName = (run.displayName.ifBlank { run.fileName.removeSuffix(".dot") })
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            val idSuffix = projectId.takeLast(8)
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"project-$safeName-$idSuffix.zip\"")
            ex.sendResponseHeaders(200, 0)
            try {
                java.util.zip.ZipOutputStream(ex.responseBody).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry("project-meta.json"))
                    zip.write(metaJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    val logsRoot = run.logsRoot
                    if (logsRoot.isNotBlank()) {
                        val rootFile = java.io.File(logsRoot)
                        if (rootFile.exists()) {
                            rootFile.walkTopDown().filter { it.isFile }.forEach { file ->
                                val entryName = "artifacts/" + rootFile.toPath().relativize(file.toPath()).toString()
                                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* client disconnected */ }
        }

        // ── Import a project run from an exported ZIP ───────────────────────
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
                val query = ex.requestURI.query ?: ""
                val params = query.split("&").associate { p ->
                    val kv = p.split("=", limit = 2)
                    (kv.getOrElse(0) { "" }) to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
                }
                val onConflict = params["onConflict"] ?: "skip"

                var metaText: String? = null
                val artifactFiles = mutableMapOf<String, ByteArray>()
                try {
                    java.util.zip.ZipInputStream(ex.requestBody).use { zis ->
                        var zipEntry = zis.nextEntry
                        while (zipEntry != null) {
                            val name = zipEntry.name.trimStart('/')
                            when {
                                name == "project-meta.json" ->
                                    metaText = zis.readBytes().toString(Charsets.UTF_8)
                                name.startsWith("artifacts/") && !zipEntry.isDirectory -> {
                                    val relPath = name.removePrefix("artifacts/")
                                    if (relPath.isNotBlank()) artifactFiles[relPath] = zis.readBytes()
                                }
                            }
                            zis.closeEntry()
                            zipEntry = zis.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    val err = """{"error":"Invalid or corrupt zip: ${e.message?.take(120)?.replace("\"", "'")}"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                if (metaText == null) {
                    val err = """{"error":"project-meta.json not found in zip"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                val fileName  = jsonField(metaText!!, "fileName")
                val dotSource = jsonField(metaText!!, "dotSource")
                if (fileName.isBlank() || dotSource.isBlank()) {
                    val err = """{"error":"Missing required field(s) in project-meta.json: fileName, dotSource"}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(400, err.size.toLong())
                    ex.responseBody.use { it.write(err) }
                    return@createContext
                }

                val importFamilyId = jsonField(metaText!!, "familyId")
                if (onConflict == "skip") {
                    val existing = registry.get(importFamilyId)
                    if (existing != null) {
                        val resp = """{"status":"skipped","id":${js(importFamilyId)}}""".toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/json")
                        ex.sendResponseHeaders(200, resp.size.toLong())
                        ex.responseBody.use { it.write(resp) }
                        return@createContext
                    }
                }

                val newId          = java.util.UUID.randomUUID().toString()
                val simulate       = jsonBool(metaText!!, "simulate")
                val autoApprove    = jsonBool(metaText!!, "autoApprove", default = true)
                val originalPrompt = jsonField(metaText!!, "originalPrompt")
                val status         = jsonField(metaText!!, "status").ifBlank { "completed" }
                val createdAt      = jsonLong(metaText!!, "createdAt", default = System.currentTimeMillis())
                val projectLog     = jsonField(metaText!!, "projectLog")
                val archived       = jsonBool(metaText!!, "archived")
                val finishedAt     = jsonLong(metaText!!, "finishedAt")
                val displayName    = jsonField(metaText!!, "displayName")

                val logsRoot = if (artifactFiles.isNotEmpty()) {
                    val safeName = displayName.ifBlank { fileName.removeSuffix(".dot") }
                        .replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifBlank { newId }
                    val destDir = java.io.File("projects/$safeName")
                    destDir.mkdirs()
                    for ((relPath, bytes) in artifactFiles) {
                        val destFile = java.io.File(destDir, relPath)
                        destFile.parentFile?.mkdirs()
                        destFile.writeBytes(bytes)
                    }
                    destDir.path
                } else ""

                val run = attractor.db.StoredRun(
                    id             = newId,
                    fileName       = fileName,
                    dotSource      = dotSource,
                    status         = if (status == "running") "failed" else status,
                    logsRoot       = logsRoot,
                    simulate       = simulate,
                    autoApprove    = autoApprove,
                    createdAt      = createdAt,
                    projectLog     = projectLog,
                    archived       = archived,
                    originalPrompt = originalPrompt,
                    finishedAt     = finishedAt,
                    displayName    = displayName,
                    familyId       = importFamilyId.ifBlank { newId }
                )
                store.insertOrReplaceImported(run)
                registry.upsertImported(run)
                broadcastUpdate()
                println("[attractor] Project imported (restored): $newId ($fileName)")
                val resp = """{"status":"imported","id":${js(newId)}}""".toByteArray()
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
                val execMode   = store.getSetting("execution_mode") ?: "api"
                val anthEnabled = store.getSetting("provider_anthropic_enabled") ?: "false"
                val oaiEnabled  = store.getSetting("provider_openai_enabled") ?: "false"
                val gemEnabled  = store.getSetting("provider_gemini_enabled") ?: "false"
                val copilotEnabled = store.getSetting("provider_copilot_enabled") ?: "false"
                val customEnabled = store.getSetting("provider_custom_enabled") ?: "false"
                val anthCmd    = store.getSetting("cli_anthropic_command") ?: "claude --dangerously-skip-permissions -p {prompt}"
                val oaiCmd     = store.getSetting("cli_openai_command") ?: "codex exec --full-auto {prompt}"
                val gemCmd     = store.getSetting("cli_gemini_command") ?: "gemini --yolo -p {prompt}"
                val copilotCmd = store.getSetting("cli_copilot_command") ?: "copilot --allow-all-tools -p {prompt}"
                val customHost  = store.getSetting("custom_api_host")  ?: "http://localhost"
                val customPort  = store.getSetting("custom_api_port")  ?: "11434"
                val customKey   = store.getSetting("custom_api_key")   ?: ""
                val customModel = store.getSetting("custom_api_model") ?: "llama3.2"
                val body = """{
                    "execution_mode":${js(execMode)},
                    "provider_anthropic_enabled":$anthEnabled,
                    "provider_openai_enabled":$oaiEnabled,
                    "provider_gemini_enabled":$gemEnabled,
                    "provider_copilot_enabled":$copilotEnabled,
                    "provider_custom_enabled":$customEnabled,
                    "cli_anthropic_command":${js(anthCmd)},
                    "cli_openai_command":${js(oaiCmd)},
                    "cli_gemini_command":${js(gemCmd)},
                    "cli_copilot_command":${js(copilotCmd)},
                    "custom_api_host":${js(customHost)},
                    "custom_api_port":${js(customPort)},
                    "custom_api_key":${js(customKey)},
                    "custom_api_model":${js(customModel)}
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
            val anthCmd    = store.getSetting("cli_anthropic_command") ?: "claude --dangerously-skip-permissions -p {prompt}"
            val oaiCmd     = store.getSetting("cli_openai_command") ?: "codex exec --full-auto {prompt}"
            val gemCmd     = store.getSetting("cli_gemini_command") ?: "gemini --yolo -p {prompt}"
            val copilotCmd = store.getSetting("cli_copilot_command") ?: "copilot --allow-all-tools -p {prompt}"
            val body = """{
                "anthropic":${detectBinary(anthCmd)},
                "openai":${detectBinary(oaiCmd)},
                "gemini":${detectBinary(gemCmd)},
                "copilot":${detectBinary(copilotCmd)}
            }""".trimIndent().replace("\n", "").replace("    ", "").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        // ── API key detection ─────────────────────────────────────────────────
        httpServer.createContext("/api/settings/api-key-status") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            val env = System.getenv()
            val anthropic = (env["ATTRACTOR_ANTHROPIC_API_KEY"] ?: "").isNotBlank()
            val openai    = (env["ATTRACTOR_OPENAI_API_KEY"] ?: "").isNotBlank()
            val gemini    = (env["ATTRACTOR_GEMINI_API_KEY"] ?: env["ATTRACTOR_GOOGLE_API_KEY"] ?: "").isNotBlank()
            val customHost  = store.getSetting("custom_api_host") ?: "http://localhost"
            val customPort  = store.getSetting("custom_api_port") ?: "11434"
            val customBaseUrl = if (customPort.isBlank()) customHost else "$customHost:$customPort"
            val customReachable = try {
                val url = java.net.URI("$customBaseUrl/v1/models").toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000; conn.readTimeout = 2000
                val code = conn.responseCode
                conn.disconnect()
                code in 200..499  // treat any HTTP response (even 401/404) as reachable
            } catch (_: Exception) { false }
            val body = """{"anthropic":$anthropic,"openai":$openai,"gemini":$gemini,"custom":$customReachable}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        // ── System tool detection ─────────────────────────────────────────────
        httpServer.createContext("/api/settings/system-tools-status") { ex ->
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            if (ex.requestMethod != "GET") {
                ex.sendResponseHeaders(405, 0); ex.responseBody.close(); return@createContext
            }
            fun probe(binary: String, vararg args: String, env: Map<String, String> = emptyMap()): Boolean = try {
                val pb = ProcessBuilder(binary, *args).redirectErrorStream(true)
                if (env.isNotEmpty()) pb.environment().putAll(env)
                pb.start().waitFor() == 0
            } catch (_: Exception) { false }
            val results = linkedMapOf(
                "git"     to probe("git",      "--version"),
                "dot"     to probe("dot",      "-V"),
                "python3" to (probe("python3", "--version") || probe("python", "--version")),
                "ruby"    to probe("ruby",     "--version"),
                "java"    to probe("java",     "-version"),
                "node"    to probe("node",     "--version"),
                "go"      to probe("go",       "version"),
                "rustc"   to probe("rustc",    "--version"),
                "gcc"     to probe("gcc",      "--version"),
                "gxx"     to probe("g++",      "--version"),
                "clang"   to probe("clang",    "--version"),
                "clangxx" to probe("clang++",  "--version"),
                "make"    to probe("make",     "--version"),
                // gradle writes to GRADLE_USER_HOME (~/.gradle by default); the container user
                // has no home directory, so point it at a writable temp path instead.
                "gradle"  to probe("gradle",   "--version", env = mapOf("GRADLE_USER_HOME" to "/tmp/.gradle")),
                "mvn"     to probe("mvn",      "--version"),
                "docker"  to probe("docker",   "--version"),
                "curl"    to probe("curl",     "--version")
            )
            val body = "{${results.entries.joinToString(",") { (k,v) -> "\"$k\":$v" }}}".toByteArray()
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
            client.offer(allProjectsJson())          // initial snapshot
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

        val restApi = RestApiRouter(registry, store, { broadcastUpdate() }, { allProjectsJson() }, restSseClients)
        httpServer.createContext("/api/v1/") { ex -> restApi.handle(ex) }

    }

    fun broadcastUpdate() {
        val json = allProjectsJson()
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

    private fun allProjectsJson(): String {
        val sb = StringBuilder()
        sb.append("{\"projects\":[")
        val all = registry.getAll()
        all.forEachIndexed { i, entry ->
            if (i > 0) sb.append(",")
            sb.append("{\"id\":${js(entry.id)},\"fileName\":${js(entry.fileName)},\"dotSource\":${js(entry.dotSource)},\"originalPrompt\":${js(entry.originalPrompt)},\"familyId\":${js(entry.familyId)},\"simulate\":${entry.options.simulate},\"autoApprove\":${entry.options.autoApprove},\"logsRoot\":${js(entry.logsRoot)},\"displayName\":${js(entry.displayName)},\"isHydratedViewOnly\":${entry.isHydratedViewOnly},\"state\":${entry.state.toJson()}}")
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
     * Strip project-semantics attributes (prompt, goal, goal_gate) from DOT before passing
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

    private fun dashboardHtml(appVersion: String, baseVersion: String, imageVersion: String): String = """<!DOCTYPE html>
<html lang="en">
<head>
<script>(function(){var t=localStorage.getItem('attractor-theme')||'light';document.documentElement.setAttribute('data-theme',t);document.documentElement.style.colorScheme=t;})();</script>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Attractor</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%234f46e5'/%3E%3Ctext x='50%25' y='50%25' font-size='20' text-anchor='middle' dominant-baseline='central'%3E%E2%9A%A1%3C/text%3E%3C/svg%3E">
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
  --text-muted: #3f3f46;
  --text-faint: #52525b;
  --text-dim: #71717a;
  --accent: #4f46e5;
  --code-bg: #f0f0f3;
  --code-text: #27272a;
  --dot-bg: #f4f4f6;
  --dot-text: #3730a3;
  --graph-bg: #ffffff;
  --badge-idle-bg: #e9e9ec;
  --badge-idle-fg: #52525b;
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
body { font-family: 'Figtree', 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; padding-bottom: 28px; }
button { font-variant-emoji: text; }

/* Header */
#topChrome { position: sticky; top: 0; z-index: 100; }
header { background: var(--surface); border-bottom: 1px solid var(--border); padding: 12px 20px; display: flex; align-items: center; gap: 12px; }
#agentWarningBanner, #requiredToolsWarningBanner { display:flex; align-items:center; gap:10px; padding:9px 20px; background:#7c3500; color:#fde8c8; font-size:0.85rem; border-bottom:1px solid #a04800; }
header h1 { font-size: 1.05rem; font-weight: 600; color: var(--text-strong); flex: 1; }
.conn-dot { width: 10px; height: 10px; border-radius: 50%; background: radial-gradient(circle at 35% 32%, #b0b8c4, #6e7681 55%, #3a3f47); box-shadow: 0 1px 3px rgba(0,0,0,0.5), inset 0 -1px 2px rgba(0,0,0,0.3); }
.conn-dot.live    { background: radial-gradient(circle at 35% 32%, #80ffaa, #34d058 50%, #137a2e); box-shadow: 0 1px 5px rgba(0,220,80,0.7), inset 0 -1px 2px rgba(0,0,0,0.25); animation: pulse 2s infinite; }
.conn-dot.offline { background: radial-gradient(circle at 35% 32%, #ff9090, #f85149 50%, #a01020); box-shadow: 0 1px 5px rgba(248,60,60,0.7), inset 0 -1px 2px rgba(0,0,0,0.25); animation: pulse 1.4s infinite; }

/* Tab bar */
.tab-bar { background: var(--surface); border-bottom: 1px solid var(--border); padding: 0 20px; display: flex; align-items: flex-end; gap: 2px; overflow-x: auto; min-height: 40px; }
.tab { padding: 8px 14px; border-radius: 6px 6px 0 0; font-size: 0.78rem; cursor: pointer; border: 1px solid transparent; border-bottom: none; color: var(--text-muted); white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.tab:hover { color: var(--text); background: var(--surface-raised); }
.tab.active { background: var(--bg); border-color: var(--border); color: var(--text-strong); }
.tab-empty { padding: 10px 16px; font-size: 0.78rem; color: var(--text-faint); font-style: italic; align-self: center; }
.tab-close { margin-left: 4px; opacity: 0.45; font-size: 0.85rem; line-height: 1; padding: 1px 3px; border-radius: 3px; cursor: pointer; flex-shrink: 0; }
.tab-close:hover { opacity: 1; background: rgba(128,128,128,0.25); }
.tab-status-icon { font-style: normal; font-size: 1em; flex-shrink: 0; line-height: 1; }
.tab-status-icon.running { animation: spin 1.1s linear infinite; display: inline-block; }
.tab-si-idle      { color: var(--text-faint); }
.tab-si-running   { color: var(--badge-running-fg); }
.tab-si-completed { color: var(--badge-completed-fg); }
.tab-si-failed    { color: var(--badge-failed-fg); }
.tab-si-cancelled { color: var(--text-muted); }
.tab-si-paused    { color: var(--accent); }

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
.badge-dot-checking { color: #d97706; animation: pulse 0.9s infinite; display:inline-block; }
#app-footer { position:fixed; bottom:0; left:0; right:0; height:28px; display:flex; align-items:center; justify-content:space-between; padding:0 16px; background:var(--surface); border-top:1px solid var(--border); font-size:0.72rem; color:var(--text-muted); z-index:99; }
#app-footer .footer-left { display:flex; align-items:center; gap:8px; }
#app-footer .footer-right { display:flex; align-items:center; gap:16px; }
#footerRunCount { color:var(--accent,#4f46e5); font-weight:600; display:none; }
#toast { position:fixed; top:60px; right:28px; display:flex; align-items:center; gap:10px; padding:12px 16px 12px 14px; border-radius:10px; font-size:0.84rem; font-weight:500; min-width:200px; max-width:320px; box-shadow:0 8px 32px rgba(0,0,0,0.22), 0 1px 4px rgba(0,0,0,0.12); z-index:9999; opacity:0; transform:translateY(-12px) scale(0.97); transition:opacity 0.22s ease, transform 0.22s ease; pointer-events:none; border-left:3px solid transparent; }
#toast.toast-show { opacity:1; transform:translateY(0) scale(1); }
#toast.toast-success { background:#f0faf4; color:#166534; border-left-color:#22c55e; box-shadow:0 8px 32px rgba(34,197,94,0.12), 0 1px 4px rgba(0,0,0,0.08); }
#toast.toast-error   { background:#fef2f2; color:#991b1b; border-left-color:#ef4444; box-shadow:0 8px 32px rgba(239,68,68,0.12), 0 1px 4px rgba(0,0,0,0.08); }
#toast .toast-icon { font-size:1rem; flex-shrink:0; line-height:1; }
#toast .toast-text { line-height:1.4; }
.api-key-hint { display:inline-flex; align-items:center; justify-content:center; width:13px; height:13px; border-radius:50%; background:var(--surface-muted); border:1px solid var(--border); color:var(--text-muted); font-size:0.65rem; font-weight:700; cursor:default; margin-left:4px; vertical-align:middle; position:relative; }
.api-key-hint:hover::after { content:attr(data-tip); position:absolute; right:0; top:calc(100% + 5px); background:#1a1a2e; color:#f0f0f0; font-size:0.75rem; font-weight:400; white-space:nowrap; padding:5px 9px; border-radius:6px; border:1px solid #444; box-shadow:0 4px 12px rgba(0,0,0,0.35); z-index:100; pointer-events:none; }

/* Main content */
main { max-width: 1200px; margin: 0 auto; padding: 20px; display: block; }
.runs-layout { display: flex; gap: 20px; align-items: flex-start; }
.runs-main { flex: 1; min-width: 0; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 18px; }
.card h2 { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; margin-bottom: 14px; }
.panel-header { display: flex; align-items: center; gap: 12px; margin-bottom: 4px; }
.project-title { font-size: 1.4rem; font-weight: 700; color: var(--text-strong); word-break: break-word; display: flex; align-items: center; gap: 8px; }
.status-icon { font-style: normal; font-size: 1.1rem; flex-shrink: 0; line-height: 1; }
.status-icon.running { animation: spin 1.1s linear infinite; display: inline-block; }
.project-meta  { font-size: 0.78rem; color: var(--text-faint); margin-bottom: 0; }
.action-bar { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 0; border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); margin: 14px 0 18px; min-height: 42px; }
.action-bar-primary { display: flex; gap: 8px; align-items: center; }
.action-bar-secondary { display: flex; gap: 8px; align-items: center; }
.action-bar button { line-height: 1; }
.project-desc-block { background: var(--surface-muted); border: 1px solid var(--border); border-radius: 6px; padding: 10px 14px; margin-bottom: 10px; }
.project-desc-label { font-size: 0.75rem; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.07em; margin-bottom: 6px; }
.project-desc-block #projectDescText { font-size: 0.8rem; color: var(--text); line-height: 1.6; white-space: pre-wrap; word-break: break-word; cursor: default; user-select: text; }
.stage-list { display: flex; flex-direction: column; gap: 6px; }
.stage { display: flex; flex-direction: column; align-items: stretch; gap: 0; border-radius: 6px; background: var(--surface-raised); border: 1px solid var(--border); }
.stage.running   { border-color: #f78166; }
.stage.completed { border-color: #238636; }
.stage.failed    { border-color: #da3633; }
.stage.retrying   { border-color: #d29922; }
.stage.diagnosing { border-color: #e3b341; }
.stage.repairing  { border-color: #58a6ff; }
.stage-row { display: flex; align-items: center; gap: 8px; padding: 9px 13px; }
.stage-icon { font-size: 0.95rem; width: 20px; text-align: center; flex-shrink: 0; }
.stage-name { flex: 1; font-size: 0.88rem; }
.stage-name.not-run { text-decoration: line-through; color: var(--text-faint); }
.stage-logs-slot { width: 62px; flex-shrink: 0; display: flex; justify-content: flex-end; align-items: center; }
.stage-dur  { width: 48px; flex-shrink: 0; text-align: right; font-size: 0.72rem; color: var(--text-faint); white-space: nowrap; font-variant-numeric: tabular-nums; }
.stage-na   { font-size: 0.72rem; color: var(--text-faint); }
.stage-total { width: auto; flex-shrink: 0; text-align: right; font-size: 0.72rem; color: var(--text-muted); white-space: nowrap; font-variant-numeric: tabular-nums; }
.stage-err  { font-size: 0.72rem; color: #f85149; white-space: nowrap; max-width: 120px; overflow: hidden; text-overflow: ellipsis; }
.stage-err-btn { background: none; border: none; padding: 0 0 0 6px; cursor: pointer; color: #f85149; font-size: 0.88rem; line-height: 1; vertical-align: middle; opacity: 0.85; }
.stage-err-btn:hover { opacity: 1; color: #ff7b72; }
.stage-log-btn { background: var(--surface-muted); border: 1px solid var(--border); color: var(--text-muted); padding: 2px 8px; border-radius: 4px; font-size: 0.68rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
.stage-log-btn:hover { background: var(--border); color: var(--text); }
.stage-log-btn.active { background: #1c2d3e; border-color: #388bfd66; color: #79c0ff; }
[data-theme="light"] .stage-log-btn.active { background: #e1f0f5; border-color: #2dadca88; color: #006876; }
.stage-log-inline { border-top: 1px solid var(--surface-muted); }
.stage-log-pre { margin: 0; background: var(--code-bg); color: var(--code-text); font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.72rem; line-height: 1.6; max-height: 320px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; padding: 10px 13px; border-radius: 0 0 5px 5px; }
.empty-note { color: var(--text-faint); font-size: 0.82rem; padding: 4px 0; }
.no-project { grid-column: 1 / -1; text-align: center; padding: 60px 20px; color: var(--text-faint); }
.no-project h2 { font-size: 1.1rem; margin-bottom: 8px; color: var(--text-muted); }
.no-project p { font-size: 0.85rem; }

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
.dashboard-layout { grid-column: 1 / -1; padding: 20px 0; }
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
.dash-toolbar { display: flex; align-items: center; justify-content: flex-end; margin-bottom: 12px; }
.dash-layout-toggle { display: flex; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.dash-lt-btn { background: var(--surface); color: var(--text-muted); border: none; padding: 5px 10px; font-size: 0.9rem; cursor: pointer; line-height: 1; }
.dash-lt-btn:hover { background: var(--surface-muted); color: var(--text); }
.dash-lt-btn.active { background: #1c2d3e; color: #79c0ff; }
[data-theme="light"] .dash-lt-btn.active { background: #e1f0f5; color: #006876; }
.dashboard-list { display: flex; flex-direction: column; gap: 4px; }
.dash-list-row { display: grid; grid-template-columns: 1fr 80px 160px 1fr 52px; align-items: center; column-gap: 10px; padding: 9px 14px; background: var(--surface); border: 1px solid var(--border); border-radius: 6px; cursor: pointer; overflow: hidden; position: relative; transition: border-color 0.12s; }
.dash-list-row:hover { border-color: #388bfd; }
.dash-lr-status-bar { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; }
.dash-status-icon { font-style: normal; font-size: 1em; line-height: 1; flex-shrink: 0; }
.dash-status-icon.running { animation: spin 1.1s linear infinite; display: inline-block; }
.dash-lr-name { display: flex; align-items: center; gap: 6px; overflow: hidden; min-width: 0; }
.dash-lr-name-text { flex: 1; font-size: 0.9rem; font-weight: 600; color: var(--text-strong); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.dash-lr-progress { height: 4px; background: var(--border); border-radius: 2px; overflow: hidden; }
.dash-lr-stage-label { font-size: 0.78rem; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dash-lr-meta { font-size: 0.7rem; color: var(--text-faint); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dash-lr-actions { display: flex; justify-content: flex-end; align-items: center; gap: 4px; }
@media (max-width: 700px) { .dash-list-row { grid-template-columns: 1fr 52px; } .dash-lr-progress, .dash-lr-stage-label, .dash-lr-meta { display: none; } }
.dash-card { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; cursor: pointer; overflow: hidden; display: flex; flex-direction: column; transition: border-color 0.12s, box-shadow 0.12s; }
.dash-card:hover { border-color: #388bfd; box-shadow: 0 0 0 1px #388bfd22; }
@keyframes dash-card-glow { 0% { box-shadow: 0 0 0px #238636; } 30% { box-shadow: 0 0 14px 3px #3fb950; } 100% { box-shadow: 0 0 0px #238636; } }
.dash-card-flash { animation: dash-card-glow 0.9s ease-out forwards; }
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
.dash-card-actions { display: flex; gap: 4px; flex-shrink: 0; }
.dash-card-action-btn { border-radius: 4px; cursor: pointer; font-size: 0.78rem; font-weight: 600; width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; padding: 0; line-height: 1; flex-shrink: 0; border: none; }
.dash-card-action-btn.arch { background: var(--surface-muted); color: var(--text-muted); }
.dash-card-action-btn.arch:hover { background: var(--border); color: var(--text); }
.dash-card-action-btn.del { background: #da3633; color: #fff; }
.dash-card-action-btn.del:hover { background: #f85149; }
.dash-sim-badge { font-size: 0.6rem; background: #1c2d3e; color: #79c0ff; padding: 2px 6px; border-radius: 3px; white-space: nowrap; flex-shrink: 0; font-weight: 600; letter-spacing: 0.04em; }
.dash-elapsed { font-family: 'Consolas','Cascadia Code',monospace; font-size: inherit; font-weight: 700; font-variant-numeric: tabular-nums; color: var(--text-muted); }
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
.view-err { font-size: 0.75rem; color: var(--danger, #f85149); margin-left: 8px; opacity: 1; transition: opacity 0.3s; }

/* Inner tabs */
.inner-tab-bar { display:flex;gap:0;border-bottom:2px solid var(--border);margin:0 0 10px; }
.inner-tab-btn { background:none;border:none;border-bottom:2px solid transparent;margin-bottom:-2px;padding:6px 14px;font-size:0.82rem;font-weight:500;color:var(--text-muted);cursor:pointer;transition:color 0.15s,border-color 0.15s; }
.inner-tab-btn:hover { color:var(--text); }
.inner-tab-btn.active { color:var(--accent);border-bottom-color:var(--accent); }
/* Details tab sections */
.details-section { margin-bottom:12px; }
.details-section-label { font-size:0.75rem;font-weight:600;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.07em;margin-bottom:6px; }
.details-workspace-value { font-family:'Consolas','Cascadia Code','Courier New',monospace;font-size:0.82rem;color:var(--text);word-break:break-all;background:var(--surface-muted);border:1px solid var(--border);border-radius:4px;padding:6px 10px;display:block; }
.details-meta-table { width:100%;border-collapse:collapse;font-size:0.81rem; }
.details-meta-table td { padding:4px 0;vertical-align:top; }
.details-meta-label { width:110px;color:var(--text-muted);font-weight:500;white-space:nowrap;padding-right:12px; }
.details-meta-value { color:var(--text); }

/* Git tab */
.git-tab-header { display:flex;align-items:center;justify-content:space-between;gap:8px;padding:7px 12px;margin:0 0 12px;background:var(--surface2);border:1px solid var(--border);border-radius:6px;font-size:0.8rem;color:var(--text-muted); }
.git-tab-summary { flex:1; }
.git-refresh-btn { background:none;border:none;color:var(--text-muted);cursor:pointer;font-size:0.82rem;padding:2px 6px;line-height:1;border:1px solid var(--border);border-radius:4px; }
.git-refresh-btn:hover { color:var(--accent);border-color:var(--accent); }
.git-commit-list { display:flex;flex-direction:column;gap:6px; }
.git-commit-row { border:1px solid var(--border);border-radius:6px;overflow:hidden; }
.git-commit-row.open { border-color:var(--accent); }
.git-commit-header { display:flex;align-items:baseline;gap:8px;width:100%;padding:7px 10px;background:none;border:none;cursor:pointer;text-align:left;font-size:0.82rem;color:var(--text);transition:background 0.1s; }
.git-commit-header:hover { background:var(--surface2); }
.git-commit-chevron { font-size:0.65rem;color:var(--text-muted);flex-shrink:0;transition:transform 0.15s;margin-top:1px; }
.git-commit-row.open .git-commit-chevron { transform:rotate(90deg); }
.git-commit-hash { font-family:'Consolas','Cascadia Code','Courier New',monospace;font-size:0.78rem;color:var(--text-muted);flex-shrink:0; }
.git-commit-subject { flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }
.git-commit-date { font-size:0.75rem;color:var(--text-muted);white-space:nowrap;flex-shrink:0; }
.git-commit-body { border-top:1px solid var(--border);padding:8px 10px;background:var(--surface2); }
.git-commit-body-pre { margin:0;font-family:'Consolas','Cascadia Code','Courier New',monospace;font-size:0.78rem;color:var(--text);white-space:pre-wrap;word-break:break-word;line-height:1.5; }
.git-commit-no-body { font-size:0.78rem;color:var(--text-muted);font-style:italic; }
.git-empty { font-size:0.82rem;color:var(--text-muted);font-style:italic;padding:12px 0; }

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
.dot-upload-link { color: var(--accent); text-decoration: underline; cursor: pointer; font-size: 0.82rem; font-weight: 400; white-space: nowrap; }
.dot-upload-link:hover { opacity: 0.8; }
.dot-download-btn { background: var(--surface-muted); border: 1px solid var(--border); border-radius: 4px; color: var(--text-muted); width: 24px; height: 24px; padding: 0; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; font-size: 0.9rem; }
.dot-download-btn:hover { border-color: var(--accent); color: var(--accent); }
.graph-toolbar-row { display: flex; align-items: center; gap: 4px; margin-bottom: 8px; }

/* DOT preview tabs */
.preview-tabs { display: flex; gap: 2px; }
.preview-tab { background: transparent; border: none; color: var(--text-muted); padding: 4px 11px; border-radius: 5px; font-size: 0.75rem; font-weight: 600; cursor: pointer; }
.preview-tab:hover { background: var(--surface-muted); color: var(--text); }
.preview-tab.active { background: var(--surface-muted); color: var(--text-strong); }
.graph-preview { flex: 1; min-height: 200px; background: var(--graph-bg); border: 1px solid var(--border); border-radius: 8px; overflow: auto; display: flex; align-items: flex-start; position: relative; cursor: grab; }
.graph-content { padding: 20px; width: 100%; display: flex; align-items: center; justify-content: center; min-height: 200px; box-sizing: border-box; }
.graph-content svg { max-width: 100%; height: auto; display: block; }
.graph-placeholder { color: #aaa; font-size: 0.82rem; text-align: center; }
.graph-loading { color: #888; font-size: 0.82rem; text-align: center; }
.graph-error { color: #c0392b; font-size: 0.78rem; font-family: monospace; white-space: pre-wrap; word-break: break-word; text-align: center; max-width: 480px; }


/* Project graph panel (monitor view) */
.project-graph-view { overflow: auto; flex: 1; min-height: 0; background: var(--graph-bg); border-radius: 4px; cursor: grab; }
#rightPanel { display: flex; flex-direction: column; }
.project-graph-view > div { width: 100%; }
.project-graph-view svg { display: block; width: 100%; height: auto; }
.project-graph-placeholder { color: var(--text-faint); font-size: 0.78rem; padding: 20px; text-align: center; width: 100%; }
.project-graph-error { color: #f85149; font-size: 0.75rem; padding: 10px; font-family: monospace; white-space: pre-wrap; }

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

.system-tools-grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(110px, 1fr)); gap:8px; margin-top:12px; }
.tool-badge { display:flex; flex-direction:column; gap:3px; padding:8px 10px; border-radius:7px; background:var(--surface-muted); border:1px solid var(--border); }
.tool-badge-name { font-size:0.8rem; font-weight:600; color:var(--text-strong); font-family:monospace; }
.tool-badge-status { font-size:0.72rem; }
.tool-badge-status.found   { color:#3c9e5f; }
.tool-badge-status.missing { color:#c0392b; }
.tool-badge-hint { display:inline-flex; align-items:center; justify-content:center; width:13px; height:13px; border-radius:50%; background:var(--border); color:var(--text-strong); font-size:0.62rem; font-weight:700; cursor:default; vertical-align:middle; margin-left:3px; position:relative; }
.tool-badge-hint::after { content:attr(data-tip); position:absolute; bottom:calc(100% + 5px); left:50%; transform:translateX(-50%); background:#333; color:#fff; font-size:0.7rem; font-weight:400; font-family:sans-serif; padding:5px 9px; border-radius:5px; pointer-events:none; opacity:0; transition:opacity 0.15s; z-index:100; width:200px; white-space:normal; line-height:1.4; text-align:center; }
.tool-badge-hint:hover::after { opacity:1; }

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
[data-theme="light"] .stage-log-btn.active { background: #ede9fe; border-color: #4f46e566; color: #4338ca; }[data-theme="light"] .project-graph-error { color: #dc2626; }
[data-theme="light"] .dash-elapsed { color: #7c3aed; }
[data-theme="light"] .dot-textarea { background: #ffffff; color: #18181b; }
[data-theme="light"] .dot-textarea::placeholder { color: #71717a; }
[data-theme="light"] .nl-textarea { background: #ffffff; color: #18181b; }
[data-theme="light"] .nl-textarea::placeholder { color: #71717a; }
</style>
</head>
<body>

<div id="topChrome">
<header>
  <h1><a href="#" onclick="showView('monitor');selectTab(DASHBOARD_TAB_ID);return false;" style="color:inherit;text-decoration:none;">&#9889; Attractor</a></h1>
  <nav style="display:flex;gap:3px;">
    <button class="nav-btn active" id="navMonitor" onclick="showView('monitor')">&#128421;&#65039; Monitor</button>
    <button class="nav-btn" id="navCreate" onclick="showView('create')">🚀 Create</button>
    <button class="nav-btn" onclick="openImportModal()">&#128229; Import</button>
    <button class="nav-btn" id="navArchived" onclick="showView('archived')">&#128193; Archived</button>
    <button class="nav-btn" id="navSettings" onclick="showView('settings')">&#9881;&#65039; Settings</button>
    <a class="nav-btn" href="https://attractor.coreydaley.dev" target="_blank" rel="noopener noreferrer" style="text-decoration:none;">&#128218; Docs</a>
    <a class="nav-btn" href="https://github.com/coreydaley/attractor/releases/latest" target="_blank" rel="noopener noreferrer" style="text-decoration:none;">&#11015;&#65039; Download CLI</a>
  </nav>
</header>

<div id="agentWarningBanner" style="display:none;">
  <span id="agentWarningIcon" style="font-size:1.1rem; flex-shrink:0;">⚠️</span>
  <span id="agentWarningMsg"></span>
  <button onclick="showView('settings')" style="margin-left:auto; padding:3px 12px; border-radius:5px; border:1px solid currentColor; background:transparent; color:inherit; font-size:0.8rem; cursor:pointer; white-space:nowrap; opacity:0.85;">Open Settings</button>
</div>
<div id="requiredToolsWarningBanner" style="display:none;">
  <span style="font-size:1.1rem; flex-shrink:0;">⚠️</span>
  <span id="requiredToolsWarningMsg"></span>
  <button onclick="showView('settings')" style="margin-left:auto; padding:3px 12px; border-radius:5px; border:1px solid currentColor; background:transparent; color:inherit; font-size:0.8rem; cursor:pointer; white-space:nowrap; opacity:0.85;">Open Settings</button>
</div>
</div>

<div id="viewMonitor">
<div class="tab-bar" id="tabBar">
  <div class="tab-empty" id="tabEmpty">No projects yet &mdash; use Create to start a project</div>
</div>

<main id="mainContent">
  <div class="no-project" id="noProject">
    <h2>No project selected</h2>
    <p>Use <strong>Create</strong> to generate and run a project.</p>
  </div>
</main>
</div>

<!-- Create view -->
<div id="viewCreate" style="display:none; position:relative;">
  <div id="noAgentOverlay" style="display:none; position:absolute; inset:0; z-index:50; background:rgba(0,0,0,0.45); backdrop-filter:blur(2px); align-items:center; justify-content:center;">
    <div style="background:var(--surface); border:1px solid var(--border); border-radius:12px; padding:36px 40px; max-width:440px; width:90%; text-align:center; box-shadow:0 16px 48px rgba(0,0,0,0.3);">
      <div style="font-size:2.4rem; margin-bottom:12px;">⚡</div>
      <h2 style="margin:0 0 10px; font-size:1.15rem; color:var(--text-strong);">No Agent Configured</h2>
      <p style="margin:0 0 24px; font-size:0.88rem; color:var(--text-muted); line-height:1.6;">At least one AI provider must be configured and enabled before you can create a project. Go to Settings to set up a provider.</p>
      <button onclick="showView('settings')" style="padding:9px 24px; border-radius:7px; border:none; background:var(--accent,#4f46e5); color:#fff; font-size:0.9rem; font-weight:600; cursor:pointer;">Open Settings</button>
    </div>
  </div>
  <div class="create-layout">
    <div class="create-col">
      <div class="create-section">
        <input type="file" id="dotFileInput" accept=".dot" style="display:none;" onchange="onDotFileSelected()">
        <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:0;">
          <h2>Describe your project</h2>
          <span style="font-size:0.82rem;color:var(--text-muted);">or <a class="dot-upload-link" onclick="document.getElementById('dotFileInput').click();return false;" href="#">upload an existing .dot file</a></span>
        </div>
        <textarea id="nlInput" class="nl-textarea"
          placeholder="e.g. &quot;Write comprehensive unit tests for a Python web app, run them, fix any failures, then generate a coverage report&quot;&#10;&#10;Describe what you want in plain English, then click Generate."></textarea>
        <div style="display:flex; justify-content:flex-end; margin-top:6px;">
          <button id="generateBtn" onclick="triggerGenerate()" disabled style="padding:5px 16px; border-radius:6px; border:1px solid var(--border); background:var(--surface-muted); color:var(--text); font-size:0.82rem; font-weight:500; cursor:pointer;">Generate</button>
        </div>
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
          placeholder="Generated project DOT source will appear here&hellip;"></textarea>
        <div class="run-row">
          <span class="gen-hint" id="genHint">You can edit the DOT source before running.</span>
          <div style="display:flex;gap:8px;align-items:center;">
            <button class="btn-cancel-iterate" id="cancelIterateBtn" style="display:none;" onclick="cancelIterate()">&#x2715;&ensp;Cancel</button>
            <button class="run-btn" id="runBtn" disabled onclick="runGenerated()">&#9654;&ensp;Create</button>
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
        <button id="createDownloadBtn" class="dot-download-btn" onclick="downloadCreateDot()" title="Download .dot file" style="display:none;position:absolute;top:8px;right:8px;z-index:1;">&#8675;</button>
        <div id="graphContent" class="graph-content">
          <div class="graph-placeholder">Generate a project first to see the graph.</div>
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

    <!-- Dark Theme -->
    <div class="setting-row">
      <div class="setting-info">
        <div class="setting-label">Dark Theme</div>
        <div class="setting-desc">Switch between dark and light appearance</div>
      </div>
      <label class="toggle-switch">
        <input type="checkbox" id="settingDarkTheme" onchange="toggleTheme()">
        <span class="toggle-slider"></span>
      </label>
    </div>

    <!-- Execution Mode -->
    <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:10px;">
      <div class="setting-info">
        <div class="setting-label">Execution Mode</div>
        <div class="setting-desc">How AI providers are invoked for generation and project stages</div>
      </div>
      <div style="display:flex; gap:8px;">
        <button id="modeApiBtn" onclick="setExecutionMode('api')" style="padding:6px 18px; border-radius:6px; border:1px solid var(--border); cursor:pointer; font-size:0.9rem; background:var(--surface-muted); color:var(--text);">Direct API</button>
        <button id="modeCliBtn" onclick="setExecutionMode('cli')" style="padding:6px 18px; border-radius:6px; border:1px solid var(--border); cursor:pointer; font-size:0.9rem; background:var(--surface-muted); color:var(--text);">CLI subprocess</button>
      </div>
      <div id="apiYoloWarning" style="display:none; padding:12px 16px; border-radius:8px; border:1px solid var(--danger); background:rgba(248,81,73,0.12); color:var(--text-strong); font-size:0.85rem; line-height:1.6;">
        <div style="display:flex; align-items:flex-start; gap:10px;">
          <span style="font-size:1.3rem; line-height:1.2; color:var(--danger);">&#9888;&#65039;</span>
          <div>
            <div style="font-weight:700; color:var(--danger); margin-bottom:2px;">Direct API mode &mdash; your API keys will be used</div>
            <div style="color:var(--text); font-size:0.8rem;">Requests are sent directly to the provider APIs using your keys. You are responsible for any usage costs incurred.</div>
          </div>
        </div>
      </div>
      <div id="cliYoloWarning" style="display:none; padding:12px 16px; border-radius:8px; border:1px solid var(--danger); background:rgba(248,81,73,0.12); color:var(--text-strong); font-size:0.85rem; line-height:1.6;">
        <div style="display:flex; align-items:flex-start; gap:10px;">
          <span style="font-size:1.3rem; line-height:1.2; color:var(--danger);">&#9888;&#65039;</span>
          <div>
            <div style="font-weight:700; color:var(--danger); margin-bottom:2px;">YOLO mode active &mdash; use at your own risk</div>
            <div style="color:var(--text); font-size:0.8rem;">CLI commands run without confirmation prompts and with full tool access. They can read, write, and execute anything on your system.</div>
          </div>
        </div>
      </div>
      <div id="cliDockerWarning" style="display:none; padding:12px 16px; border-radius:8px; border:1px solid #d97706; background:rgba(217,119,6,0.1); color:var(--text-strong); font-size:0.85rem; line-height:1.6;">
        <div style="display:flex; align-items:flex-start; gap:10px;">
          <span style="font-size:1.3rem; line-height:1.2; color:#d97706;">&#9432;&#65039;</span>
          <div>
            <div style="font-weight:700; color:#d97706; margin-bottom:4px;">CLI subprocess mode is not supported in Docker</div>
            <div style="color:var(--text); font-size:0.8rem; line-height:1.6;">The AI CLI tools (<code>claude</code>, <code>codex</code>, <code>gemini</code>, <code>copilot</code>) are not installed in the container and their authentication state is not available. Use <strong>Direct API</strong> mode instead.</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Providers -->
    <div style="padding: 12px 0 4px 0;">
      <div class="setting-label" style="margin-bottom:8px;">Providers</div>
      <div class="setting-desc" style="margin-bottom:12px;">Enable or disable individual AI providers.<span id="cliPromptHint" style="display:none;"> CLI command templates support <code>{prompt}</code> substitution.</span></div>
      <div id="apiKeyRestartNote" style="display:none; padding:8px 12px; border-radius:7px; background:var(--surface-muted); border:1px solid var(--border); color:var(--text-muted); font-size:0.8rem; margin-bottom:12px;">&#8505;&#65039; API keys are read from environment variables at startup. Restart the application after setting a key for it to be detected.</div>

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
          <span id="apiBadgeAnthropic" style="font-size:0.78rem; display:none;"></span>
          <span id="cliBadgeAnthropic" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdAnthropic" type="text" placeholder="claude --dangerously-skip-permissions -p {prompt}"
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
          <span id="apiBadgeOpenAI" style="font-size:0.78rem; display:none;"></span>
          <span id="cliBadgeOpenAI" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdOpenAI" type="text" placeholder="codex exec --full-auto {prompt}"
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
          <span id="apiBadgeGemini" style="font-size:0.78rem; display:none;"></span>
          <span id="cliBadgeGemini" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdGemini" type="text" placeholder="gemini --yolo -p {prompt}"
          style="display:none; width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
          onblur="saveSetting('cli_gemini_command', this.value)">
      </div>

      <!-- Custom OpenAI-compatible endpoint — API-mode only, hidden in CLI mode -->
      <div id="customProviderRow" class="setting-row" style="flex-direction:column; align-items:flex-start; gap:6px; padding:10px 0;">
        <div style="display:flex; align-items:center; justify-content:space-between; width:100%;">
          <div style="display:flex; align-items:center; gap:10px;">
            <label class="toggle-switch" style="margin:0;">
              <input type="checkbox" id="settingCustomEnabled" onchange="saveSetting('provider_custom_enabled', this.checked); applyCustomApiFieldsVisibility()">
              <span class="toggle-slider"></span>
            </label>
            <span class="setting-label">Custom (OpenAI-compatible)</span>
          </div>
          <span id="apiBadgeCustom" style="font-size:0.78rem; display:none;"></span>
        </div>
        <div id="customApiFields" style="display:none; width:100%; flex-direction:column; gap:8px; padding-top:4px;">
          <div style="display:grid; grid-template-columns:1fr 120px; gap:8px;">
            <div>
              <label style="font-size:0.78rem; color:var(--text-muted); display:block; margin-bottom:3px;">Host URL</label>
              <input id="customApiHost" type="text" placeholder="http://localhost"
                style="width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
                onblur="saveSetting('custom_api_host', this.value)">
            </div>
            <div>
              <label style="font-size:0.78rem; color:var(--text-muted); display:block; margin-bottom:3px;">Port</label>
              <input id="customApiPort" type="text" placeholder="11434"
                style="width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
                onblur="saveSetting('custom_api_port', this.value)">
            </div>
          </div>
          <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;">
            <div>
              <label style="font-size:0.78rem; color:var(--text-muted); display:block; margin-bottom:3px;">API Key <span style="font-weight:400;">(optional)</span></label>
              <input id="customApiKey" type="text" placeholder="ollama"
                style="width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
                onblur="saveSetting('custom_api_key', this.value)">
            </div>
            <div>
              <label style="font-size:0.78rem; color:var(--text-muted); display:block; margin-bottom:3px;">Model</label>
              <input id="customApiModel" type="text" placeholder="llama3.2"
                style="width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
                onblur="saveSetting('custom_api_model', this.value)">
            </div>
          </div>
          <div style="font-size:0.78rem; color:var(--text-muted);">Compatible with Ollama, LM Studio, vLLM, and any OpenAI <code>/v1/chat/completions</code> endpoint.</div>
        </div>
      </div>

      <!-- Copilot — CLI-only provider, hidden in Direct API mode -->
      <div id="copilotProviderRow" class="setting-row" style="flex-direction:column; align-items:flex-start; gap:6px; padding:10px 0;">
        <div style="display:flex; align-items:center; justify-content:space-between; width:100%;">
          <div style="display:flex; align-items:center; gap:10px;">
            <label class="toggle-switch" style="margin:0;">
              <input type="checkbox" id="settingCopilotEnabled" onchange="saveSetting('provider_copilot_enabled', this.checked)">
              <span class="toggle-slider"></span>
            </label>
            <span class="setting-label">GitHub Copilot (gh)</span>
          </div>
          <span id="cliBadgeCopilot" style="font-size:0.78rem; display:none;"></span>
        </div>
        <input id="cliCmdCopilot" type="text" placeholder="copilot --allow-all-tools -p {prompt}"
          style="display:none; width:100%; box-sizing:border-box; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem; font-family:monospace;"
          onblur="saveSetting('cli_copilot_command', this.value)">
      </div>
    </div>
  </div>

  <!-- System Tools -->
  <div style="margin-top:28px; padding-top:24px; border-top:1px solid var(--border);">
    <div style="display:flex; align-items:center; justify-content:space-between; margin-bottom:4px;">
      <h2 style="margin:0; font-size:1.05rem; font-weight:600; color:var(--text-strong);">System Tools</h2>
      <button onclick="loadSystemToolsStatus()" style="padding:3px 12px; border-radius:6px; border:1px solid var(--border); background:var(--surface-muted); color:var(--text); font-size:0.8rem; cursor:pointer;">Re-check</button>
    </div>
    <div class="setting-desc" style="margin-bottom:16px;">Tools detected on the system. Required tools must be present for core features to work.</div>
    <div class="setting-label" style="font-size:0.78rem; text-transform:uppercase; letter-spacing:0.05em; color:var(--text-muted); margin-bottom:6px;">Required</div>
    <div id="systemToolsRequired" class="system-tools-grid" style="margin-bottom:16px;"></div>
    <div class="setting-label" style="font-size:0.78rem; text-transform:uppercase; letter-spacing:0.05em; color:var(--text-muted); margin-bottom:6px;">Optional &mdash; depending on projects you create</div>
    <div id="systemToolsOptional" class="system-tools-grid"></div>
  </div>

  <!-- Info -->
  <div style="margin-top:28px; padding-top:24px; border-top:1px solid var(--border);">
    <h2 style="margin:0 0 16px; font-size:1.05rem; font-weight:600; color:var(--text-strong);">Info</h2>
    <div class="setting-row">
      <div class="setting-info">
        <div class="setting-label">Application Version</div>
        <div class="setting-desc">The running version of Attractor</div>
      </div>
      <span style="font-family:monospace; font-size:0.85rem; color:var(--text-muted);">$appVersion</span>
    </div>
${if (baseVersion != "dev") """    <div class="setting-row">
      <div class="setting-info">
        <div class="setting-label">Base Image Version</div>
        <div class="setting-desc">The <code>attractor-base</code> image this container was built from</div>
      </div>
      <span style="font-family:monospace; font-size:0.85rem; color:var(--text-muted);">$baseVersion</span>
    </div>""" else ""}
${if (imageVersion != "dev") """    <div class="setting-row">
      <div class="setting-info">
        <div class="setting-label">Server Image Version</div>
        <div class="setting-desc">The <code>attractor</code> server image version</div>
      </div>
      <span style="font-family:monospace; font-size:0.85rem; color:var(--text-muted);">$imageVersion</span>
    </div>""" else ""}
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
    <h2>Delete Project Run?</h2>
    <p style="color:#c9d1d9;margin:12px 0 6px;line-height:1.5;">Project: <strong id="deleteModalName" style="color:#f0f6fc;"></strong></p>
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
    <h2>&#128229;&ensp;Import Project</h2>
    <p style="color:#8b949e;font-size:0.82rem;margin-bottom:16px;line-height:1.5;">Select an exported project ZIP to restore it (metadata and artifacts) without re-running.</p>
    <div class="field">
      <label style="display:block;font-size:0.8rem;color:#8b949e;margin-bottom:6px;">Project ZIP file</label>
      <input type="file" id="importZipInput" accept=".zip" style="color:#c9d1d9;font-size:0.82rem;width:100%;" onchange="onImportFileChange()">
    </div>
    <div id="importMsg" style="margin-top:10px;font-size:0.8rem;min-height:1.2em;"></div>
    <div class="modal-actions" style="margin-top:16px;">
      <button class="btn-cancel" onclick="closeImportModal()">Cancel</button>
      <button class="btn-primary" id="importSubmitBtn" onclick="submitImport()" disabled>Import</button>
    </div>
  </div>
</div>

<script>
var IS_DOCKER = ${imageVersion != "dev"};
var DASHBOARD_TAB_ID = '__dashboard__';
var projects = {};     // id -> {id, fileName, state}
var _storedTab = localStorage.getItem('attractor-selected-tab');
var selectedId = _storedTab || DASHBOARD_TAB_ID;
var _closedTabsRaw; try { _closedTabsRaw = localStorage.getItem('attractor-closed-tabs'); } catch(e){}
var closedTabs = {};
if (_closedTabsRaw) { try { var _cta = JSON.parse(_closedTabsRaw); if (Array.isArray(_cta)) _cta.forEach(function(id){ closedTabs[id] = true; }); } catch(e){} }
if (_storedTab && closedTabs[_storedTab]) { selectedId = DASHBOARD_TAB_ID; try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){} }
var _storedLayout; try { _storedLayout = localStorage.getItem('attractor-dashboard-layout'); } catch(e){}
var dashLayout = (_storedLayout === 'list') ? 'list' : 'card';
var panelBuiltFor = null;  // which id the main panel DOM was built for
var innerTab = 'runs';     // 'runs' | 'details' — active project inner tab
var elapsedTimer = null;   // interval that ticks the elapsed counter every second
var dashboardTimer = null; // interval that ticks elapsed counters on the dashboard
var stageErrors = {};      // stageIndex -> full error string for the selected project
var stageLogTimer = null;   // interval for polling stage live log
var stageLogNodeId = null;  // nodeId of the currently-expanded inline stage log
var stageLogContent = '';   // last fetched log text for the expanded stage
var graphSigFor = {};       // id -> last stage-status signature used to render the graph
var graphRenderGen = {};    // id -> render generation; stale in-flight responses are discarded
var prevStatuses = {};      // id -> last observed project status (for completion flash detection)

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
function saveDashLayout() {
  try { localStorage.setItem('attractor-dashboard-layout', dashLayout); } catch(e){}
}

function setDashLayout(mode) {
  if (mode !== 'card' && mode !== 'list') return;
  if (dashLayout === mode) return;
  dashLayout = mode;
  saveDashLayout();
  if (selectedId === DASHBOARD_TAB_ID) renderDashboard();
}

function startDashboardTimer() {
  dashboardTimer = setInterval(tickDashboardElapsed, 1000);
}

function stopDashboardTimer() {
  clearInterval(dashboardTimer); dashboardTimer = null;
}

function flashDashCard(id) {
  var el = document.getElementById('dash-card-' + id);
  if (!el) return;
  el.classList.add('dash-card-flash');
  setTimeout(function() { el.classList.remove('dash-card-flash'); }, 900);
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
  var spans = document.querySelectorAll('.dash-elapsed[data-project-id]');
  for (var i = 0; i < spans.length; i++) {
    var id = spans[i].getAttribute('data-project-id');
    var p = projects[id];
    if (!p || !p.state) continue;
    var st = p.state;
    if (st.status !== 'running' && st.status !== 'paused') continue;
    spans[i].textContent = elapsed(st);
  }
}

function dashProjectData(id) {
  var p = projects[id];
  var st = p.state || {};
  var status = st.status || 'idle';
  var sc = 's-' + status;
  var name = esc(st.project || p.fileName || 'project');
  // Stage progress
  var stages = st.stages || [];
  var totalStages = stages.length;
  var doneStages = 0;
  for (var j = 0; j < stages.length; j++) { if (stages[j].status === 'completed') doneStages++; }
  var pct = status === 'completed' ? 100
          : (totalStages > 0 ? Math.min(100, Math.round(doneStages / totalStages * 100)) : 0);
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
  // Started time
  var startedStr = '';
  if (st.startedAt) {
    var d = new Date(st.startedAt);
    var today = new Date();
    startedStr = d.toDateString() !== today.toDateString()
      ? d.toLocaleDateString([], {month:'short', day:'numeric'}) + ' ' + d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'})
      : d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
  }
  var effectiveDone = (status === 'completed') ? totalStages : doneStages;
  var completedPrefix = (status === 'completed' && totalStages > 0) ? '\u2713\u2002' : '';
  var stageCountStr = totalStages > 0 ? completedPrefix + effectiveDone + '\u2009/\u2009' + totalStages + ' stages' : '';
  var _dashIconMap = { idle:'\u25cb', running:'\u27f3', completed:'\u2713', failed:'\u2717', cancelled:'\u2715', paused:'\u23f8' };
  var _glyph = _dashIconMap[status] || _dashIconMap.idle;
  var _spinCls = status === 'running' ? ' running' : '';
  var statusIcon = '<span class="dash-status-icon tab-si-' + status + _spinCls + '">' + _glyph + '</span>';
  var simBadge = p.simulate ? '<span class="dash-sim-badge">SIM</span>' : '';
  var isTerminal = (status === 'completed' || status === 'failed' || status === 'cancelled');
  var cardActions = isTerminal
    ? '<div class="dash-card-actions">'
      + '<button class="dash-card-action-btn arch" onclick="dashCardArchive(\'' + id + '\',event)" title="Archive">&#8595;</button>'
      + '<button class="dash-card-action-btn del" onclick="dashCardDelete(\'' + id + '\',event)" title="Delete">&#10005;</button>'
      + '</div>'
    : '';
  return { status: status, sc: sc, name: name, pct: pct, stageLabel: stageLabel,
           elapsedStr: elapsedStr, startedStr: startedStr, stageCountStr: stageCountStr,
           simBadge: simBadge, isTerminal: isTerminal, cardActions: cardActions,
           statusIcon: statusIcon };
}

function buildDashCards(visibleIds) {
  var cards = '';
  for (var i = 0; i < visibleIds.length; i++) {
    var id = visibleIds[i];
    var d = dashProjectData(id);
    var elapsedSpanC = d.elapsedStr ? '<span class="dash-elapsed ' + d.sc + '" id="dash-elapsed-' + id + '" data-project-id="' + id + '">' + d.elapsedStr + '</span>' : '';
    var stageTimeStrC = d.stageCountStr ? d.stageCountStr + (elapsedSpanC ? ' in\u00a0' + elapsedSpanC : '') : elapsedSpanC;
    cards += '<div class="dash-card" id="dash-card-' + id + '" onclick="selectTab(\'' + id + '\')">'
      + '<div class="dash-card-top ' + d.sc + '"></div>'
      + '<div class="dash-card-body">'
      +   '<div class="dash-card-title-row">' + d.statusIcon + '<span class="dash-card-name">' + d.name + '</span>' + d.simBadge + d.cardActions + '</div>'
      +   '<div class="dash-progress-track"><div class="dash-progress-fill ' + d.sc + '" style="width:' + d.pct + '%"></div></div>'
      +   '<div class="dash-stage-label">' + d.stageLabel + '</div>'
      + '</div>'
      + '<div class="dash-card-footer">'
      +   '<span class="dash-stage-count">' + stageTimeStrC + '</span>'
      +   '<span class="dash-started">' + d.startedStr + '</span>'
      + '</div>'
      + '</div>';
  }
  return cards;
}

function buildDashList(visibleIds) {
  var html = '';
  for (var i = 0; i < visibleIds.length; i++) {
    var id = visibleIds[i];
    var d = dashProjectData(id);
    var elapsedSpanL = d.elapsedStr ? '<span class="dash-elapsed ' + d.sc + '" id="dash-elapsed-' + id + '" data-project-id="' + id + '">' + d.elapsedStr + '</span>' : '';
    var stageTimeStrL = d.stageCountStr ? d.stageCountStr + (elapsedSpanL ? ' in\u00a0' + elapsedSpanL : '') : elapsedSpanL;
    var metaStr = stageTimeStrL + (stageTimeStrL && d.startedStr ? ' \u00b7 ' : '') + d.startedStr;
    html += '<div class="dash-list-row" onclick="selectTab(' + JSON.stringify(id) + ')">'
      + '<div class="dash-lr-status-bar ' + d.sc + '"></div>'
      + '<div class="dash-lr-name">' + d.statusIcon + '<span class="dash-lr-name-text">' + d.name + '</span>' + d.simBadge + '</div>'
      + '<div class="dash-lr-progress"><div class="dash-progress-fill ' + d.sc + '" style="width:' + d.pct + '%"></div></div>'
      + '<span class="dash-lr-stage-label">' + d.stageLabel + '</span>'
      + '<span class="dash-lr-meta">' + metaStr + '</span>'
      + '<div class="dash-lr-actions">' + d.cardActions + '</div>'
      + '</div>';
  }
  return html;
}

function renderDashboard() {
  stopDashboardTimer();
  var ids = Object.keys(projects);
  var visibleIds = ids.filter(function(id) {
    return !(projects[id].state && projects[id].state.archived);
  });

  // Compute stats
  var totalCount = visibleIds.length;
  var runningCount = 0, completedCount = 0, failedCount = 0, cancelledCount = 0;
  for (var i = 0; i < visibleIds.length; i++) {
    var s = (projects[visibleIds[i]].state || {}).status || '';
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
    var sa = (projects[a].state || {}).status || 'idle';
    var sb = (projects[b].state || {}).status || 'idle';
    var oa = statusOrder[sa] !== undefined ? statusOrder[sa] : 5;
    var ob = statusOrder[sb] !== undefined ? statusOrder[sb] : 5;
    if (oa !== ob) return oa - ob;
    var ta = (projects[a].state || {}).startedAt || 0;
    var tb = (projects[b].state || {}).startedAt || 0;
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

  var toolbarHtml = '<div class="dash-toolbar">'
    + '<div class="dash-layout-toggle">'
    +   '<button class="dash-lt-btn' + (dashLayout === 'card' ? ' active' : '') + '" aria-pressed="' + (dashLayout === 'card' ? 'true' : 'false') + '" onclick="setDashLayout(\'card\')" title="Card view">\u229e</button>'
    +   '<button class="dash-lt-btn' + (dashLayout === 'list' ? ' active' : '') + '" aria-pressed="' + (dashLayout === 'list' ? 'true' : 'false') + '" onclick="setDashLayout(\'list\')" title="List view">\u2261</button>'
    + '</div></div>';

  if (totalCount === 0) {
    mainEl.innerHTML = '<div class="dashboard-layout">' + toolbarHtml + statsHtml
      + '<div class="dash-empty"><div class="dash-empty-icon">\u26a1</div>'
      + '<div style="font-size:1rem;font-weight:600;color:var(--text-muted);">No projects yet</div>'
      + '<p>Use <strong>Create</strong> to generate and run a project.</p></div></div>';
    return;
  }

  if (dashLayout === 'list') {
    mainEl.innerHTML = '<div class="dashboard-layout">' + toolbarHtml + statsHtml + '<div class="dashboard-list">' + buildDashList(visibleIds) + '</div></div>';
  } else {
    mainEl.innerHTML = '<div class="dashboard-layout">' + toolbarHtml + statsHtml + '<div class="dashboard-grid">' + buildDashCards(visibleIds) + '</div></div>';
  }
  startDashboardTimer();
}

// ── Tabs ────────────────────────────────────────────────────────────────────
function saveClosedTabs() {
  try { localStorage.setItem('attractor-closed-tabs', JSON.stringify(Object.keys(closedTabs))); } catch(e){}
}

function closeTab(id, event) {
  event.stopPropagation();
  closedTabs[id] = true;
  saveClosedTabs();
  delete graphSigFor[id]; delete graphRenderGen[id];
  if (selectedId === id) {
    selectedId = DASHBOARD_TAB_ID;
    try { localStorage.setItem('attractor-selected-tab', DASHBOARD_TAB_ID); } catch(e){}
    panelBuiltFor = null;
  }
  renderTabs();
  renderMain();
}

function renderTabs() {
  var bar = document.getElementById('tabBar');
  var ids = Object.keys(projects);
  // Show non-archived runs, plus the selected run even if archived; never show closed tabs
  var visibleIds = ids.filter(function(id) {
    return (!projects[id].state.archived || id === selectedId) && !closedTabs[id];
  });
  var dashActive = selectedId === DASHBOARD_TAB_ID ? ' active' : '';
  var html = '<div class="tab dash-tab' + dashActive + '" onclick="selectTab(DASHBOARD_TAB_ID)">&#128202; Dashboard</div>';
  var tabIconMap = {
    idle:      '\u25cb',
    running:   '\u27f3',
    completed: '\u2713',
    failed:    '\u2717',
    cancelled: '\u2715',
    paused:    '\u23f8'
  };
  for (var i = 0; i < visibleIds.length; i++) {
    var id = visibleIds[i];
    var p = projects[id];
    var st = p.state;
    var name = (st && st.project) ? st.project : p.fileName;
    var status = (st && st.status) ? st.status : 'idle';
    var active = id === selectedId ? ' active' : '';
    var archivedCls = (st && st.archived) ? ' archived-tab' : '';
    var glyph = tabIconMap[status] || tabIconMap.idle;
    var spinCls = status === 'running' ? ' running' : '';
    html += '<div class="tab' + active + archivedCls + '" onclick="selectTab(\'' + id + '\')">'
         +  '<span class="tab-status-icon tab-si-' + esc(status) + spinCls + '">' + glyph + '</span>'
         +  esc(name)
         +  ' <span class="tab-close" onclick="closeTab(\'' + id + '\', event)" title="Close tab">&times;</span>'
         +  '</div>';
  }
  bar.innerHTML = html;
}

function selectTab(id) {
  if (closedTabs[id]) { delete closedTabs[id]; saveClosedTabs(); }
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

// ── Inner tabs ───────────────────────────────────────────────────────────────

function _loadInnerTab() {
  try {
    var v = localStorage.getItem('attractor-project-inner-tab');
    if (v === 'details' || v === 'git') return v;
    return 'runs';
  } catch(e) { return 'runs'; }
}

function _applyInnerTabButtons() {
  var btnRuns    = document.getElementById('innerTabBtnRuns');
  var btnDetails = document.getElementById('innerTabBtnDetails');
  var btnGit     = document.getElementById('innerTabBtnGit');
  if (btnRuns)    btnRuns.classList.toggle('active',    innerTab === 'runs');
  if (btnDetails) btnDetails.classList.toggle('active', innerTab === 'details');
  if (btnGit)     btnGit.classList.toggle('active',     innerTab === 'git');
}

function selectInnerTab(tab) {
  if (tab === innerTab) return;
  innerTab = tab;
  try { localStorage.setItem('attractor-project-inner-tab', tab); } catch(e) {}
  if (selectedId && selectedId !== DASHBOARD_TAB_ID) {
    panelBuiltFor = null;
    renderMain();
  }
}

function renderDetailsTab(id) {
  var p = projects[id];
  if (!p) return;
  var d = p.state || {};
  var ws = document.getElementById('detailsWorkspace');
  if (ws) ws.textContent = p.logsRoot || '\u2014';
  var rows = [
    ['Display name', esc(p.displayName || d.project || p.fileName || '\u2014'), false],
    ['File',         esc(p.fileName || '\u2014'), false],
    ['Family ID',    esc(p.familyId || '\u2014'), true],
    ['Run ID',       esc(d.runId || '\u2014'), true],
    ['Simulate',     p.simulate ? 'Yes' : 'No', false],
    ['Auto-approve', p.autoApprove !== false ? 'Yes' : 'No', false],
    ['Started',      d.startedAt ? esc(new Date(d.startedAt).toLocaleString()) : '\u2014', false],
    ['Finished',     d.finishedAt ? esc(new Date(d.finishedAt).toLocaleString()) : '\u2014', false]
  ];
  var html = '';
  for (var i = 0; i < rows.length; i++) {
    var label = rows[i][0], value = rows[i][1], mono = rows[i][2];
    html += '<tr><td class="details-meta-label">' + esc(label) + '</td>'
      + '<td class="details-meta-value' + (mono ? '" style="font-family:monospace;font-size:0.8rem;"' : '"') + '>'
      + value + '</td></tr>';
  }
  var tbody = document.querySelector('#detailsMetaTable tbody');
  if (tbody) tbody.innerHTML = html;
}

// ── Main panel ──────────────────────────────────────────────────────────────

// Build the static DOM scaffold for a project tab — called once per tab selection.
function buildPanel(id) {
  stopDashboardTimer();
  clearInterval(elapsedTimer); elapsedTimer = null;
  clearInterval(stageLogTimer); stageLogTimer = null;
  stageLogNodeId = null; stageLogContent = '';
  panelBuiltFor = id;
  graphSigFor[id] = null;   // force graph re-render after DOM rebuild
  gitPanelExpanded = false;
  window._gitData = null;
  // Reset version history state on tab switch
  vhExpanded = false;
  vhData = null;
  vhMembersById = {};
  // Read persisted inner tab preference
  innerTab = _loadInnerTab();

  // Shared header (always present above the inner tab bar)
  var sharedHeader =
    '<div id="panelLeft">'
    + '<div class="panel-header">'
    +   '<div class="project-title"><em class="status-icon" id="pStatusIcon">\u25cb</em><span id="pTitle"></span></div>'
    + '</div>'
    + '<div class="project-meta" id="pMeta"></div>'
    + '<div class="inner-tab-bar">'
    +   '<button class="inner-tab-btn" id="innerTabBtnRuns" onclick="selectInnerTab(\'runs\')">Runs</button>'
    +   '<button class="inner-tab-btn" id="innerTabBtnDetails" onclick="selectInnerTab(\'details\')">Details</button>'
    +   '<button class="inner-tab-btn" id="innerTabBtnGit" onclick="selectInnerTab(\'git\')">Git</button>'
    + '</div>';

  var mainContent = '';

  if (innerTab === 'details') {
    // Details scaffold — full width
    mainContent = sharedHeader
      + '<div class="details-section">'
      +   '<div class="details-section-label">Workspace</div>'
      +   '<span class="details-workspace-value" id="detailsWorkspace">\u2014</span>'
      + '</div>'
      + '<div class="details-section">'
      +   '<div class="details-section-label">Project</div>'
      +   '<table class="details-meta-table" id="detailsMetaTable"><tbody></tbody></table>'
      + '</div>'
      + '</div>';
  } else if (innerTab === 'git') {
    // Git scaffold — full width, expandable commit list
    mainContent = sharedHeader
      + '<div class="git-tab-header">'
      +   '<span class="git-tab-summary" id="gitBarSummary">Loading git info\u2026</span>'
      +   '<button class="git-refresh-btn" onclick="refreshGitInfo()" title="Refresh">\u21bb Refresh</button>'
      + '</div>'
      + '<div class="git-commit-list" id="gitCommitList">'
      +   '<div class="git-empty">Loading commits\u2026</div>'
      + '</div>'
      + '</div>';
  } else {
    // Runs scaffold — flex layout with right panel inside panelLeft
    mainContent = sharedHeader
      + '<div class="runs-layout">'
      + '<div class="runs-main">'
      + '<div class="action-bar" id="actionBar">'
      +   '<div class="action-bar-primary">'
      +     '<button class="btn-cancel-run" id="cancelBtn" style="display:none;" onclick="cancelProject()">&#9632;&ensp;Cancel</button>'
      +     '<button class="btn-pause-run"  id="pauseBtn"  style="display:none;" onclick="pauseProject()">&#9646;&#9646;&ensp;Pause</button>'
      +     '<button class="btn-resume-run" id="resumeBtn" style="display:none;" onclick="resumeProject()">&#9654;&ensp;Resume</button>'
      +     '<button class="btn-rerun" id="rerunBtn" style="display:none;" onclick="rerunProject()">&#8635;&ensp;Re-run</button>'
      +     '<button class="btn-rerun" id="iterateBtn" style="display:none;" onclick="iterateProject()">&#9998;&ensp;Iterate</button>'
      +   '</div>'
      +   '<div class="action-bar-secondary">'
      +     '<button class="btn-download" id="failureReportBtn" style="display:none;" onclick="openArtifacts(currentRunId(),\'Failure Report\')">&#128203;&ensp;View Failure Report</button>'
      +     '<button class="btn-download" id="exportBtn" style="display:none;" onclick="exportRun()">&#8599;&ensp;Export</button>'
      +     '<button class="btn-archive" id="archiveBtn" style="display:none;" onclick="archiveProject()">&#8595;&ensp;Archive</button>'
      +     '<button class="btn-unarchive" id="unarchiveBtn" style="display:none;" onclick="unarchiveProject()">&#8593;&ensp;Unarchive</button>'
      +     '<button class="btn-delete" id="deleteBtn" style="display:none;" onclick="showDeleteConfirm(currentRunId())">&#10005;&ensp;Delete</button>'
      +   '</div>'
      + '</div>'
      + '<div id="projectDesc" style="display:none;" class="project-desc-block"><div class="project-desc-label">Prompt</div><div id="projectDescText"></div></div>'
      + '<div class="card"><h2>Stages</h2><div class="stage-list" id="stageList"><div class="empty-note">No stages yet.</div></div></div>'
      + '<div class="version-history" id="versionHistory" style="display:none;">'
      +   '<button class="vh-header" onclick="toggleVersionHistory()">'
      +     '<span id="vhChevron">&#9654;</span>&ensp;<span id="vhLabel">Version History</span>'
      +   '</button>'
      +   '<span class="view-err" id="viewError" style="display:none;"></span>'
      +   '<div class="vh-list" id="vhList" style="display:none;"></div>'
      + '</div>'
      + '</div>'  // close runs-main
      + '<div class="card" id="rightPanel">'
      +   '<div id="graphToolbarRow" class="graph-toolbar-row">'
      +     '<button class="dot-download-btn" onclick="downloadMonitorDot()" title="Download .dot file">&#8675;</button>'
      +     '<div style="flex:1;"></div>'
      +     '<button class="graph-zoom-btn" title="Zoom out (or Ctrl+scroll)" onclick="zoomMonitor(-1)">&#x2212;</button>'
      +     '<span class="graph-zoom-label" id="monitorZoomLabel">100%</span>'
      +     '<button class="graph-zoom-btn" title="Zoom in (or Ctrl+scroll)" onclick="zoomMonitor(1)">+</button>'
      +     '<button class="graph-zoom-btn" title="Reset zoom" onclick="resetMonitorZoom()">&#x21BA;</button>'
      +   '</div>'
      +   '<div class="project-graph-view" id="graphView"><div id="graphViewInner"><div class="project-graph-placeholder">Waiting for project\u2026</div></div></div>'
      + '</div>'
      + '</div>'  // close runs-layout
      + '</div>';  // close panelLeft
  }

  document.getElementById('mainContent').innerHTML = mainContent;


  _applyInnerTabButtons();
  elapsedTimer = setInterval(tickElapsed, 1000);

  if (innerTab === 'git') {
    loadGitInfo(id);
  } else if (innerTab === 'details') {
    // Details tab has no async content to load
  } else {
    loadVersionHistory(id);
    monitorZoom = 1.0;
    var gvEl = document.getElementById('graphView');
    if (gvEl) {
      gvEl.addEventListener('wheel', function(e) {
        if (e.ctrlKey || e.metaKey) { e.preventDefault(); zoomMonitor(e.deltaY < 0 ? 1 : -1); }
      }, { passive: false });
      initDragPan(gvEl);
    }
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
}

// Tick the elapsed counter every second while a project is running.
function tickElapsed() {
  var p = selectedId && projects[selectedId];
  if (!p) return;
  var d = p.state || {};

  // Overall project elapsed
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
  var p = projects[id];
  if (!p) return;
  var d = p.state || {};

  var titleEl = document.getElementById('pTitle');
  if (titleEl) titleEl.textContent = d.project || p.fileName;

  var statusIcon = document.getElementById('pStatusIcon');
  if (statusIcon) {
    var st = d.status || 'idle';
    var iconMap = {
      idle:      { glyph: '\u25cb', color: 'var(--text-faint)' },
      running:   { glyph: '\u27f3', color: 'var(--badge-running-fg)' },
      completed: { glyph: '\u2713', color: 'var(--badge-completed-fg)' },
      failed:    { glyph: '\u2717', color: 'var(--badge-failed-fg)' },
      cancelled: { glyph: '\u2715', color: 'var(--text-muted)' },
      paused:    { glyph: '\u23f8', color: 'var(--accent)' }
    };
    var ic = iconMap[st] || iconMap.idle;
    statusIcon.textContent = ic.glyph;
    statusIcon.style.color = ic.color;
    statusIcon.className = 'status-icon' + (st === 'running' ? ' running' : '');
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

  // Project description (originalPrompt)
  var descEl = document.getElementById('projectDesc');
  if (descEl) {
    var desc = (p && p.originalPrompt) ? p.originalPrompt.trim() : '';
    if (desc) { var t = document.getElementById('projectDescText'); if (t) t.textContent = desc; descEl.style.display = ''; }
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
          + '<span class="stage-name' + (s.status === 'pending' ? ' not-run' : '') + '">' + esc(s.name) + '</span>';
        if (s.error) {
          html += '<button class="stage-err-btn" title="Click for full error details" data-pos="' + i + '">&#9888;</button>';
        }
        html += '<span class="stage-logs-slot">';
        if (hasNodeId && s.hasLog) {
          html += '<button class="stage-log-btn' + (isLogOpen ? ' active' : '') + '" data-node-id="' + esc(s.nodeId) + '" data-stage-name="' + esc(s.name) + '">' + (isLogOpen ? '\u25bc\u2002Logs' : 'Logs') + '</button>';
        } else {
          html += '<span class="stage-na">n/a</span>';
        }
        html += '</span>';
        var isLastStage = (i === d.stages.length - 1);
        if (isLastStage && d.startedAt) {
          html += '<span class="stage-total">';
          if ((s.status === 'running' || s.status === 'retrying' || s.status === 'diagnosing' || s.status === 'repairing') && d.startedAt) {
            var sec = Math.floor((Date.now() - d.startedAt) / 1000);
            var liveStr = sec < 60 ? sec + 's' : Math.floor(sec/60) + 'm ' + (sec%60) + 's';
            html += 'Total Runtime: <span class="stage-live-dur" data-started-at="' + d.startedAt + '">' + liveStr + '</span>';
          } else if (s.status === 'completed' || s.status === 'failed' || s.status === 'cancelled') {
            html += 'Total Runtime: ' + elapsed(d);
          }
          html += '</span>';
        } else {
          html += '<span class="stage-dur">';
          if ((s.status === 'running' || s.status === 'retrying' || s.status === 'diagnosing' || s.status === 'repairing') && s.startedAt) {
            var sec = Math.floor((Date.now() - s.startedAt) / 1000);
            var liveStr = sec < 60 ? sec + 's' : Math.floor(sec/60) + 'm ' + (sec%60) + 's';
            html += '<span class="stage-live-dur" data-started-at="' + s.startedAt + '">' + liveStr + '</span>';
          } else if (s.durationMs != null) {
            html += fmtDur(s.durationMs);
          } else {
            html += 'n/a';
          }
          html += '</span>';
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
      stageList.innerHTML = '<div class="empty-note">Project failed before any stages ran.</div>';
    }
  }

  // Render (or re-render) the project graph whenever stage statuses change
  renderProjectGraph(id);

  // Refresh version history panel (re-render from cached data, no network request)
  if (vhData !== null) renderVersionHistory(id, vhData);

  // Populate Details tab content when active
  if (innerTab === 'details') renderDetailsTab(id);
}

function renderMain() {
  var mainEl = document.getElementById('mainContent');
  if (selectedId === DASHBOARD_TAB_ID) { renderDashboard(); return; }
  if (!selectedId || !projects[selectedId]) {
    panelBuiltFor = null;
    mainEl.innerHTML = '<div class="no-project"><h2>No project selected</h2>'
      + '<p>Use <strong>Create</strong> to generate and run a project.</p></div>';
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

var gitPanelExpanded = false;

function loadVersionHistory(runId) {
  var fid = projects[runId] && projects[runId].familyId;
  if (!fid) {
    var el = document.getElementById('versionHistory');
    if (el) el.style.display = 'none';
    return;
  }
  fetch('/api/project-family?id=' + encodeURIComponent(runId))
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
    })
    .catch(function() {
      var el = document.getElementById('versionHistory');
      if (el) el.style.display = 'none';
    });
}

function renderVersionHistory(tabId, members) {
  var section = document.getElementById('versionHistory');
  var listEl  = document.getElementById('vhList');
  var label   = document.getElementById('vhLabel');
  if (!section || !listEl || !label) return;
  // Show if 2+ members, or if 1 member and this run is an iterated version (familyId ≠ tabId)
  var p = projects[tabId];
  var isIterateMember = p && p.familyId && p.familyId !== tabId;
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
  // Resolve which run is actually being displayed (may differ from tab key for iterations).
  var displayedRunId = (p && p.id) || tabId;
  var html = '';
  // Render newest-first
  for (var i = members.length - 1; i >= 0; i--) {
    var m = members[i];
    var vn = i + 1;
    var isCurrent = m.id === displayedRunId ? ' vh-current' : '';
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

// ── Git ──────────────────────────────────────────────────────────────────────

function loadGitInfo(id) {
  if (!id || id === DASHBOARD_TAB_ID) return;
  fetch('/api/v1/projects/' + encodeURIComponent(id) + '/git')
    .then(function(r) { return r.ok ? r.json() : null; })
    .then(function(data) {
      if (data && id === selectedId) {
        window._gitData = data;
        renderGitBar(data);
        renderGitCommitList(data.recent || []);
      }
    })
    .catch(function() { /* silent */ });
}

function refreshGitInfo() {
  var id = typeof currentRunId === 'function' ? currentRunId() : selectedId;
  if (id) loadGitInfo(id);
}

function timeAgo(isoDate) {
  try {
    var diff = Math.floor((Date.now() - new Date(isoDate).getTime()) / 1000);
    if (diff < 60) return 'just now';
    if (diff < 3600) return Math.floor(diff / 60) + ' min ago';
    if (diff < 86400) return Math.floor(diff / 3600) + ' hr ago';
    var d = Math.floor(diff / 86400); return d + ' day' + (d !== 1 ? 's' : '') + ' ago';
  } catch(e) { return ''; }
}

function renderGitBar(data) {
  var summaryEl = document.getElementById('gitBarSummary');
  if (!summaryEl) return;
  var text;
  if (!data.available) {
    text = '\u2387 Git unavailable \u2014 workspace history requires git on PATH';
  } else if (!data.repoExists) {
    text = '\u2387 Git repo not initialized';
  } else if (data.commitCount === 0) {
    text = '\u2387 ' + esc(data.branch || 'main') + '  \u2022  0 commits  \u2022  no history yet';
  } else {
    var lc = data.lastCommit || {};
    var subj = (lc.subject || '');
    var subjTrunc = subj.length > 55 ? subj.substring(0, 55) + '\u2026' : subj;
    text = '\u2387 ' + esc(data.branch) + '  \u2022  ' + data.commitCount + ' commit' + (data.commitCount !== 1 ? 's' : '')
      + '  \u2022  last: \u201c' + esc(subjTrunc) + '\u201d (' + timeAgo(lc.date) + ')';
  }
  summaryEl.textContent = text;
  if (gitPanelExpanded && data.recent) renderGitLog(data.recent);
}

function renderGitCommitList(commits) {
  var el = document.getElementById('gitCommitList');
  if (!el) return;
  if (!commits || commits.length === 0) {
    el.innerHTML = '<div class="git-empty">No commits yet.</div>';
    return;
  }
  var html = '';
  for (var i = 0; i < commits.length; i++) {
    var c = commits[i];
    html += '<div class="git-commit-row" id="gc-' + i + '">'
      + '<button class="git-commit-header" onclick="toggleGitCommit(' + i + ')">'
      +   '<span class="git-commit-chevron">\u25b6</span>'
      +   '<span class="git-commit-hash">' + esc(c.shortHash) + '</span>'
      +   '<span class="git-commit-subject">' + esc(c.subject) + '</span>'
      +   '<span class="git-commit-date">' + esc(timeAgo(c.date)) + '</span>'
      + '</button>'
      + '<div class="git-commit-body" style="display:none;">'
      +   (c.body ? '<pre class="git-commit-body-pre">' + esc(c.body) + '</pre>'
                  : '<span class="git-commit-no-body">No additional message body.</span>')
      + '</div>'
      + '</div>';
  }
  el.innerHTML = html;
}

function toggleGitCommit(idx) {
  var row = document.getElementById('gc-' + idx);
  if (!row) return;
  var body = row.querySelector('.git-commit-body');
  var chevron = row.querySelector('.git-commit-chevron');
  if (!body) return;
  var open = body.style.display !== 'none';
  body.style.display = open ? 'none' : '';
  if (chevron) chevron.textContent = open ? '\u25b6' : '\u25bc';
  row.classList.toggle('open', !open);
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

function applyProjectEntry(entry) {
  // Use the family key so historical version loads don't create extra tabs.
  var tabKey = entry.familyId || entry.id;
  projects[tabKey] = entry;
}

function maybeAutoExpandVH(runId) {
  vhExpanded = true;
  var chevron = document.getElementById('vhChevron');
  if (chevron) chevron.innerHTML = '&#9660;';
  if (vhData !== null && runId === currentRunId()) renderVersionHistory(selectedId, vhData);
}

function selectOrHydrateRun(runId) {
  // Check if this run is already the active run in some family tab.
  for (var k in projects) {
    if (k === runId || projects[k].id === runId) {
      if (selectedId !== k) selectTab(k);
      maybeAutoExpandVH(runId);
      return;
    }
  }
  // Need to load the run from the server; update its family tab in-place (no new tab).
  fetch('/api/project-view?id=' + encodeURIComponent(runId))
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (data.error) { showViewError('Load failed: ' + data.error); return; }
      var key = data.familyId || data.id;
      projects[key] = data;
      if (selectedId !== key) {
        selectTab(key);
      } else {
        panelBuiltFor = null;
        renderMain();
      }
      maybeAutoExpandVH(runId);
    })
    .catch(function() { showViewError('Network error loading run'); });
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

// Returns the actual run ID of the run currently displayed in the selected tab.
// The tab key (selectedId) equals the familyId; the run stored there may be a newer iteration.
function currentRunId() {
  var p = selectedId && projects[selectedId];
  return (p && p.id) ? p.id : (selectedId || '');
}

// True if candidate should replace current as the tab's displayed run.
function shouldReplaceWith(current, candidate) {
  var cArchived = current.state && current.state.archived;
  var nArchived = candidate.state && candidate.state.archived;
  if (cArchived && !nArchived) return true;
  if (!cArchived && nArchived) return false;
  var cActive = isActiveRun(current);
  var nActive = isActiveRun(candidate);
  if (nActive && !cActive) return true;
  if (cActive && !nActive) return false;
  var cTime = (current.state && current.state.startedAt) || 0;
  var nTime = (candidate.state && candidate.state.startedAt) || 0;
  return nTime > cTime;
}

function isActiveRun(p) {
  var s = p && p.state && p.state.status;
  return s === 'running' || s === 'retrying' || s === 'diagnosing' || s === 'repairing' || s === 'paused';
}

function applyUpdate(data) {
  if (!data.projects) return;
  // Group by family: one tab per familyId, showing the best (active > most recent) run.
  var familyBest = {};
  for (var i = 0; i < data.projects.length; i++) {
    var p = data.projects[i];
    var key = p.familyId || p.id;
    if (!familyBest[key] || shouldReplaceWith(familyBest[key], p)) familyBest[key] = p;
  }
  var incoming = {};
  for (var key in familyBest) {
    var p = familyBest[key];
    var isNew = !projects[key];
    var prevStatus = projects[key] && projects[key].state && projects[key].state.status;
    projects[key] = p;
    incoming[key] = true;
    var newSt = p.state && p.state.status;
    prevStatuses[key] = newSt;
    if (isNew && selectedId === DASHBOARD_TAB_ID && _storedTab === null && !closedTabs[key]) selectedId = key;
    // Flash dashboard card when any project transitions to completed
    if (!isNew && prevStatus !== 'completed' && newSt === 'completed') {
      flashDashCard(key);
    }
    // Refresh git info after any terminal-state transition for the active project
    if (!isNew && prevStatus && prevStatus !== newSt &&
        (newSt === 'completed' || newSt === 'failed' || newSt === 'cancelled' || newSt === 'paused')) {
      if (key === selectedId) {
        var _gitRefreshId = key;
        setTimeout(function() { loadGitInfo(_gitRefreshId); }, 500);
      }
    }
  }
  // Remove any local entries no longer reported by the server (e.g. deleted runs).
  for (var existingKey in projects) {
    if (!incoming[existingKey]) {
      delete projects[existingKey];
      delete prevStatuses[existingKey];
      if (closedTabs[existingKey]) { delete closedTabs[existingKey]; saveClosedTabs(); }
      if (selectedId === existingKey) {
        selectedId = DASHBOARD_TAB_ID;
        panelBuiltFor = null;
      }
    }
  }
  renderTabs();
  if (selectedId === DASHBOARD_TAB_ID || (selectedId && projects[selectedId])) renderMain();
  var archivedView = document.getElementById('viewArchived');
  if (archivedView && archivedView.style.display !== 'none') renderArchivedView();
  updateFooterRunCount();
}

// ── SSE connection ───────────────────────────────────────────────────────────
function setConnected(live) {
  var fdot = document.getElementById('footerConnDot');
  if (fdot) fdot.className = 'conn-dot' + (live ? ' live' : ' offline');
  var ftxt = document.getElementById('footerConnText');
  if (ftxt) ftxt.textContent = live ? 'Online' : 'Offline';
}

function updateFooterRunCount() {
  var count = 0;
  for (var id in projects) {
    if (projects[id].state && projects[id].state.status === 'running') count++;
  }
  var el = document.getElementById('footerRunCount');
  if (!el) return;
  if (count > 0) {
    el.textContent = count + ' running';
    el.style.display = '';
  } else {
    el.style.display = 'none';
  }
}

var sseDelay = 500;
var appSettings = {};
var agentApiKeyStatus = { anthropic: false, openai: false, gemini: false };
var agentCliStatus    = { anthropic: false, openai: false, gemini: false, copilot: false };

function hasUsableAgent() {
  var isTrue = function(v) { return v === true || v === 'true'; };
  return isTrue(appSettings.provider_anthropic_enabled) ||
         isTrue(appSettings.provider_openai_enabled)    ||
         isTrue(appSettings.provider_gemini_enabled)    ||
         isTrue(appSettings.provider_copilot_enabled);
}

function applyCreatePageAgentState() {
  var usable = hasUsableAgent();
  var overlay = document.getElementById('noAgentOverlay');
  if (overlay) overlay.style.display = usable ? 'none' : 'flex';
  var inputs = ['nlInput', 'dotPreview', 'createSimulate', 'createAutoApprove', 'generateBtn', 'runBtn'];
  inputs.forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.disabled = !usable;
  });
  var uploadLink = document.querySelector('.dot-upload-link');
  if (uploadLink) uploadLink.style.pointerEvents = usable ? '' : 'none';
}

function updateAgentWarning() {
  var banner = document.getElementById('agentWarningBanner');
  var msg    = document.getElementById('agentWarningMsg');
  if (!banner || !msg) return;

  var anyEnabled = hasUsableAgent();
  if (!anyEnabled) {
    msg.textContent = 'No AI providers are enabled. Enable at least one provider in Settings to use Attractor.';
    banner.style.display = 'flex';
  } else {
    banner.style.display = 'none';
  }
  applyCreatePageAgentState();
}

function loadSettings() {
  fetch('/api/settings')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      appSettings = s;
      var anthEl = document.getElementById('settingAnthropicEnabled');
      if (anthEl) anthEl.checked = s.provider_anthropic_enabled !== false;
      var oaiEl = document.getElementById('settingOpenAIEnabled');
      if (oaiEl) oaiEl.checked = s.provider_openai_enabled !== false;
      var gemEl = document.getElementById('settingGeminiEnabled');
      if (gemEl) gemEl.checked = s.provider_gemini_enabled !== false;
      var copilotEl = document.getElementById('settingCopilotEnabled');
      if (copilotEl) copilotEl.checked = s.provider_copilot_enabled === true || s.provider_copilot_enabled === 'true';
      var customEl = document.getElementById('settingCustomEnabled');
      if (customEl) customEl.checked = s.provider_custom_enabled === true || s.provider_custom_enabled === 'true';
      var customHost = document.getElementById('customApiHost');
      if (customHost) customHost.value = s.custom_api_host || 'http://localhost';
      var customPort = document.getElementById('customApiPort');
      if (customPort) customPort.value = s.custom_api_port || '11434';
      var customKey = document.getElementById('customApiKey');
      if (customKey) customKey.value = s.custom_api_key || '';
      var customModel = document.getElementById('customApiModel');
      if (customModel) customModel.value = s.custom_api_model || 'llama3.2';
      var anthCmd = document.getElementById('cliCmdAnthropic');
      if (anthCmd) anthCmd.value = s.cli_anthropic_command || 'claude --dangerously-skip-permissions -p {prompt}';
      var oaiCmd = document.getElementById('cliCmdOpenAI');
      if (oaiCmd) oaiCmd.value = s.cli_openai_command || 'codex exec --full-auto {prompt}';
      var gemCmd = document.getElementById('cliCmdGemini');
      if (gemCmd) gemCmd.value = s.cli_gemini_command || 'gemini --yolo -p {prompt}';
      var copilotCmd = document.getElementById('cliCmdCopilot');
      if (copilotCmd) copilotCmd.value = s.cli_copilot_command || 'copilot --allow-all-tools -p {prompt}';
      var mode = s.execution_mode || 'api';
      applyExecutionModeUi(mode);
      updateAgentWarning();
      applyCreatePageAgentState();
      if (mode === 'api') loadApiKeyStatus();
      if (mode === 'cli') loadCliStatus();
      loadSystemToolsStatus();
    })
    .catch(function() {});
}

function setExecutionMode(mode) {
  saveSetting('execution_mode', mode);
  applyExecutionModeUi(mode);
  if (mode === 'cli') loadCliStatus();
  if (mode === 'api') loadApiKeyStatus();
}

function applyExecutionModeUi(mode) {
  var apiBtn = document.getElementById('modeApiBtn');
  var cliBtn = document.getElementById('modeCliBtn');
  var cliFields = ['cliCmdAnthropic', 'cliCmdOpenAI', 'cliCmdGemini', 'cliCmdCopilot'];
  var cliBadges = ['cliBadgeAnthropic', 'cliBadgeOpenAI', 'cliBadgeGemini', 'cliBadgeCopilot'];
  var apiBadges = ['apiBadgeAnthropic', 'apiBadgeOpenAI', 'apiBadgeGemini', 'apiBadgeCustom'];
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
  var apiYoloWarning = document.getElementById('apiYoloWarning');
  if (apiYoloWarning) apiYoloWarning.style.display = mode === 'api' ? 'block' : 'none';
  var yoloWarning = document.getElementById('cliYoloWarning');
  if (yoloWarning) yoloWarning.style.display = mode === 'cli' ? 'block' : 'none';
  var dockerWarning = document.getElementById('cliDockerWarning');
  if (dockerWarning) dockerWarning.style.display = (mode === 'cli' && IS_DOCKER) ? 'block' : 'none';
  cliFields.forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.style.display = mode === 'cli' ? 'block' : 'none';
  });
  cliBadges.forEach(function(id) {
    var el = document.getElementById(id);
    if (!el) return;
    if (mode === 'cli') {
      el.style.display = 'inline';
      el.className = '';
      el.style.color = '#d97706';
      el.innerHTML = '<span class="badge-dot-checking">checking\u2026</span>';
    } else {
      el.style.display = 'none';
    }
  });
  apiBadges.forEach(function(id) {
    var el = document.getElementById(id);
    if (el) el.style.display = mode === 'api' ? 'inline' : 'none';
  });
  // Copilot is CLI-only — hide the entire row in Direct API mode
  var copilotRow = document.getElementById('copilotProviderRow');
  if (copilotRow) copilotRow.style.display = mode === 'cli' ? '' : 'none';
  // Custom is API-only — hide the entire row in CLI mode
  var customRow = document.getElementById('customProviderRow');
  if (customRow) customRow.style.display = mode === 'api' ? '' : 'none';
  // Show/hide custom fields based on toggle state
  applyCustomApiFieldsVisibility();
  var apiKeyNote = document.getElementById('apiKeyRestartNote');
  if (apiKeyNote) apiKeyNote.style.display = mode === 'api' ? 'block' : 'none';
  var cliPromptHint = document.getElementById('cliPromptHint');
  if (cliPromptHint) cliPromptHint.style.display = mode === 'cli' ? 'inline' : 'none';
}

function applyCustomApiFieldsVisibility() {
  var toggle = document.getElementById('settingCustomEnabled');
  var fields = document.getElementById('customApiFields');
  if (!fields) return;
  var show = toggle && toggle.checked;
  fields.style.display = show ? 'flex' : 'none';
}

function loadCliStatus() {
  var ids = ['cliBadgeAnthropic', 'cliBadgeOpenAI', 'cliBadgeGemini', 'cliBadgeCopilot'];
  ids.forEach(function(id) {
    var el = document.getElementById(id);
    if (!el || el.style.display === 'none') return;
    el.className = '';
    el.style.color = '#d97706';
    el.innerHTML = '<span class="badge-dot-checking">checking\u2026</span>';
  });
  fetch('/api/settings/cli-status')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      function badge(id, detected) {
        var el = document.getElementById(id);
        if (!el) return;
        el.className = '';
        el.style.color = detected ? '#3c9e5f' : '#c0392b';
        el.innerHTML = detected ? 'detected' : 'not found';
      }
      function applyToggle(toggleId, detected) {
        var toggle = document.getElementById(toggleId);
        if (!toggle) return;
        toggle.disabled = !detected;
        if (!detected) toggle.checked = false;
      }
      badge('cliBadgeAnthropic', s.anthropic); applyToggle('settingAnthropicEnabled', s.anthropic);
      badge('cliBadgeOpenAI',    s.openai);    applyToggle('settingOpenAIEnabled',    s.openai);
      badge('cliBadgeGemini',    s.gemini);    applyToggle('settingGeminiEnabled',    s.gemini);
      badge('cliBadgeCopilot',   s.copilot);   applyToggle('settingCopilotEnabled',   s.copilot);
      agentCliStatus = { anthropic: !!s.anthropic, openai: !!s.openai, gemini: !!s.gemini, copilot: !!s.copilot };
      updateAgentWarning();
    })
    .catch(function() {
      ids.forEach(function(id) {
        var el = document.getElementById(id);
        if (!el) return;
        el.className = '';
        el.textContent = '\u2717 not found';
        el.style.color = '#c0392b';
      });
      ['settingAnthropicEnabled','settingOpenAIEnabled','settingGeminiEnabled','settingCopilotEnabled'].forEach(function(id) {
        var t = document.getElementById(id); if (t) { t.disabled = true; t.checked = false; }
      });
      agentCliStatus = { anthropic: false, openai: false, gemini: false, copilot: false };
      updateAgentWarning();
    });
}

function loadApiKeyStatus() {
  fetch('/api/settings/api-key-status')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      function apiBadge(badgeId, toggleId, found, envHint) {
        var badge = document.getElementById(badgeId);
        if (badge) {
          badge.style.color = found ? '#3c9e5f' : '#c0392b';
          if (found) {
            badge.innerHTML = '\u25cf key found';
          } else {
            var hint = '<span class="api-key-hint" data-tip="Set ' + envHint + ' in your environment">?</span>';
            badge.innerHTML = '\u2717 key not set' + hint;
          }
        }
        var toggle = document.getElementById(toggleId);
        if (toggle) {
          toggle.disabled = !found;
          if (!found) toggle.checked = false;
        }
      }
      apiBadge('apiBadgeAnthropic', 'settingAnthropicEnabled', s.anthropic, 'ATTRACTOR_ANTHROPIC_API_KEY');
      apiBadge('apiBadgeOpenAI',    'settingOpenAIEnabled',    s.openai,    'ATTRACTOR_OPENAI_API_KEY');
      apiBadge('apiBadgeGemini',    'settingGeminiEnabled',    s.gemini,    'ATTRACTOR_GEMINI_API_KEY or ATTRACTOR_GOOGLE_API_KEY');
      // Custom: badge shows reachability, not a key requirement
      var customBadge = document.getElementById('apiBadgeCustom');
      if (customBadge) {
        customBadge.style.color = s.custom ? '#3c9e5f' : '#c0392b';
        customBadge.innerHTML = s.custom ? '\u25cf reachable' : '\u2717 unreachable';
      }
      agentApiKeyStatus = { anthropic: !!s.anthropic, openai: !!s.openai, gemini: !!s.gemini };
      updateAgentWarning();
    })
    .catch(function() {});
}

var toastTimer = null;
function showToast(message, type) {
  var el = document.getElementById('toast');
  if (!el) return;
  if (toastTimer) clearTimeout(toastTimer);
  var icon = type === 'success' ? '✓' : '✕';
  el.innerHTML = '<span class="toast-icon">' + icon + '</span><span class="toast-text">' + message + '</span>';
  el.className = 'toast-show toast-' + type;
  toastTimer = setTimeout(function() { el.className = ''; }, 3000);
}

var systemToolsRequired = { git:'git', java:'java', dot:'graphviz' };
var requiredToolsStatus = {};
var systemToolsOptional = {
  python3:'python3', ruby:'ruby', node:'node', go:'go', rustc:'rustc',
  gcc:'gcc', gxx:'g++', clang:'clang', clangxx:'clang++',
  make:'make', gradle:'gradle', mvn:'mvn', docker:'docker', curl:'curl'
};
var allSystemTools = Object.assign({}, systemToolsRequired, systemToolsOptional);

function renderToolBadges(gridId, toolMap) {
  var grid = document.getElementById(gridId);
  if (!grid) return;
  grid.innerHTML = Object.keys(toolMap).map(function(id) {
    var hint = (id === 'docker' && IS_DOCKER)
      ? '<span class="tool-badge-hint" data-tip="Docker is not available by default inside a container. To enable it, start with PROFILES=docker to mount the host Docker socket.">?</span>'
      : '';
    return '<div class="tool-badge" id="toolBadge_' + id + '">' +
      '<span class="tool-badge-name">' + toolMap[id] + hint + '</span>' +
      '<span class="tool-badge-status"><span class="badge-dot-checking">checking\u2026</span></span>' +
      '</div>';
  }).join('');
}

function updateRequiredToolsWarning() {
  var banner = document.getElementById('requiredToolsWarningBanner');
  var msg    = document.getElementById('requiredToolsWarningMsg');
  if (!banner || !msg) return;
  var missing = Object.keys(systemToolsRequired).filter(function(id) {
    return !requiredToolsStatus[id];
  }).map(function(id) { return systemToolsRequired[id]; });
  if (missing.length > 0) {
    msg.textContent = 'Required system tool' + (missing.length > 1 ? 's' : '') +
      ' not found: ' + missing.join(', ') + '. Core functionality may not work correctly.';
    banner.style.display = 'flex';
  } else {
    banner.style.display = 'none';
  }
}

function loadSystemToolsStatus() {
  renderToolBadges('systemToolsRequired', systemToolsRequired);
  renderToolBadges('systemToolsOptional', systemToolsOptional);
  fetch('/api/settings/system-tools-status')
    .then(function(r) { return r.json(); })
    .then(function(s) {
      Object.keys(allSystemTools).forEach(function(id) {
        var badge = document.getElementById('toolBadge_' + id);
        if (!badge) return;
        var found = !!s[id];
        var st = badge.querySelector('.tool-badge-status');
        st.className = 'tool-badge-status ' + (found ? 'found' : 'missing');
        st.textContent = found ? 'detected' : 'not found';
      });
      Object.keys(systemToolsRequired).forEach(function(id) { requiredToolsStatus[id] = !!s[id]; });
      updateRequiredToolsWarning();
    })
    .catch(function() {
      Object.keys(allSystemTools).forEach(function(id) {
        var badge = document.getElementById('toolBadge_' + id);
        if (!badge) return;
        var st = badge.querySelector('.tool-badge-status');
        st.className = 'tool-badge-status missing';
        st.textContent = 'not found';
      });
      Object.keys(systemToolsRequired).forEach(function(id) { requiredToolsStatus[id] = false; });
      updateRequiredToolsWarning();
    });
}

function saveSetting(key, value) {
  appSettings[key] = value;
  if (key.startsWith('provider_')) updateAgentWarning();
  fetch('/api/settings/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key: key, value: String(value) })
  })
  .then(function(r) {
    if (r.ok) showToast('Setting saved', 'success');
    else showToast('Failed to save setting', 'error');
  })
  .catch(function() { showToast('Failed to save setting', 'error'); });
}

function connectSSE() {
  var es = new EventSource('/events');
  es.onopen = function() {
    setConnected(true);
    sseDelay = 500;
    // Explicit snapshot fetch on (re)connect for state convergence.
    fetch('/api/projects')
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

// ── DOT download ──────────────────────────────────────────────────────────────
function downloadDot(content, filename) {
  if (!content) return;
  var blob = new Blob([content], { type: 'text/plain' });
  var url = URL.createObjectURL(blob);
  var a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}
function downloadCreateDot() {
  var content = document.getElementById('dotPreview').value.trim();
  downloadDot(content, uploadedFileName || 'project.dot');
}
function downloadMonitorDot() {
  var p = selectedId && projects[selectedId];
  if (!p || !p.dotSource) return;
  downloadDot(p.dotSource, p.fileName || 'project.dot');
}

// ── DOT file upload state ─────────────────────────────────────────────────────
var uploadedFileName = null;

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
    nlInput.placeholder = 'Describe modifications to make to the existing project\u2026';
  }
  if (runBtn) { runBtn.disabled = false; runBtn.innerHTML = '&#9654;&ensp;Iterate'; }
  if (cancelBtn) cancelBtn.style.display = 'inline-block';
  setGenStatus('', 'Iterate mode \u2014 edit description and click Generate, or edit DOT directly.');
  renderGraph();
}

function exitIterateMode() {
  iterateSourceId = null;
  var nlInput = document.getElementById('nlInput');
  var genHint = document.getElementById('genHint');
  var cancelBtn = document.getElementById('cancelIterateBtn');
  var runBtn = document.getElementById('runBtn');
  if (nlInput) nlInput.placeholder = 'e.g. \u201cWrite comprehensive unit tests for a Python web app, run them, fix any failures, then generate a coverage report\u201d\n\nDescribe what you want in plain English. The project will be generated automatically as you type.';
  if (genHint) genHint.textContent = 'You can edit the DOT source before running.';
  if (cancelBtn) cancelBtn.style.display = 'none';
  if (runBtn) runBtn.innerHTML = '&#9654;&ensp;Create';
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

function iterateProject() {
  if (!selectedId || !projects[selectedId]) return;
  var p = projects[selectedId];
  if (!p.dotSource) return;
  enterIterateMode(currentRunId(), p.dotSource, p.originalPrompt || '');
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
        if (runBtn) { runBtn.disabled = false; runBtn.innerHTML = '&#9654;&ensp;Iterate'; }
        return;
      }
      exitIterateMode();
      kickPoll();
      showView('monitor');
    })
    .catch(function(e) {
      setGenStatus('error', 'Failed: ' + String(e));
      if (runBtn) { runBtn.disabled = false; runBtn.innerHTML = '&#9654;&ensp;Iterate'; }
    });
}

// ── Adaptive polling ─────────────────────────────────────────────────────────
// 300ms while any project is running, 2s otherwise.
// Fallback and safety net; SSE is the primary fast-path.
// pollGen invalidates stale timers when kickPoll() restarts the loop.
var pollGen = 0;

function poll(gen) {
  if (gen !== pollGen) return;
  fetch('/api/projects')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (gen !== pollGen) return;
      applyUpdate(data);
      // Poll fast while any project is idle or running (not yet in a terminal or paused state).
      var active = Object.keys(projects).some(function(id) {
        var s = projects[id].state && projects[id].state.status;
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
  var dotFileInput = document.getElementById('dotFileInput');
  if (nlInput)    { nlInput.value = ''; }
  if (dotPreview) { dotPreview.value = ''; }
  if (runBtn)     { runBtn.disabled = true; runBtn.innerHTML = '&#9654;&ensp;Create'; }
  if (graphContent) { graphContent.innerHTML = '<div class="graph-placeholder">Generate a project first to see the graph.</div>'; }
  var createDownloadBtn = document.getElementById('createDownloadBtn');
  if (createDownloadBtn) createDownloadBtn.style.display = 'none';
  if (dotFileInput) { dotFileInput.value = ''; }
  uploadedFileName = null;
  setGenStatus('', 'Start typing to generate\u2026');
}

function showView(name) {
  var isMonitor  = name === 'monitor';
  var isCreate   = name === 'create';
  var isArchived = name === 'archived';
  var isSettings = name === 'settings';
  if (!isCreate && iterateSourceId) exitIterateMode();
  if (isCreate && !iterateSourceId) clearCreateForm();
  try { localStorage.setItem('activeView', name); } catch(e) {}
  document.getElementById('viewMonitor').style.display  = isMonitor  ? '' : 'none';
  document.getElementById('viewCreate').style.display   = isCreate   ? '' : 'none';
  document.getElementById('viewArchived').style.display = isArchived ? '' : 'none';
  document.getElementById('viewSettings').style.display = isSettings ? '' : 'none';
  document.getElementById('navMonitor').classList.toggle('active', isMonitor);
  document.getElementById('navCreate').classList.toggle('active', isCreate);
  document.getElementById('navArchived').classList.toggle('active', isArchived);
  document.getElementById('navSettings').classList.toggle('active', isSettings);
  if (isCreate) applyCreatePageAgentState();
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
  var dlBtn = document.getElementById('createDownloadBtn');
  if (!dotSource) {
    content.innerHTML = '<div class="graph-placeholder">No DOT source yet.</div>';
    if (dlBtn) dlBtn.style.display = 'none';
    return;
  }
  content.innerHTML = '<div class="graph-loading">Rendering\u2026</div>';
  if (dlBtn) dlBtn.style.display = 'none';
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
      if (dlBtn) dlBtn.style.display = '';
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
  var chk = document.getElementById('settingDarkTheme');
  if (chk) chk.checked = (t === 'dark');
}
function toggleTheme() {
  var cur = document.documentElement.getAttribute('data-theme') || 'light';
  var next = cur === 'light' ? 'dark' : 'light';
  localStorage.setItem('attractor-theme', next);
  applyTheme(next);
}
function initTheme() {
  var saved = localStorage.getItem('attractor-theme') || 'light';
  applyTheme(saved);
}

document.addEventListener('DOMContentLoaded', function() {
  initTheme();
  loadSettings();
  var savedView = '';
  try { savedView = localStorage.getItem('activeView') || ''; } catch(e) {}
  if (savedView === 'create' || savedView === 'archived' || savedView === 'settings') showView(savedView);
  document.getElementById('nlInput').addEventListener('input', function() {
    var hasPrompt = !!this.value.trim();
    var generateBtn = document.getElementById('generateBtn');
    if (generateBtn) generateBtn.disabled = !hasPrompt;
    if (!hasPrompt) {
      setGenStatus('', iterateSourceId
        ? 'Iterate mode \u2014 edit description and click Generate, or edit DOT directly.'
        : 'Describe your project, then click Generate\u2026');
      document.getElementById('runBtn').disabled = true;
    }
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
    initDragPan(gpEl);
  }
});

function setGenStatus(cls, msg) {
  var el = document.getElementById('genStatus');
  el.className = 'gen-status' + (cls ? ' ' + cls : '');
  el.textContent = msg;
  if (cls === 'ok' || cls === 'error') {
    var generateBtn = document.getElementById('generateBtn');
    var hasPrompt = !!((document.getElementById('nlInput') || {}).value || '').trim();
    if (generateBtn) generateBtn.disabled = !hasPrompt;
  }
}

function onDotFileSelected() {
  var input = document.getElementById('dotFileInput');
  if (!input || !input.files || input.files.length === 0) return;
  var file = input.files[0];
  // Cancel any pending NL auto-generate to prevent overwriting uploaded DOT
  clearTimeout(genDebounce);
  clearInterval(genCountdown);
  var reader = new FileReader();
  reader.onload = function(e) {
    var dotSource = e.target.result || '';
    var preview = document.getElementById('dotPreview');
    var nlInput = document.getElementById('nlInput');
    if (preview) {
      preview.value = dotSource;
      if (nlInput) nlInput.value = '';
      uploadedFileName = file.name;
      document.getElementById('runBtn').disabled = !dotSource.trim();
      setGenStatus('ok', 'Loaded: ' + file.name);
      renderRetries = 0;
      renderGraph();
      if (dotSource.trim()) describeUploadedDot(dotSource);
    }
  };
  reader.onerror = function() {
    setGenStatus('error', 'Could not read file.');
  };
  reader.readAsText(file, 'UTF-8');
}

function describeUploadedDot(dotSource) {
  var nlInput = document.getElementById('nlInput');
  if (!nlInput) return;
  fetch('/api/describe-dot/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dotSource: dotSource })
  })
  .then(function(resp) {
    if (!resp.ok) return;
    var reader = resp.body.getReader();
    var decoder = new TextDecoder();
    var buf = '';
    var finished = false;
    function read() {
      reader.read().then(function(result) {
        if (result.done) return;
        buf += decoder.decode(result.value, { stream: true });
        var lines = buf.split('\n');
        buf = lines.pop();
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i];
          if (!line.startsWith('data: ')) continue;
          try {
            var evt = JSON.parse(line.slice(6));
            if (evt.delta !== undefined) {
              nlInput.value += evt.delta;
            } else if (evt.done || evt.error) {
              finished = true;
            }
          } catch (x) {}
        }
        if (!finished) read();
      }).catch(function() {});
    }
    read();
  })
  .catch(function() {});
}

function triggerGenerate() {
  var prompt = (document.getElementById('nlInput').value || '').trim();
  if (!prompt) return;
  var verb = iterateSourceId ? 'Modifying' : 'Generating';
  setGenStatus('loading', verb + '\u2026');
  var generateBtn = document.getElementById('generateBtn');
  if (generateBtn) generateBtn.disabled = true;
  if (iterateSourceId) {
    modifyDot(prompt, document.getElementById('dotPreview').value.trim());
  } else {
    generateDot(prompt);
  }
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
  var p = selectedId && projects[selectedId];
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
  fetch('/api/stage-log?id=' + encodeURIComponent(currentRunId()) + '&stage=' + encodeURIComponent(stageLogNodeId))
    .then(function(r) { return r.text(); })
    .then(function(text) {
      stageLogContent = text || '';
      var pre = document.getElementById('stage-log-pre-' + stageLogNodeId);
      if (!pre) return;
      var atBottom = (pre.scrollHeight - pre.scrollTop) <= (pre.clientHeight + 60);
      pre.textContent = stageLogContent || '(no output yet)';
      if (atBottom) pre.scrollTop = pre.scrollHeight;
      // Stop polling once stage is done
      var p = selectedId && projects[selectedId];
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


// ── Project graph visualization ─────────────────────────────────────────────
function renderProjectGraph(id) {
  var p = projects[id];
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
      inner.innerHTML = '<div class="project-graph-error">' + esc(resp.error) + '</div>';
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

// ── Graph drag-to-pan ─────────────────────────────────────────────────────────
function initDragPan(el) {
  var dragging = false;
  var startX, startY, scrollLeft, scrollTop;
  el.addEventListener('mousedown', function(e) {
    if (e.target.closest('button, a, input, select')) return;
    dragging = true;
    startX = e.clientX;
    startY = e.clientY;
    scrollLeft = el.scrollLeft;
    scrollTop = el.scrollTop;
    el.style.cursor = 'grabbing';
    e.preventDefault();
  });
  el.addEventListener('mousemove', function(e) {
    if (!dragging) return;
    el.scrollLeft = scrollLeft - (e.clientX - startX);
    el.scrollTop  = scrollTop  - (e.clientY - startY);
  });
  function stopDrag() {
    if (!dragging) return;
    dragging = false;
    el.style.cursor = '';
  }
  el.addEventListener('mouseup', stopDrag);
  el.addEventListener('mouseleave', stopDrag);
}

function exportRun() {
  if (!selectedId) return;
  var a = document.createElement('a');
  a.href = '/api/export-run?id=' + encodeURIComponent(currentRunId());
  a.download = '';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function cancelProject() {
  var btn = document.getElementById('cancelBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Cancelling\u2026';
  fetch('/api/cancel', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: currentRunId() })
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

function pauseProject() {
  var btn = document.getElementById('pauseBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Pausing\u2026';
  fetch('/api/pause', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: currentRunId() })
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

function resumeProject() {
  var btn = document.getElementById('resumeBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Resuming\u2026';
  fetch('/api/resume', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: currentRunId() })
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

function rerunProject() {
  var btn = document.getElementById('rerunBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Submitting\u2026';
  fetch('/api/rerun', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: currentRunId() })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.id) {
      kickPoll();
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

function archiveProject() {
  var btn = document.getElementById('archiveBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Archiving\u2026';
  var archiveRunId = currentRunId();
  var archiveTabKey = selectedId;
  fetch('/api/archive', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: archiveRunId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.archived) {
      // Optimistically mark the family tab as archived so renderTabs hides it
      if (projects[archiveTabKey] && projects[archiveTabKey].state) {
        projects[archiveTabKey].state.archived = true;
      }
      // Advance selectedId to next visible non-archived tab
      var ids = Object.keys(projects);
      var nextId = null;
      for (var i = 0; i < ids.length; i++) {
        if (ids[i] !== archiveTabKey && !projects[ids[i]].state.archived) {
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

function unarchiveProject() {
  var btn = document.getElementById('unarchiveBtn');
  if (!btn || !selectedId) return;
  btn.disabled = true;
  btn.textContent = 'Unarchiving\u2026';
  var unarchiveRunId = currentRunId();
  var unarchiveTabKey = selectedId;
  fetch('/api/unarchive', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: unarchiveRunId })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.unarchived) {
      if (projects[unarchiveTabKey] && projects[unarchiveTabKey].state) {
        projects[unarchiveTabKey].state.archived = false;
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
  var ids = Object.keys(projects);
  var archived = ids.filter(function(id) { return projects[id].state && projects[id].state.archived; });
  if (archived.length === 0) {
    content.innerHTML = '<div class="archived-empty">No archived runs.</div>';
    return;
  }
  var html = '<table class="archived-table"><thead><tr>'
    + '<th>Project</th><th>Status</th><th>Finished</th><th>Actions</th>'
    + '</tr></thead><tbody>';
  for (var i = archived.length - 1; i >= 0; i--) {
    var id = archived[i];
    var p = projects[id];
    var st = p.state || {};
    var name = st.project || p.fileName;
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
      if (projects[id] && projects[id].state) {
        projects[id].state.archived = false;
      }
      renderArchivedView();
      renderTabs();
      kickPoll();
    }
  })
  .catch(function() {});
}

// ── Delete project run ───────────────────────────────────────────────────────
var pendingDeleteId = null;
var pendingDeleteFamily = false;

function showDeleteConfirm(id) {
  pendingDeleteId = id;
  pendingDeleteFamily = false;
  var p = projects[id];
  var name = (p && p.state && p.state.project) ? p.state.project : (p ? p.fileName : id);
  var nameEl = document.getElementById('deleteModalName');
  if (nameEl) nameEl.textContent = name;
  var btn = document.getElementById('deleteConfirmBtn');
  if (btn) { btn.disabled = false; btn.innerHTML = '&#10005;&ensp;Delete Permanently'; }
  var modal = document.getElementById('deleteModal');
  if (modal) modal.classList.remove('hidden');
}

function closeDeleteModal() {
  pendingDeleteId = null;
  pendingDeleteFamily = false;
  var modal = document.getElementById('deleteModal');
  if (modal) modal.classList.add('hidden');
}

// ── Dashboard card quick-actions ─────────────────────────────────────────────
function dashCardArchive(tabKey, evt) {
  evt.stopPropagation();
  var p = projects[tabKey];
  if (!p) return;
  // If this run belongs to a family, archive all siblings; otherwise archive by run ID.
  var familyId = p.familyId;
  var runId = p.id || tabKey;
  var reqBody = familyId ? { familyId: familyId } : { id: runId };
  fetch('/api/archive', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(reqBody) })
    .then(function(r) { return r.json(); })
    .then(function(resp) {
      if (resp.archived) {
        if (projects[tabKey] && projects[tabKey].state) projects[tabKey].state.archived = true;
        if (selectedId === tabKey) {
          var ids = Object.keys(projects);
          var nextId = null;
          for (var i = 0; i < ids.length; i++) {
            if (ids[i] !== tabKey && !(projects[ids[i]].state && projects[ids[i]].state.archived)) nextId = ids[i];
          }
          selectedId = nextId || DASHBOARD_TAB_ID;
          panelBuiltFor = null;
        }
        renderTabs();
        renderMain();
        kickPoll();
      }
    });
}

function dashCardDelete(tabKey, evt) {
  evt.stopPropagation();
  pendingDeleteFamily = true;
  showDeleteConfirm(tabKey);
}

function executeDelete() {
  var id = pendingDeleteId;
  if (!id) return;
  // Resolve tab key
  var tabKey = id;
  for (var k in projects) {
    if (k === id || (projects[k] && projects[k].id === id)) { tabKey = k; break; }
  }
  var p = projects[tabKey];
  var familyId = p && p.familyId;
  var runId = (p && p.id) || tabKey;
  // Always delete the whole family when a familyId is available, regardless of entry point.
  var reqBody = familyId ? { familyId: familyId } : { id: runId };
  var btn = document.getElementById('deleteConfirmBtn');
  if (btn) { btn.disabled = true; btn.textContent = 'Deleting\u2026'; }
  fetch('/api/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(reqBody)
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    closeDeleteModal();
    if (resp.deleted) {
      // Pick next visible tab before removing from local state
      var allIds = Object.keys(projects);
      var nextId = null;
      for (var i = 0; i < allIds.length; i++) {
        if (allIds[i] !== tabKey) nextId = allIds[i];
      }
      delete projects[tabKey];
      if (selectedId === tabKey) {
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
    body: JSON.stringify({ dotSource: dotSource, fileName: uploadedFileName || 'generated.dot', simulate: simulate, autoApprove: autoApprove, originalPrompt: originalPrompt })
  })
  .then(function(r) { return r.json(); })
  .then(function(resp) {
    if (resp.error) {
      btn.disabled = false;
      btn.textContent = '\u25B6\u2002Create';
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
    btn.textContent = '\u25B6\u2002Create';
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
  document.getElementById('runBtn').textContent = '\u25B6\u2002Create';
  setGenStatus('', 'Start typing to generate\u2026');
  document.getElementById('graphContent').innerHTML = '<div class="graph-placeholder">Generate a project first to see the graph.</div>';
  var createDownloadBtn = document.getElementById('createDownloadBtn');
  if (createDownloadBtn) createDownloadBtn.style.display = 'none';
  var dotFileInput = document.getElementById('dotFileInput');
  if (dotFileInput) dotFileInput.value = '';
  uploadedFileName = null;
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
  if (btn) { btn.disabled = true; btn.textContent = 'Importing\u2026'; }
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
        if (btn) { btn.disabled = false; btn.textContent = 'Import'; }
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
<div id="app-footer">
  <div class="footer-left">
    <span id="footerConnDot" class="conn-dot offline"></span>
    <span id="footerConnText">Offline</span>
  </div>
  <div class="footer-right">
    <span id="footerRunCount"></span>
    <span>v$appVersion</span>${if (baseVersion != "dev") """
    <span>base: $baseVersion</span>""" else ""}${if (imageVersion != "dev") """
    <span>image: $imageVersion</span>""" else ""}
  </div>
</div>
<div id="toast"></div>
</body>
</html>"""
}
