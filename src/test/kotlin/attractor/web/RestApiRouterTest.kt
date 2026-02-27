package attractor.web

import attractor.db.RunStore
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class RestApiRouterTest : FunSpec({

    var httpServer: HttpServer? = null
    var port = 0
    var registry: PipelineRegistry? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("attractor-rest-test-", ".db").toFile()
        val s = RunStore(tmpDb!!.absolutePath)
        val r = PipelineRegistry(s)
        registry = r
        val sseClients = CopyOnWriteArrayList<RestApiRouter.RestSseClient>()
        val router = RestApiRouter(r, s, {}, { """{"pipelines":[]}""" }, sseClients)
        val srv = HttpServer.create(InetSocketAddress(0), 0)
        srv.executor = Executors.newCachedThreadPool()
        srv.createContext("/api/v1/") { ex -> router.handle(ex) }
        srv.start()
        httpServer = srv
        port = srv.address.port
    }

    afterSpec {
        httpServer?.stop(0)
        tmpDb?.delete()
    }

    fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun post(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun put(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun delete(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .DELETE().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun patch(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ── Pipelines CRUD ────────────────────────────────────────────────────────

    test("GET /api/v1/pipelines returns 200 empty array on fresh server") {
        val resp = get("/pipelines")
        resp.statusCode() shouldBe 200
        resp.body() shouldBe "[]"
    }

    test("POST /api/v1/pipelines with missing dotSource returns 400") {
        val resp = post("/pipelines", """{"fileName":"test.dot"}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("GET /api/v1/pipelines/{id} with unknown id returns 404") {
        val resp = get("/pipelines/does-not-exist")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/pipelines/{id} with known id returns 200 with required fields") {
        registry!!.register("crud-test-run", "test.dot", "digraph T { start [shape=Mdiamond] }", familyId = "crud-family-1")

        val resp = get("/pipelines/crud-test-run")
        resp.statusCode() shouldBe 200
        val body = resp.body()
        body shouldContain "\"id\""
        body shouldContain "\"status\""
        body shouldContain "\"archived\""
        body shouldContain "\"hasFailureReport\""
        body shouldContain "\"familyId\""
        body shouldContain "\"dotSource\""   // full=true includes dotSource
    }

    test("GET /api/v1/pipelines list excludes dotSource") {
        registry!!.register(
            "list-test-run", "list.dot",
            "digraph List { start [shape=Mdiamond] }",
            familyId = "list-family-1"
        )
        val resp = get("/pipelines")
        resp.statusCode() shouldBe 200
        // list response must NOT include dotSource field
        resp.body() shouldNotContain "\"dotSource\""
    }

    test("PATCH /api/v1/pipelines/{id} updates dotSource on idle pipeline") {
        registry!!.register(
            "patch-test-run", "patch.dot",
            "digraph Patch { start [shape=Mdiamond] }",
            familyId = "patch-family-1"
        )
        val resp = patch(
            "/pipelines/patch-test-run",
            """{"dotSource":"digraph Updated { start [shape=Mdiamond] }"}"""
        )
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "Updated"
    }

    test("DELETE /api/v1/pipelines/{id} on idle pipeline returns 200") {
        registry!!.register(
            "delete-test-run", "delete.dot",
            "digraph D { start [shape=Mdiamond] }",
            familyId = "delete-family-1"
        )
        val resp = delete("/pipelines/delete-test-run")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"deleted\":true"
    }

    test("DELETE /api/v1/pipelines/{id} on unknown id returns 404") {
        val resp = delete("/pipelines/nonexistent-delete")
        resp.statusCode() shouldBe 404
    }

    // ── Pipeline Lifecycle ────────────────────────────────────────────────────

    test("POST /api/v1/pipelines/{id}/pause on non-running pipeline returns 409") {
        registry!!.register(
            "pause-test-run", "pause.dot",
            "digraph P { start [shape=Mdiamond] }",
            familyId = "pause-family-1"
        )
        val resp = post("/pipelines/pause-test-run/pause", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/pipelines/{id}/resume on non-paused pipeline returns 409") {
        registry!!.register(
            "resume-test-run", "resume.dot",
            "digraph R { start [shape=Mdiamond] }",
            familyId = "resume-family-1"
        )
        val resp = post("/pipelines/resume-test-run/resume", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/pipelines/{id}/cancel on non-running pipeline returns 409") {
        registry!!.register(
            "cancel-test-run", "cancel.dot",
            "digraph C { start [shape=Mdiamond] }",
            familyId = "cancel-family-1"
        )
        val resp = post("/pipelines/cancel-test-run/cancel", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/pipelines/{id}/archive then unarchive toggles archived flag") {
        registry!!.register(
            "archive-test-run", "archive.dot",
            "digraph A { start [shape=Mdiamond] }",
            familyId = "archive-family-1"
        )
        val archiveResp = post("/pipelines/archive-test-run/archive", "")
        archiveResp.statusCode() shouldBe 200
        archiveResp.body() shouldContain "\"archived\":true"

        val unarchiveResp = post("/pipelines/archive-test-run/unarchive", "")
        unarchiveResp.statusCode() shouldBe 200
        unarchiveResp.body() shouldContain "\"unarchived\":true"
    }

    test("POST /api/v1/pipelines/{id}/iterations with missing dotSource returns 400") {
        registry!!.register(
            "iter-test-run", "iter.dot",
            "digraph I { start [shape=Mdiamond] }",
            familyId = "iter-family-1"
        )
        val resp = post("/pipelines/iter-test-run/iterations", """{}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("GET /api/v1/pipelines/{id}/family returns familyId and members array") {
        registry!!.register(
            "family-test-run", "family.dot",
            "digraph F { start [shape=Mdiamond] }",
            familyId = "family-test-id"
        )
        val resp = get("/pipelines/family-test-run/family")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"familyId\""
        resp.body() shouldContain "\"members\""
    }

    // ── Artifacts & Reports ───────────────────────────────────────────────────

    test("GET /api/v1/pipelines/{id}/failure-report with no logsRoot returns 404") {
        registry!!.register(
            "fr-test-run", "fr.dot",
            "digraph Fr { start [shape=Mdiamond] }",
            familyId = "fr-family-1"
        )
        val resp = get("/pipelines/fr-test-run/failure-report")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/pipelines/{id}/artifacts returns empty list with no logsRoot") {
        registry!!.register(
            "arts-test-run", "arts.dot",
            "digraph Arts { start [shape=Mdiamond] }",
            familyId = "arts-family-1"
        )
        val resp = get("/pipelines/arts-test-run/artifacts")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"files\":[]"
    }

    test("GET /api/v1/pipelines/{id}/artifacts/{path} with path traversal returns 404") {
        registry!!.register(
            "trav-test-run", "trav.dot",
            "digraph Trav { start [shape=Mdiamond] }",
            familyId = "trav-family-1"
        )
        // Set a logsRoot so we get past the "blank logsRoot" check
        val tmpLogsDir = Files.createTempDirectory("attractor-logs-").toFile()
        try {
            registry!!.setLogsRoot("trav-test-run", tmpLogsDir.absolutePath)
            val resp = get("/pipelines/trav-test-run/artifacts/../../etc/passwd")
            resp.statusCode() shouldBe 404
            resp.body() shouldContain "NOT_FOUND"
        } finally {
            tmpLogsDir.deleteRecursively()
        }
    }

    // ── DOT Tooling ───────────────────────────────────────────────────────────

    test("POST /api/v1/dot/validate with valid DOT returns 200 valid=true") {
        val dot = """digraph Valid {
            |  start [shape=Mdiamond, label="Start"]
            |  exit  [shape=Msquare,  label="Exit"]
            |  start -> exit
            |}""".trimMargin()
        val resp = post("/dot/validate", """{"dotSource":${RestApiRouter.js(dot)}}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"valid\":true"
        resp.body() shouldContain "\"diagnostics\":"
    }

    test("POST /api/v1/dot/validate with blank dotSource returns 400") {
        val resp = post("/dot/validate", """{"dotSource":""}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("POST /api/v1/dot/validate with unparseable DOT returns 200 valid=false") {
        val resp = post("/dot/validate", """{"dotSource":"this is not dot at all!!!"}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"valid\":false"
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    test("GET /api/v1/settings returns 200 with fireworks_enabled key") {
        val resp = get("/settings")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "fireworks_enabled"
    }

    test("PUT /api/v1/settings/fireworks_enabled updates setting and returns 200") {
        val resp = put("/settings/fireworks_enabled", """{"value":"false"}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"key\":\"fireworks_enabled\""
        resp.body() shouldContain "\"value\":\"false\""
    }

    test("GET /api/v1/settings/fireworks_enabled after PUT returns 200") {
        // Ensure the setting exists first
        put("/settings/fireworks_enabled", """{"value":"true"}""")
        val resp = get("/settings/fireworks_enabled")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"key\":\"fireworks_enabled\""
    }

    test("GET /api/v1/settings/unknown_key returns 404") {
        val resp = get("/settings/not_a_real_setting")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("PUT /api/v1/settings/unknown_key returns 400") {
        val resp = put("/settings/not_a_real_setting", """{"value":"foo"}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    // ── Models ────────────────────────────────────────────────────────────────

    test("GET /api/v1/models returns 200 with non-empty models array") {
        val resp = get("/models")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"models\":"
        // ModelCatalog should have at least one model
        resp.body() shouldContain "\"id\":"
        resp.body() shouldContain "\"provider\":"
    }

    // ── Error Handling ────────────────────────────────────────────────────────

    test("unknown route returns 404 with NOT_FOUND code") {
        val resp = get("/this/does/not/exist")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("error responses use uniform envelope with error and code fields") {
        val resp = get("/pipelines/no-such-id")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "\"error\":"
        resp.body() shouldContain "\"code\":"
    }
})
