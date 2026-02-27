package attractor.web

import attractor.db.RunStore
import attractor.dot.Parser
import attractor.lint.Validator
import attractor.llm.ModelCatalog
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RestApiRouter(
    private val registry: PipelineRegistry,
    private val store: RunStore,
    private val onUpdate: () -> Unit,
    private val getSseSnapshot: () -> String,
    private val sseClients: CopyOnWriteArrayList<RestSseClient>
) {

    data class RestSseClient(val ex: HttpExchange) {
        val queue = LinkedBlockingQueue<String>(512)
        @Volatile var alive = true
        fun offer(json: String) { if (alive) queue.offer(json) }
    }

    private val requestJson = Json { ignoreUnknownKeys = true }
    private val dotGenerator = DotGenerator(store)

    private val KNOWN_SETTINGS = setOf(
        "fireworks_enabled",
        "execution_mode",
        "provider_anthropic_enabled",
        "provider_openai_enabled",
        "provider_gemini_enabled",
        "cli_anthropic_command",
        "cli_openai_command",
        "cli_gemini_command"
    )

    private val TEXT_EXTENSIONS = setOf("log", "txt", "md", "json", "dot", "kt", "py", "js", "sh", "yaml", "yml", "toml", "xml", "html", "css")

    companion object {
        fun js(s: String): String =
            "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
    }

    private fun jsonResponse(ex: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun errorResponse(ex: HttpExchange, status: Int, message: String, code: String) {
        jsonResponse(ex, status, """{"error":${js(message)},"code":"$code"}""")
    }

    private fun readBody(ex: HttpExchange): String {
        val cl = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L
        if (cl > 10_000_000L) {
            throw IllegalArgumentException("Request body too large")
        }
        return ex.requestBody.readBytes().toString(Charsets.UTF_8)
    }

    private fun readJsonBody(ex: HttpExchange): kotlinx.serialization.json.JsonObject? {
        return try {
            val body = readBody(ex)
            if (body.isBlank()) return null
            requestJson.parseToJsonElement(body).jsonObject
        } catch (_: Exception) { null }
    }

    private fun parseQuery(ex: HttpExchange): Map<String, String> {
        val query = ex.requestURI.query ?: return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) null
            else {
                val key = java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                key to value
            }
        }.toMap()
    }

    fun handle(ex: HttpExchange) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")

        if (ex.requestMethod == "OPTIONS") {
            ex.sendResponseHeaders(204, -1)
            return
        }

        try {
            val path = ex.requestURI.path
            val stripped = path.removePrefix("/api/v1").trimEnd('/')
            val segments = stripped.split("/").filter { it.isNotBlank() }
            val method = ex.requestMethod

            when {
                // GET /api/v1/pipelines
                method == "GET" && segments == listOf("pipelines") ->
                    handleListPipelines(ex)

                // POST /api/v1/pipelines/import  (must check before /{id})
                method == "POST" && segments == listOf("pipelines", "import") ->
                    handleImportPipeline(ex)

                // POST /api/v1/pipelines
                method == "POST" && segments == listOf("pipelines") ->
                    handleCreatePipeline(ex)

                // GET /api/v1/pipelines/{id}
                method == "GET" && segments.size == 2 && segments[0] == "pipelines" ->
                    handleGetPipeline(ex, segments[1])

                // PATCH /api/v1/pipelines/{id}
                method == "PATCH" && segments.size == 2 && segments[0] == "pipelines" ->
                    handlePatchPipeline(ex, segments[1])

                // DELETE /api/v1/pipelines/{id}
                method == "DELETE" && segments.size == 2 && segments[0] == "pipelines" ->
                    handleDeletePipeline(ex, segments[1])

                // GET /api/v1/pipelines/{id}/artifacts.zip
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "artifacts.zip" ->
                    handleDownloadArtifactsZip(ex, segments[1])

                // GET /api/v1/pipelines/{id}/artifacts  (list)
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "artifacts" ->
                    handleListArtifacts(ex, segments[1])

                // GET /api/v1/pipelines/{id}/artifacts/{path...}
                method == "GET" && segments.size > 3 && segments[0] == "pipelines" && segments[2] == "artifacts" ->
                    handleGetArtifact(ex, segments[1], segments.drop(3).joinToString("/"))

                // GET /api/v1/pipelines/{id}/stages/{nodeId}/log
                method == "GET" && segments.size == 5 && segments[0] == "pipelines" && segments[2] == "stages" && segments[4] == "log" ->
                    handleGetStageLog(ex, segments[1], segments[3])

                // GET /api/v1/pipelines/{id}/stages
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "stages" ->
                    handleGetStages(ex, segments[1])

                // GET /api/v1/pipelines/{id}/failure-report
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "failure-report" ->
                    handleGetFailureReport(ex, segments[1])

                // GET /api/v1/pipelines/{id}/export
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "export" ->
                    handleExportPipeline(ex, segments[1])

                // GET /api/v1/pipelines/{id}/family
                method == "GET" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "family" ->
                    handleGetFamily(ex, segments[1])

                // POST /api/v1/pipelines/{id}/iterations
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "iterations" ->
                    handleCreateIteration(ex, segments[1])

                // POST /api/v1/pipelines/{id}/rerun
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "rerun" ->
                    handleRerunPipeline(ex, segments[1])

                // POST /api/v1/pipelines/{id}/pause
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "pause" ->
                    handlePausePipeline(ex, segments[1])

                // POST /api/v1/pipelines/{id}/resume
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "resume" ->
                    handleResumePipeline(ex, segments[1])

                // POST /api/v1/pipelines/{id}/cancel
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "cancel" ->
                    handleCancelPipeline(ex, segments[1])

                // POST /api/v1/pipelines/{id}/archive
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "archive" ->
                    handleArchivePipeline(ex, segments[1])

                // POST /api/v1/pipelines/{id}/unarchive
                method == "POST" && segments.size == 3 && segments[0] == "pipelines" && segments[2] == "unarchive" ->
                    handleUnarchivePipeline(ex, segments[1])

                // DOT operations
                method == "POST" && segments == listOf("dot", "render") ->
                    handleRenderDot(ex)
                method == "POST" && segments == listOf("dot", "validate") ->
                    handleValidateDot(ex)
                method == "POST" && segments == listOf("dot", "generate") ->
                    handleGenerateDot(ex)
                method == "GET" && segments == listOf("dot", "generate", "stream") ->
                    handleGenerateDotStream(ex)
                method == "POST" && segments == listOf("dot", "fix") ->
                    handleFixDot(ex)
                method == "GET" && segments == listOf("dot", "fix", "stream") ->
                    handleFixDotStream(ex)
                method == "POST" && segments == listOf("dot", "iterate") ->
                    handleIterateDot(ex)
                method == "GET" && segments == listOf("dot", "iterate", "stream") ->
                    handleIterateDotStream(ex)

                // Settings
                method == "GET" && segments == listOf("settings") ->
                    handleGetSettings(ex)
                method == "GET" && segments.size == 2 && segments[0] == "settings" ->
                    handleGetSetting(ex, segments[1])
                method == "PUT" && segments.size == 2 && segments[0] == "settings" ->
                    handlePutSetting(ex, segments[1])

                // Models
                method == "GET" && segments == listOf("models") ->
                    handleGetModels(ex)

                // Events
                method == "GET" && segments == listOf("events") ->
                    handleEvents(ex)
                method == "GET" && segments.size == 2 && segments[0] == "events" ->
                    handleEventsSingle(ex, segments[1])

                // API documentation
                method == "GET" && segments == listOf("openapi.json") ->
                    handleSpecJson(ex)
                method == "GET" && segments == listOf("openapi.yaml") ->
                    handleSpecYaml(ex)
                method == "GET" && segments == listOf("swagger.json") ->
                    handleSpecJson(ex)
                method == "GET" && segments == listOf("docs") ->
                    handleSwaggerUi(ex)

                else -> errorResponse(ex, 404, "not found", "NOT_FOUND")
            }
        } catch (e: Exception) {
            runCatching { errorResponse(ex, 500, e.message ?: "internal error", "INTERNAL_ERROR") }
        }
    }

    // ── Pipeline JSON helpers ─────────────────────────────────────────────────

    private fun pipelineEntryToJson(entry: PipelineEntry, full: Boolean): String {
        val state = entry.state
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":${js(entry.id)},")
        sb.append("\"displayName\":${js(entry.displayName)},")
        sb.append("\"fileName\":${js(entry.fileName)},")
        sb.append("\"status\":${js(state.status.get())},")
        sb.append("\"archived\":${state.archived.get()},")
        sb.append("\"hasFailureReport\":${state.hasFailureReport.get()},")
        sb.append("\"simulate\":${entry.options.simulate},")
        sb.append("\"autoApprove\":${entry.options.autoApprove},")
        sb.append("\"familyId\":${js(entry.familyId)},")
        sb.append("\"originalPrompt\":${js(entry.originalPrompt)},")
        if (full) sb.append("\"dotSource\":${js(entry.dotSource)},")
        val startedAt = state.startedAt.get()
        val finishedAt = state.finishedAt.get()
        sb.append("\"startedAt\":${if (startedAt > 0L) startedAt.toString() else "null"},")
        sb.append("\"finishedAt\":${if (finishedAt > 0L) finishedAt.toString() else "null"},")
        val currentNode = state.currentNode.get()
        sb.append("\"currentNode\":${if (currentNode.isNullOrBlank()) "null" else js(currentNode)},")
        // stages
        sb.append("\"stages\":[")
        state.stages.forEachIndexed { i, stage ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"index\":${stage.index},")
            sb.append("\"name\":${js(stage.name)},")
            sb.append("\"nodeId\":${js(stage.nodeId)},")
            sb.append("\"status\":${js(stage.status)},")
            sb.append("\"startedAt\":${stage.startedAt ?: "null"},")
            sb.append("\"durationMs\":${stage.durationMs ?: "null"},")
            sb.append("\"error\":${if (stage.error != null) js(stage.error) else "null"},")
            sb.append("\"hasLog\":${stage.hasLog}")
            sb.append("}")
        }
        sb.append("],")
        // logs
        val logList = state.recentLogs.toList()
        val logsToShow = if (logList.size > 50) logList.subList(logList.size - 50, logList.size) else logList
        sb.append("\"logs\":[")
        logsToShow.forEachIndexed { i, log ->
            if (i > 0) sb.append(",")
            sb.append(js(log))
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    // ── Pipeline CRUD ─────────────────────────────────────────────────────────

    private fun handleListPipelines(ex: HttpExchange) {
        val entries = registry.getAll()
        val sb = StringBuilder("[")
        entries.forEachIndexed { i, entry ->
            if (i > 0) sb.append(",")
            sb.append(pipelineEntryToJson(entry, full = false))
        }
        sb.append("]")
        jsonResponse(ex, 200, sb.toString())
    }

    private fun handleCreatePipeline(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val dotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        val fileName = body?.get("fileName")?.jsonPrimitive?.contentOrNull ?: ""
        val simulate = body?.get("simulate")?.jsonPrimitive?.booleanOrNull ?: false
        val autoApprove = body?.get("autoApprove")?.jsonPrimitive?.booleanOrNull ?: true
        val originalPrompt = body?.get("originalPrompt")?.jsonPrimitive?.contentOrNull ?: ""
        val familyId = body?.get("familyId")?.jsonPrimitive?.contentOrNull ?: ""
        val id = PipelineRunner.submit(
            dotSource = dotSource,
            fileName = fileName,
            options = RunOptions(simulate = simulate, autoApprove = autoApprove),
            registry = registry,
            store = store,
            originalPrompt = originalPrompt,
            familyId = familyId,
            onUpdate = onUpdate
        )
        jsonResponse(ex, 201, """{"id":${js(id)},"status":"running"}""")
    }

    private fun handleGetPipeline(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        jsonResponse(ex, 200, pipelineEntryToJson(entry, full = true))
    }

    private fun handlePatchPipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val body = readJsonBody(ex)
        val newDotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull
        val newOriginalPrompt = body?.get("originalPrompt")?.jsonPrimitive?.contentOrNull
        if (newDotSource != null) {
            val status = entry.state.status.get()
            if (status == "running" || status == "paused") {
                errorResponse(ex, 409, "cannot update dotSource while pipeline is running or paused", "INVALID_STATE"); return
            }
            registry.updateDotAndPrompt(id, newDotSource, newOriginalPrompt ?: entry.originalPrompt)
        }
        val updated = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        jsonResponse(ex, 200, pipelineEntryToJson(updated, full = true))
    }

    private fun handleDeletePipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val status = entry.state.status.get()
        if (status == "running" || status == "paused") {
            errorResponse(ex, 409, "cannot delete running or paused pipeline", "INVALID_STATE"); return
        }
        val (deleted, logsRoot) = registry.delete(id)
        if (logsRoot.isNotBlank()) {
            runCatching { java.io.File(logsRoot).deleteRecursively() }
        }
        jsonResponse(ex, 200, """{"deleted":$deleted}""")
    }

    // ── Pipeline Lifecycle ────────────────────────────────────────────────────

    private fun handleRerunPipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        if (entry.state.status.get() == "running") {
            errorResponse(ex, 409, "pipeline is already running", "INVALID_STATE"); return
        }
        PipelineRunner.resubmit(id, registry, store, onUpdate)
        jsonResponse(ex, 200, """{"id":${js(id)},"status":"running"}""")
    }

    private fun handlePausePipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        if (entry.state.status.get() != "running") {
            errorResponse(ex, 409, "pipeline is not running", "INVALID_STATE"); return
        }
        val paused = registry.pause(id)
        jsonResponse(ex, 200, """{"paused":$paused}""")
    }

    private fun handleResumePipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        if (entry.state.status.get() != "paused") {
            errorResponse(ex, 409, "pipeline is not paused", "INVALID_STATE"); return
        }
        PipelineRunner.resumePipeline(id, registry, store, onUpdate)
        jsonResponse(ex, 200, """{"id":${js(id)},"status":"running"}""")
    }

    private fun handleCancelPipeline(ex: HttpExchange, id: String) {
        val entry = registry.get(id) ?: registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val status = entry.state.status.get()
        if (status != "running" && status != "paused") {
            errorResponse(ex, 409, "pipeline is not running or paused", "INVALID_STATE"); return
        }
        val cancelled = registry.cancel(id)
        jsonResponse(ex, 200, """{"cancelled":$cancelled}""")
    }

    private fun handleArchivePipeline(ex: HttpExchange, id: String) {
        registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val archived = registry.archive(id)
        jsonResponse(ex, 200, """{"archived":$archived}""")
    }

    private fun handleUnarchivePipeline(ex: HttpExchange, id: String) {
        registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val unarchived = registry.unarchive(id)
        jsonResponse(ex, 200, """{"unarchived":$unarchived}""")
    }

    private fun handleCreateIteration(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val body = readJsonBody(ex)
        val dotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        val originalPrompt = body?.get("originalPrompt")?.jsonPrimitive?.contentOrNull ?: entry.originalPrompt
        val fileName = body?.get("fileName")?.jsonPrimitive?.contentOrNull ?: entry.fileName
        val newId = PipelineRunner.submit(
            dotSource = dotSource,
            fileName = fileName,
            options = entry.options,
            registry = registry,
            store = store,
            originalPrompt = originalPrompt,
            familyId = entry.familyId,
            onUpdate = onUpdate
        )
        jsonResponse(ex, 201, """{"id":${js(newId)},"status":"running","familyId":${js(entry.familyId)}}""")
    }

    // ── Family & Stages ───────────────────────────────────────────────────────

    private fun handleGetFamily(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val members = store.getByFamilyId(entry.familyId).take(100)
        val sb = StringBuilder("[")
        members.forEachIndexed { i, run ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":${js(run.id)},")
            sb.append("\"displayName\":${js(run.displayName)},")
            sb.append("\"fileName\":${js(run.fileName)},")
            sb.append("\"createdAt\":${run.createdAt},")
            sb.append("\"status\":${js(run.status)},")
            sb.append("\"versionNum\":${i + 1},")
            sb.append("\"originalPrompt\":${js(run.originalPrompt)}")
            sb.append("}")
        }
        sb.append("]")
        jsonResponse(ex, 200, """{"familyId":${js(entry.familyId)},"members":$sb}""")
    }

    private fun handleGetStages(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val sb = StringBuilder("[")
        entry.state.stages.forEachIndexed { i, stage ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"index\":${stage.index},")
            sb.append("\"name\":${js(stage.name)},")
            sb.append("\"nodeId\":${js(stage.nodeId)},")
            sb.append("\"status\":${js(stage.status)},")
            sb.append("\"startedAt\":${stage.startedAt ?: "null"},")
            sb.append("\"durationMs\":${stage.durationMs ?: "null"},")
            sb.append("\"error\":${if (stage.error != null) js(stage.error) else "null"},")
            sb.append("\"hasLog\":${stage.hasLog}")
            sb.append("}")
        }
        sb.append("]")
        jsonResponse(ex, 200, sb.toString())
    }

    // ── Artifacts & Logs ──────────────────────────────────────────────────────

    private fun handleListArtifacts(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val logsRoot = entry.logsRoot
        if (logsRoot.isBlank()) {
            jsonResponse(ex, 200, """{"files":[],"truncated":false}"""); return
        }
        val rootFile = java.io.File(logsRoot)
        if (!rootFile.exists()) {
            jsonResponse(ex, 200, """{"files":[],"truncated":false}"""); return
        }
        val allFiles = rootFile.walkTopDown().filter { it.isFile }.toList()
        val truncated = allFiles.size > 500
        val files = allFiles.take(500)
        val sb = StringBuilder("[")
        files.forEachIndexed { i, file ->
            if (i > 0) sb.append(",")
            val relPath = rootFile.toPath().relativize(file.toPath()).toString()
            val ext = file.extension.lowercase()
            val isText = ext in TEXT_EXTENSIONS
            sb.append("{\"path\":${js(relPath)},\"size\":${file.length()},\"isText\":$isText}")
        }
        sb.append("]")
        jsonResponse(ex, 200, """{"files":$sb,"truncated":$truncated}""")
    }

    private fun handleGetArtifact(ex: HttpExchange, id: String, relPath: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val logsRoot = entry.logsRoot
        if (logsRoot.isBlank()) {
            errorResponse(ex, 404, "no artifacts available", "NOT_FOUND"); return
        }
        val rootFile = java.io.File(logsRoot).canonicalFile
        val targetFile = java.io.File(logsRoot, relPath).canonicalFile
        if (!targetFile.path.startsWith(rootFile.path)) {
            errorResponse(ex, 404, "artifact not found", "NOT_FOUND"); return
        }
        if (!targetFile.exists() || !targetFile.isFile) {
            errorResponse(ex, 404, "artifact not found", "NOT_FOUND"); return
        }
        val ext = targetFile.extension.lowercase()
        val contentType = if (ext in TEXT_EXTENSIONS) "text/plain; charset=utf-8" else "application/octet-stream"
        val bytes = targetFile.readBytes()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleDownloadArtifactsZip(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val logsRoot = entry.logsRoot
        if (logsRoot.isBlank()) {
            errorResponse(ex, 404, "no artifacts available", "NOT_FOUND"); return
        }
        val rootFile = java.io.File(logsRoot)
        if (!rootFile.exists()) {
            errorResponse(ex, 404, "artifacts directory not found", "NOT_FOUND"); return
        }
        val safeName = entry.displayName.ifEmpty { entry.fileName }
            .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        ex.responseHeaders.add("Content-Type", "application/zip")
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"artifacts-$safeName.zip\"")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        try {
            ZipOutputStream(ex.responseBody).use { zip ->
                rootFile.walkTopDown().filter { it.isFile }.forEach { file ->
                    val entryName = rootFile.toPath().relativize(file.toPath()).toString()
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (_: Exception) { /* client disconnected */ }
    }

    private fun handleGetStageLog(ex: HttpExchange, id: String, nodeId: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        if (nodeId.contains("/") || nodeId.contains("..")) {
            errorResponse(ex, 404, "invalid nodeId", "NOT_FOUND"); return
        }
        val logsRoot = entry.logsRoot
        if (logsRoot.isBlank()) {
            errorResponse(ex, 404, "stage log not found", "NOT_FOUND"); return
        }
        val logFile = java.io.File(logsRoot, "$nodeId/live.log")
        if (!logFile.exists()) {
            errorResponse(ex, 404, "stage log not found", "NOT_FOUND"); return
        }
        val bytes = logFile.readBytes()
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleGetFailureReport(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val logsRoot = entry.logsRoot
        if (logsRoot.isBlank()) {
            errorResponse(ex, 404, "failure report not found", "NOT_FOUND"); return
        }
        val reportFile = java.io.File(logsRoot, "failure_report.json")
        if (!reportFile.exists()) {
            errorResponse(ex, 404, "failure report not found", "NOT_FOUND"); return
        }
        val bytes = reportFile.readBytes()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ── Import / Export ───────────────────────────────────────────────────────

    private fun handleExportPipeline(ex: HttpExchange, id: String) {
        val entry = registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        val meta = """{"id":${js(entry.id)},"fileName":${js(entry.fileName)},"dotSource":${js(entry.dotSource)},"originalPrompt":${js(entry.originalPrompt)},"familyId":${js(entry.familyId)},"simulate":${entry.options.simulate},"autoApprove":${entry.options.autoApprove}}"""
        val metaBytes = meta.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("pipeline-meta.json"))
            zip.write(metaBytes)
            zip.closeEntry()
        }
        val zipBytes = baos.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/zip")
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"pipeline-${entry.id}.zip\"")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, zipBytes.size.toLong())
        ex.responseBody.use { it.write(zipBytes) }
    }

    private fun handleImportPipeline(ex: HttpExchange) {
        val query = parseQuery(ex)
        val onConflict = query["onConflict"] ?: "skip"
        val bodyBytes = try {
            ex.requestBody.readBytes()
        } catch (e: Exception) {
            errorResponse(ex, 400, "failed to read request body", "BAD_REQUEST"); return
        }
        var metaText: String? = null
        try {
            ZipInputStream(bodyBytes.inputStream()).use { zis ->
                var zipEntry = zis.nextEntry
                while (zipEntry != null) {
                    if (zipEntry.name.trimStart('/') == "pipeline-meta.json") {
                        metaText = zis.readBytes().toString(Charsets.UTF_8)
                    }
                    zis.closeEntry()
                    zipEntry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            errorResponse(ex, 400, "invalid or corrupt zip: ${e.message?.take(120)}", "BAD_REQUEST"); return
        }
        if (metaText == null) {
            errorResponse(ex, 400, "pipeline-meta.json not found in zip", "BAD_REQUEST"); return
        }
        val meta = try {
            requestJson.parseToJsonElement(metaText!!).jsonObject
        } catch (e: Exception) {
            errorResponse(ex, 400, "invalid pipeline-meta.json", "BAD_REQUEST"); return
        }
        val fileName = meta["fileName"]?.jsonPrimitive?.contentOrNull ?: ""
        val dotSource = meta["dotSource"]?.jsonPrimitive?.contentOrNull ?: ""
        if (fileName.isBlank() || dotSource.isBlank()) {
            errorResponse(ex, 400, "missing required field(s) in pipeline-meta.json: fileName, dotSource", "BAD_REQUEST"); return
        }
        val simulate = meta["simulate"]?.jsonPrimitive?.booleanOrNull ?: false
        val autoApprove = meta["autoApprove"]?.jsonPrimitive?.booleanOrNull ?: true
        val originalPrompt = meta["originalPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val importFamilyId = meta["familyId"]?.jsonPrimitive?.contentOrNull ?: ""
        if (onConflict == "skip") {
            val existing = registry.get(importFamilyId) ?: registry.getOrHydrate(importFamilyId, store)
            if (existing != null) {
                jsonResponse(ex, 200, """{"status":"skipped","id":${js(importFamilyId)}}"""); return
            }
        }
        val newId = PipelineRunner.submit(
            dotSource = dotSource,
            fileName = fileName,
            options = RunOptions(simulate = simulate, autoApprove = autoApprove),
            registry = registry,
            store = store,
            originalPrompt = originalPrompt,
            familyId = importFamilyId,
            onUpdate = onUpdate
        )
        jsonResponse(ex, 201, """{"status":"started","id":${js(newId)}}""")
    }

    // ── DOT Operations ────────────────────────────────────────────────────────

    private fun handleRenderDot(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val dotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        try {
            val proc = ProcessBuilder("dot", "-Tsvg")
                .redirectErrorStream(true)
                .start()
            val writer = Thread { proc.outputStream.use { it.write(dotSource.toByteArray()); it.flush() } }
            writer.start()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            writer.join()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                jsonResponse(ex, 200, """{"svg":${js(output)}}""")
            } else {
                jsonResponse(ex, 400, """{"error":${js(output.take(400))},"code":"RENDER_ERROR"}""")
            }
        } catch (e: java.io.IOException) {
            val msg = if (e.message?.contains("error=2") == true || e.message?.contains("No such file") == true)
                "Graphviz not installed. Run: brew install graphviz"
            else e.message ?: "IO error"
            jsonResponse(ex, 400, """{"error":${js(msg)},"code":"RENDER_ERROR"}""")
        }
    }

    private fun handleValidateDot(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val dotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        val graph = try {
            Parser.parse(dotSource)
        } catch (e: Exception) {
            val diag = """[{"severity":"error","message":${js(e.message ?: "parse error")},"nodeId":null}]"""
            jsonResponse(ex, 200, """{"valid":false,"diagnostics":$diag}"""); return
        }
        val diagnostics = Validator.validate(graph)
        val valid = diagnostics.none { it.severity == attractor.lint.Severity.ERROR }
        val sb = StringBuilder("[")
        diagnostics.forEachIndexed { i, d ->
            if (i > 0) sb.append(",")
            sb.append("{\"severity\":${js(d.severity.name.lowercase())},\"message\":${js(d.message)},\"nodeId\":${if (d.nodeId != null) js(d.nodeId) else "null"}}")
        }
        sb.append("]")
        jsonResponse(ex, 200, """{"valid":$valid,"diagnostics":$sb}""")
    }

    private fun handleGenerateDot(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val prompt = body?.get("prompt")?.jsonPrimitive?.contentOrNull ?: ""
        if (prompt.isBlank()) {
            errorResponse(ex, 400, "prompt is required", "BAD_REQUEST"); return
        }
        try {
            val dotSource = dotGenerator.generate(prompt)
            jsonResponse(ex, 200, """{"dotSource":${js(dotSource)}}""")
        } catch (e: Exception) {
            jsonResponse(ex, 500, """{"error":${js(e.message ?: "generation failed")},"code":"GENERATION_ERROR"}""")
        }
    }

    private fun handleGenerateDotStream(ex: HttpExchange) {
        val query = parseQuery(ex)
        val prompt = query["prompt"] ?: ""
        if (prompt.isBlank()) {
            errorResponse(ex, 400, "prompt is required", "BAD_REQUEST"); return
        }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            val dotSource = dotGenerator.generateStream(prompt) { delta ->
                val line = "data: {\"delta\":${js(delta)}}\n\n"
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val done = "data: {\"done\":true,\"dotSource\":${js(dotSource)}}\n\n"
            out.write(done.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            runCatching { out.write("data: {\"error\":${js(e.message ?: "unknown")}}\n\n".toByteArray(Charsets.UTF_8)); out.flush() }
        } finally {
            runCatching { out.close() }
        }
    }

    private fun handleFixDot(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val dotSource = body?.get("dotSource")?.jsonPrimitive?.contentOrNull ?: ""
        val error = body?.get("error")?.jsonPrimitive?.contentOrNull ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        try {
            val fixedDot = dotGenerator.fixStream(dotSource, error) { }
            jsonResponse(ex, 200, """{"dotSource":${js(fixedDot)}}""")
        } catch (e: Exception) {
            jsonResponse(ex, 500, """{"error":${js(e.message ?: "fix failed")},"code":"GENERATION_ERROR"}""")
        }
    }

    private fun handleFixDotStream(ex: HttpExchange) {
        val query = parseQuery(ex)
        val dotSource = query["dotSource"] ?: ""
        val error = query["error"] ?: ""
        if (dotSource.isBlank()) {
            errorResponse(ex, 400, "dotSource is required", "BAD_REQUEST"); return
        }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            val fixedDot = dotGenerator.fixStream(dotSource, error) { delta ->
                val line = "data: {\"delta\":${js(delta)}}\n\n"
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val done = "data: {\"done\":true,\"dotSource\":${js(fixedDot)}}\n\n"
            out.write(done.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            runCatching { out.write("data: {\"error\":${js(e.message ?: "unknown")}}\n\n".toByteArray(Charsets.UTF_8)); out.flush() }
        } finally {
            runCatching { out.close() }
        }
    }

    private fun handleIterateDot(ex: HttpExchange) {
        val body = readJsonBody(ex)
        val baseDot = body?.get("baseDot")?.jsonPrimitive?.contentOrNull ?: ""
        val changes = body?.get("changes")?.jsonPrimitive?.contentOrNull ?: ""
        if (baseDot.isBlank()) { errorResponse(ex, 400, "baseDot is required", "BAD_REQUEST"); return }
        if (changes.isBlank()) { errorResponse(ex, 400, "changes is required", "BAD_REQUEST"); return }
        try {
            val dotSource = dotGenerator.iterateStream(baseDot, changes) { }
            jsonResponse(ex, 200, """{"dotSource":${js(dotSource)}}""")
        } catch (e: Exception) {
            jsonResponse(ex, 500, """{"error":${js(e.message ?: "iterate failed")},"code":"GENERATION_ERROR"}""")
        }
    }

    private fun handleIterateDotStream(ex: HttpExchange) {
        val query = parseQuery(ex)
        val baseDot = query["baseDot"] ?: ""
        val changes = query["changes"] ?: ""
        if (baseDot.isBlank()) { errorResponse(ex, 400, "baseDot is required", "BAD_REQUEST"); return }
        if (changes.isBlank()) { errorResponse(ex, 400, "changes is required", "BAD_REQUEST"); return }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        try {
            val dotSource = dotGenerator.iterateStream(baseDot, changes) { delta ->
                val line = "data: {\"delta\":${js(delta)}}\n\n"
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val done = "data: {\"done\":true,\"dotSource\":${js(dotSource)}}\n\n"
            out.write(done.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            runCatching { out.write("data: {\"error\":${js(e.message ?: "unknown")}}\n\n".toByteArray(Charsets.UTF_8)); out.flush() }
        } finally {
            runCatching { out.close() }
        }
    }

    // ── Settings & Models ─────────────────────────────────────────────────────

    private fun handleGetSettings(ex: HttpExchange) {
        val booleanKeys = setOf(
            "fireworks_enabled",
            "provider_anthropic_enabled",
            "provider_openai_enabled",
            "provider_gemini_enabled"
        )
        val defaults = mapOf(
            "fireworks_enabled" to "true",
            "execution_mode" to "api",
            "provider_anthropic_enabled" to "true",
            "provider_openai_enabled" to "true",
            "provider_gemini_enabled" to "true",
            "cli_anthropic_command" to "claude -p {prompt}",
            "cli_openai_command" to "codex -p {prompt}",
            "cli_gemini_command" to "gemini -p {prompt}"
        )
        val sb = StringBuilder("{")
        KNOWN_SETTINGS.forEachIndexed { i, key ->
            if (i > 0) sb.append(",")
            val value = store.getSetting(key) ?: defaults[key] ?: ""
            if (key in booleanKeys) {
                sb.append("${js(key)}:${value == "true"}")
            } else {
                sb.append("${js(key)}:${js(value)}")
            }
        }
        sb.append("}")
        jsonResponse(ex, 200, sb.toString())
    }

    private val SETTING_DEFAULTS = mapOf(
        "fireworks_enabled" to "true",
        "execution_mode" to "api",
        "provider_anthropic_enabled" to "true",
        "provider_openai_enabled" to "true",
        "provider_gemini_enabled" to "true",
        "cli_anthropic_command" to "claude -p {prompt}",
        "cli_openai_command" to "codex -p {prompt}",
        "cli_gemini_command" to "gemini -p {prompt}"
    )

    private fun handleGetSetting(ex: HttpExchange, key: String) {
        if (key !in KNOWN_SETTINGS) {
            errorResponse(ex, 404, "unknown setting: $key", "NOT_FOUND"); return
        }
        val value = store.getSetting(key) ?: SETTING_DEFAULTS[key]
        if (value == null) {
            errorResponse(ex, 404, "setting not found: $key", "NOT_FOUND"); return
        }
        jsonResponse(ex, 200, """{"key":${js(key)},"value":${js(value)}}""")
    }

    private fun handlePutSetting(ex: HttpExchange, key: String) {
        if (key !in KNOWN_SETTINGS) {
            errorResponse(ex, 400, "unknown setting key: $key", "BAD_REQUEST"); return
        }
        val body = readJsonBody(ex)
        val value = body?.get("value")?.jsonPrimitive?.contentOrNull
            ?: run { errorResponse(ex, 400, "value is required", "BAD_REQUEST"); return }

        // Validate values for constrained settings
        when (key) {
            "execution_mode" -> if (value !in setOf("api", "cli")) {
                errorResponse(ex, 400, "execution_mode must be 'api' or 'cli'", "BAD_REQUEST"); return
            }
            "provider_anthropic_enabled", "provider_openai_enabled", "provider_gemini_enabled" ->
                if (value !in setOf("true", "false")) {
                    errorResponse(ex, 400, "$key must be 'true' or 'false'", "BAD_REQUEST"); return
                }
            "cli_anthropic_command", "cli_openai_command", "cli_gemini_command" ->
                if (value.isBlank()) {
                    errorResponse(ex, 400, "$key must not be blank", "BAD_REQUEST"); return
                }
        }

        store.setSetting(key, value)
        jsonResponse(ex, 200, """{"key":${js(key)},"value":${js(value)}}""")
    }

    private fun handleGetModels(ex: HttpExchange) {
        val models = ModelCatalog.listModels(null)
        val sb = StringBuilder("[")
        models.forEachIndexed { i, m ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":${js(m.id)},")
            sb.append("\"provider\":${js(m.provider)},")
            sb.append("\"displayName\":${js(m.displayName)},")
            sb.append("\"contextWindow\":${m.contextWindow},")
            sb.append("\"supportsTools\":${m.supportsTools},")
            sb.append("\"supportsVision\":${m.supportsVision},")
            sb.append("\"supportsReasoning\":${m.supportsReasoning}")
            sb.append("}")
        }
        sb.append("]")
        jsonResponse(ex, 200, """{"models":$sb}""")
    }

    // ── SSE Events ────────────────────────────────────────────────────────────

    private fun handleEvents(ex: HttpExchange) {
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.sendResponseHeaders(200, 0)
        val client = RestSseClient(ex)
        sseClients.add(client)
        client.offer(getSseSnapshot())
        try {
            while (client.alive) {
                val json = client.queue.poll(2, TimeUnit.SECONDS)
                if (json != null) {
                    val bytes = "data: $json\n\n".toByteArray(Charsets.UTF_8)
                    ex.responseBody.write(bytes)
                    ex.responseBody.flush()
                } else {
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

    private fun handleEventsSingle(ex: HttpExchange, id: String) {
        registry.getOrHydrate(id, store)
            ?: run { errorResponse(ex, 404, "pipeline not found", "NOT_FOUND"); return }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.responseHeaders.add("Connection", "keep-alive")
        ex.responseHeaders.add("X-Accel-Buffering", "no")
        ex.sendResponseHeaders(200, 0)
        val client = RestSseClient(ex)
        sseClients.add(client)
        // Initial snapshot filtered for this pipeline
        val entry = registry.get(id)
        if (entry != null) {
            val snap = """{"pipeline":${pipelineEntryToJson(entry, full = false)}}"""
            client.offer(snap)
        }
        try {
            while (client.alive) {
                val json = client.queue.poll(2, TimeUnit.SECONDS)
                if (json != null) {
                    // Filter: only forward if this pipeline's id appears in the payload
                    if (json.contains(id)) {
                        val bytes = "data: $json\n\n".toByteArray(Charsets.UTF_8)
                        ex.responseBody.write(bytes)
                        ex.responseBody.flush()
                    } else {
                        // send heartbeat
                        val bytes = ": heartbeat\n\n".toByteArray(Charsets.UTF_8)
                        ex.responseBody.write(bytes)
                        ex.responseBody.flush()
                    }
                } else {
                    val bytes = ": heartbeat\n\n".toByteArray(Charsets.UTF_8)
                    ex.responseBody.write(bytes)
                    ex.responseBody.flush()
                }
            }
        } catch (_: Exception) {
            // client disconnected
        } finally {
            client.alive = false
            sseClients.remove(client)
            runCatching { ex.responseBody.close() }
        }
    }

    // ── API Documentation ─────────────────────────────────────────────────────

    private fun handleSpecJson(ex: HttpExchange) {
        val bytes = javaClass.getResourceAsStream("/api/openapi.json")?.readBytes()
            ?: run {
                errorResponse(ex, 404,
                    "openapi.json not found — run `make openapi` then rebuild the app",
                    "NOT_FOUND")
                return
            }
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleSpecYaml(ex: HttpExchange) {
        val bytes = javaClass.getResourceAsStream("/api/openapi.yaml")?.readBytes()
            ?: run {
                errorResponse(ex, 404,
                    "openapi.yaml not found — run `make openapi` then rebuild the app",
                    "NOT_FOUND")
                return
            }
        ex.responseHeaders.add("Content-Type", "application/yaml; charset=utf-8")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun handleSwaggerUi(ex: HttpExchange) {
        val html = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Corey's Attractor — API Docs</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
  <style>body { margin: 0; }</style>
</head>
<body>
<div id="swagger-ui"></div>
<script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
<script>
  SwaggerUIBundle({
    url: '/api/v1/openapi.json',
    dom_id: '#swagger-ui',
    presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
    layout: 'BaseLayout',
    deepLinking: true,
    tryItOutEnabled: true,
  });
</script>
</body>
</html>""".trimIndent()
        val bytes = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
