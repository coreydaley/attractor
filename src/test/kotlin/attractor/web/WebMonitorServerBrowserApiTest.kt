package attractor.web

import attractor.db.RunStore
import attractor.db.SqliteRunStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class WebMonitorServerBrowserApiTest : FunSpec({

    var server: WebMonitorServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    var registry: PipelineRegistry? = null
    val client = HttpClient.newHttpClient()
    val testRunId = "browser-api-test-run"

    beforeSpec {
        tmpDb = Files.createTempFile("browser-api-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb!!.absolutePath)
        val reg = PipelineRegistry(store!!)
        registry = reg
        // Pre-register a pipeline for happy-path tests
        reg.register(testRunId, "test.dot", "digraph T { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "browser-api-family-1")
        server = WebMonitorServer(0, reg, store!!)
        server!!.start()
        port = server!!.port
    }

    afterSpec {
        server?.stop()
        store?.close()
        tmpDb?.delete()
    }

    fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun post(path: String, body: String = ""): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ── Read-only routes (GET) ────────────────────────────────────────────────

    test("GET /api/pipelines returns 200 with JSON array") {
        val resp = get("/api/pipelines")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/pipeline-view?id=known returns 200") {
        val resp = get("/api/pipeline-view?id=$testRunId")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/pipeline-view without id returns 400") {
        val resp = get("/api/pipeline-view")
        resp.statusCode() shouldBe 400
    }

    test("GET /api/pipeline-family?id=known returns 200") {
        val resp = get("/api/pipeline-family?id=$testRunId")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/run-artifacts?id=known returns 200 with files array") {
        val resp = get("/api/run-artifacts?id=$testRunId")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "files"
    }

    test("GET /api/stage-log with missing params returns 200 with missing-param message") {
        val resp = get("/api/stage-log")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "missing"
    }

    test("GET /api/settings returns 200") {
        val resp = get("/api/settings")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/settings/cli-status returns 200") {
        val resp = get("/api/settings/cli-status")
        resp.statusCode() shouldBe 200
    }

    // ── Method rejection (405) for routes with explicit guards ────────────────

    test("GET /api/run (POST-only) returns 405") {
        val resp = get("/api/run")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/cancel (POST-only) returns 405") {
        val resp = get("/api/cancel")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/pause (POST-only) returns 405") {
        val resp = get("/api/pause")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/resume (POST-only) returns 405") {
        val resp = get("/api/resume")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/archive (POST-only) returns 405") {
        val resp = get("/api/archive")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/unarchive (POST-only) returns 405") {
        val resp = get("/api/unarchive")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/delete (POST-only) returns 405") {
        val resp = get("/api/delete")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/pipeline-view (GET-only) with wrong method returns 405") {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/pipeline-view?id=$testRunId"))
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 405
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    test("GET / returns 200 (SPA still works)") {
        val resp = get("/")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/v1/pipelines returns 200 (REST API still works)") {
        val resp = get("/api/v1/pipelines")
        resp.statusCode() shouldBe 200
    }

    test("GET /docs returns 200 (docs still works)") {
        val resp = get("/docs")
        resp.statusCode() shouldBe 200
    }
})
