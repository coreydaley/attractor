package attractor.web

import attractor.db.SqliteRunStore
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class RestApiLlmTest : FunSpec({

    var httpServer: HttpServer? = null
    var port = 0
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("attractor-llm-test-", ".db").toFile()
        val s = SqliteRunStore(tmpDb!!.absolutePath)
        // Configure mock LLM: CLI mode with `echo` as the command
        s.setSetting("execution_mode", "cli")
        s.setSetting("provider_anthropic_enabled", "true")
        s.setSetting("cli_anthropic_command", "echo")
        val r = PipelineRegistry(s)
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

    // ── Parameter validation (no LLM needed) ────────────────────────────────

    test("POST /api/v1/dot/generate with missing prompt returns 400") {
        val resp = post("/dot/generate", """{}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("POST /api/v1/dot/render with missing dotSource returns 400") {
        val resp = post("/dot/render", """{}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("POST /api/v1/dot/fix with missing dotSource returns 400") {
        val resp = post("/dot/fix", """{}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("POST /api/v1/dot/iterate with missing baseDot returns 400") {
        val resp = post("/dot/iterate", """{"changes":"add a node"}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    // ── Happy path with mock LLM (echo command) ──────────────────────────────

    test("POST /api/v1/dot/generate with prompt returns 200 with dotSource field") {
        val resp = post("/dot/generate", """{"prompt":"simple pipeline"}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "dotSource"
    }

    test("POST /api/v1/dot/fix with dotSource returns 200 with dotSource field") {
        val resp = post("/dot/fix", """{"dotSource":"digraph G { a -> b }"}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "dotSource"
    }

    test("POST /api/v1/dot/iterate with baseDot and changes returns 200 with dotSource field") {
        val resp = post("/dot/iterate", """{"baseDot":"digraph G { a -> b }","changes":"add node c"}""")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "dotSource"
    }

    test("GET /api/v1/dot/generate/stream with prompt returns 200 with text/event-stream") {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/dot/generate/stream?prompt=test"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "text/event-stream"
    }
})
