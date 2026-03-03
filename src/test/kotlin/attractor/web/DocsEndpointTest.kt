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

class DocsEndpointTest : FunSpec({

    var server: WebMonitorServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("docs-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb!!.absolutePath)
        server = WebMonitorServer(0, PipelineRegistry(store!!), store!!)
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

    // ── Route contract ────────────────────────────────────────────────────────

    test("GET /docs returns 200") {
        get("/docs").statusCode() shouldBe 200
    }

    test("GET /docs/ trailing slash returns 200") {
        get("/docs/").statusCode() shouldBe 200
    }

    test("GET /docs/anything sub-path returns 404") {
        get("/docs/anything").statusCode() shouldBe 404
    }

    test("GET /docs has text/html content-type") {
        val resp = get("/docs")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "text/html"
    }

    // ── Page structure ────────────────────────────────────────────────────────

    test("GET /docs body contains title") {
        get("/docs").body() shouldContain "<title>Attractor Docs</title>"
    }

    test("GET /docs body contains Web App tab label") {
        get("/docs").body() shouldContain "Web App"
    }

    test("GET /docs body contains REST API tab label") {
        get("/docs").body() shouldContain "REST API"
    }

    test("GET /docs body contains CLI tab label") {
        get("/docs").body() shouldContain "CLI"
    }

    test("GET /docs body contains DOT Format tab label") {
        get("/docs").body() shouldContain "DOT Format"
    }

    // ── Content completeness markers ──────────────────────────────────────────

    test("GET /docs REST API section contains /api/v1/pipelines") {
        get("/docs").body() shouldContain "/api/v1/pipelines"
    }

    test("GET /docs REST API section contains POST /api/v1/dot/validate") {
        get("/docs").body() shouldContain "POST /api/v1/dot/validate"
    }

    test("GET /docs CLI section contains attractor pipeline list") {
        get("/docs").body() shouldContain "attractor pipeline list"
    }

    test("GET /docs CLI section contains attractor dot generate") {
        get("/docs").body() shouldContain "attractor dot generate"
    }

    test("GET /docs DOT Format section contains shape=Mdiamond") {
        get("/docs").body() shouldContain "shape=Mdiamond"
    }

    test("GET /docs artifact download section contains workspace/ directory description") {
        get("/docs").body() shouldContain "workspace/"
    }

    test("GET /docs artifact download section contains live.log description") {
        get("/docs").body() shouldContain "live.log"
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    test("GET / root SPA still returns 200") {
        get("/").statusCode() shouldBe 200
    }

    test("GET / root page contains window.open for Docs nav button") {
        get("/").body() shouldContain "window.open"
    }
})
