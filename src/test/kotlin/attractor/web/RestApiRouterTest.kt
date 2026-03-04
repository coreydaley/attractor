package attractor.web

import attractor.db.SqliteRunStore
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
    var registry: ProjectRegistry? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("attractor-rest-test-", ".db").toFile()
        val s = SqliteRunStore(tmpDb!!.absolutePath)
        val r = ProjectRegistry(s)
        registry = r
        val sseClients = CopyOnWriteArrayList<RestApiRouter.RestSseClient>()
        val router = RestApiRouter(r, s, {}, { """{"projects":[]}""" }, sseClients)
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

    // ── Projects CRUD ────────────────────────────────────────────────────────

    test("GET /api/v1/projects returns 200 empty array on fresh server") {
        val resp = get("/projects")
        resp.statusCode() shouldBe 200
        resp.body() shouldBe "[]"
    }

    test("POST /api/v1/projects with missing dotSource returns 400") {
        val resp = post("/projects", """{"fileName":"test.dot"}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("GET /api/v1/projects/{id} with unknown id returns 404") {
        val resp = get("/projects/does-not-exist")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/projects/{id} with known id returns 200 with required fields") {
        registry!!.register("crud-test-run", "test.dot", "digraph T { start [shape=Mdiamond] }", familyId = "crud-family-1")

        val resp = get("/projects/crud-test-run")
        resp.statusCode() shouldBe 200
        val body = resp.body()
        body shouldContain "\"id\""
        body shouldContain "\"status\""
        body shouldContain "\"archived\""
        body shouldContain "\"hasFailureReport\""
        body shouldContain "\"familyId\""
        body shouldContain "\"dotSource\""   // full=true includes dotSource
    }

    test("GET /api/v1/projects list excludes dotSource") {
        registry!!.register(
            "list-test-run", "list.dot",
            "digraph List { start [shape=Mdiamond] }",
            familyId = "list-family-1"
        )
        val resp = get("/projects")
        resp.statusCode() shouldBe 200
        // list response must NOT include dotSource field
        resp.body() shouldNotContain "\"dotSource\""
    }

    test("PATCH /api/v1/projects/{id} updates dotSource on idle project") {
        registry!!.register(
            "patch-test-run", "patch.dot",
            "digraph Patch { start [shape=Mdiamond] }",
            familyId = "patch-family-1"
        )
        val resp = patch(
            "/projects/patch-test-run",
            """{"dotSource":"digraph Updated { start [shape=Mdiamond] }"}"""
        )
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "Updated"
    }

    test("DELETE /api/v1/projects/{id} on idle project returns 200") {
        registry!!.register(
            "delete-test-run", "delete.dot",
            "digraph D { start [shape=Mdiamond] }",
            familyId = "delete-family-1"
        )
        val resp = delete("/projects/delete-test-run")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"deleted\":true"
    }

    test("DELETE /api/v1/projects/{id} on unknown id returns 404") {
        val resp = delete("/projects/nonexistent-delete")
        resp.statusCode() shouldBe 404
    }

    // ── Project Lifecycle ────────────────────────────────────────────────────

    test("POST /api/v1/projects/{id}/pause on non-running project returns 409") {
        registry!!.register(
            "pause-test-run", "pause.dot",
            "digraph P { start [shape=Mdiamond] }",
            familyId = "pause-family-1"
        )
        val resp = post("/projects/pause-test-run/pause", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/projects/{id}/resume on non-paused project returns 409") {
        registry!!.register(
            "resume-test-run", "resume.dot",
            "digraph R { start [shape=Mdiamond] }",
            familyId = "resume-family-1"
        )
        val resp = post("/projects/resume-test-run/resume", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/projects/{id}/cancel on non-running project returns 409") {
        registry!!.register(
            "cancel-test-run", "cancel.dot",
            "digraph C { start [shape=Mdiamond] }",
            familyId = "cancel-family-1"
        )
        val resp = post("/projects/cancel-test-run/cancel", "")
        resp.statusCode() shouldBe 409
        resp.body() shouldContain "INVALID_STATE"
    }

    test("POST /api/v1/projects/{id}/archive then unarchive toggles archived flag") {
        registry!!.register(
            "archive-test-run", "archive.dot",
            "digraph A { start [shape=Mdiamond] }",
            familyId = "archive-family-1"
        )
        val archiveResp = post("/projects/archive-test-run/archive", "")
        archiveResp.statusCode() shouldBe 200
        archiveResp.body() shouldContain "\"archived\":true"

        val unarchiveResp = post("/projects/archive-test-run/unarchive", "")
        unarchiveResp.statusCode() shouldBe 200
        unarchiveResp.body() shouldContain "\"unarchived\":true"
    }

    test("POST /api/v1/projects/{id}/iterations with missing dotSource returns 400") {
        registry!!.register(
            "iter-test-run", "iter.dot",
            "digraph I { start [shape=Mdiamond] }",
            familyId = "iter-family-1"
        )
        val resp = post("/projects/iter-test-run/iterations", """{}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("GET /api/v1/projects/{id}/family returns familyId and members array") {
        registry!!.register(
            "family-test-run", "family.dot",
            "digraph F { start [shape=Mdiamond] }",
            familyId = "family-test-id"
        )
        val resp = get("/projects/family-test-run/family")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"familyId\""
        resp.body() shouldContain "\"members\""
    }

    // ── Artifacts & Reports ───────────────────────────────────────────────────

    test("GET /api/v1/projects/{id}/failure-report with no logsRoot returns 404") {
        registry!!.register(
            "fr-test-run", "fr.dot",
            "digraph Fr { start [shape=Mdiamond] }",
            familyId = "fr-family-1"
        )
        val resp = get("/projects/fr-test-run/failure-report")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/projects/{id}/artifacts returns empty list with no logsRoot") {
        registry!!.register(
            "arts-test-run", "arts.dot",
            "digraph Arts { start [shape=Mdiamond] }",
            familyId = "arts-family-1"
        )
        val resp = get("/projects/arts-test-run/artifacts")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"files\":[]"
    }

    test("GET /api/v1/projects/{id}/artifacts/{path} with path traversal returns 404") {
        registry!!.register(
            "trav-test-run", "trav.dot",
            "digraph Trav { start [shape=Mdiamond] }",
            familyId = "trav-family-1"
        )
        // Set a logsRoot so we get past the "blank logsRoot" check
        val tmpLogsDir = Files.createTempDirectory("attractor-logs-").toFile()
        try {
            registry!!.setLogsRoot("trav-test-run", tmpLogsDir.absolutePath)
            val resp = get("/projects/trav-test-run/artifacts/../../etc/passwd")
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
        val resp = get("/projects/no-such-id")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "\"error\":"
        resp.body() shouldContain "\"code\":"
    }

    // ── Project Gap Coverage ──────────────────────────────────────────────────

    test("POST /api/v1/projects with valid dotSource returns 201 with id and status") {
        val dot = "digraph G { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }"
        val resp = post("/projects", """{"dotSource":${RestApiRouter.js(dot)},"fileName":"test.dot"}""")
        resp.statusCode() shouldBe 201
        resp.body() shouldContain "\"id\""
        resp.body() shouldContain "\"status\""
    }

    test("GET /api/v1/projects/{id}/stages returns 200 with stages array") {
        registry!!.register("stages-test-run", "stages.dot", "digraph S { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "stages-family-1")
        val resp = get("/projects/stages-test-run/stages")
        resp.statusCode() shouldBe 200
        resp.body().trimStart() shouldContain "["
    }

    // ── Git endpoint ─────────────────────────────────────────────────────────

    test("GET /api/v1/projects/{id}/git returns 404 for unknown id") {
        val resp = get("/projects/nonexistent-git-project/git")
        resp.statusCode() shouldBe 404
    }

    test("GET /api/v1/projects/{id}/git returns 200 with application/json for known id") {
        registry!!.register("git-test-run", "git.dot", "digraph G { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "git-family-1")
        val resp = get("/projects/git-test-run/git")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "application/json"
    }

    test("GET /api/v1/projects/{id}/git response body contains 'available' key") {
        registry!!.register("git-test-run-2", "git2.dot", "digraph G2 { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "git-family-2")
        val resp = get("/projects/git-test-run-2/git")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"available\""
    }

    test("GET /api/v1/projects/{id}/git response body contains 'commitCount' key") {
        registry!!.register("git-test-run-3", "git3.dot", "digraph G3 { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "git-family-3")
        val resp = get("/projects/git-test-run-3/git")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"commitCount\""
    }

    test("GET /api/v1/projects/{id}/git response body contains 'recent' key") {
        registry!!.register("git-test-run-4", "git4.dot", "digraph G4 { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "git-family-4")
        val resp = get("/projects/git-test-run-4/git")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"recent\""
    }

    test("POST /api/v1/projects/{id}/rerun on idle project returns 200") {
        registry!!.register("rerun-test-run", "rerun.dot", "digraph Re { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "rerun-family-1")
        val resp = post("/projects/rerun-test-run/rerun", "")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "\"status\":\"running\""
    }

    test("GET /api/v1/projects/{id}/dot returns 200 with text/plain and dotSource") {
        val dotContent = "digraph Dot { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }"
        registry!!.register("dot-dl-test-run", "dot-dl.dot", dotContent, familyId = "dot-dl-family-1")
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/projects/dot-dl-test-run/dot"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "text/plain"
        resp.body() shouldContain "digraph"
    }

    test("POST /api/v1/projects/dot with raw DOT body returns 201") {
        val dot = "digraph Upload { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }"
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/projects/dot"))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(dot)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 201
        resp.body() shouldContain "\"id\""
    }

    test("GET /api/v1/projects/{id}/artifacts.zip with no logsRoot returns 404") {
        registry!!.register("zip-test-run", "zip.dot", "digraph Z { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "zip-family-1")
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/projects/zip-test-run/artifacts.zip"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        resp.statusCode() shouldBe 404
    }

    test("GET /api/v1/projects/{id}/stages/{nodeId}/log with no logsRoot returns 404") {
        registry!!.register("log-test-run", "log.dot", "digraph L { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "log-family-1")
        val resp = get("/projects/log-test-run/stages/start/log")
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/projects/{id}/export returns 200 with application/zip") {
        registry!!.register("export-test-run", "export.dot", "digraph E { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "export-family-1")
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/projects/export-test-run/export"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "application/zip"
    }

    test("POST /api/v1/projects/import with empty body returns 400") {
        val resp = post("/projects/import", "")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }
})
