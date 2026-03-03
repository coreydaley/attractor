package attractor.web

import attractor.db.RunStore
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

class SettingsEndpointsTest : FunSpec({

    var httpServer: HttpServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("settings-test-", ".db").toFile()
        val s = SqliteRunStore(tmpDb!!.absolutePath)
        store = s
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
        store?.close()
        tmpDb?.delete()
    }

    fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    fun put(path: String, body: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1$path"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ── GET /api/v1/settings ──────────────────────────────────────────────────

    test("GET /api/v1/settings returns 200 with all expected keys") {
        val resp = get("/settings")
        resp.statusCode() shouldBe 200
        val body = resp.body()
        body shouldContain "fireworks_enabled"
        body shouldContain "execution_mode"
        body shouldContain "provider_anthropic_enabled"
        body shouldContain "provider_openai_enabled"
        body shouldContain "provider_gemini_enabled"
        body shouldContain "cli_anthropic_command"
        body shouldContain "cli_openai_command"
        body shouldContain "cli_gemini_command"
    }

    test("GET /api/v1/settings returns boolean for provider toggles") {
        val resp = get("/settings")
        resp.statusCode() shouldBe 200
        val body = resp.body()
        // booleans should appear without quotes
        body shouldContain "\"provider_anthropic_enabled\":"
        // should not be quoted string "true" but bare true
        body shouldNotContain "\"provider_anthropic_enabled\":\"true\""
    }

    // ── GET /api/v1/settings/{key} ────────────────────────────────────────────

    test("GET /api/v1/settings/execution_mode returns 200") {
        val resp = get("/settings/execution_mode")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/v1/settings/unknown_key returns 404") {
        val resp = get("/settings/totally_unknown_key_xyz")
        resp.statusCode() shouldBe 404
    }

    // ── PUT /api/v1/settings/{key} ────────────────────────────────────────────

    test("PUT /api/v1/settings/execution_mode with api succeeds") {
        val resp = put("/settings/execution_mode", """{"value":"api"}""")
        resp.statusCode() shouldBe 200
        store!!.getSetting("execution_mode") shouldBe "api"
    }

    test("PUT /api/v1/settings/execution_mode with cli succeeds") {
        val resp = put("/settings/execution_mode", """{"value":"cli"}""")
        resp.statusCode() shouldBe 200
        store!!.getSetting("execution_mode") shouldBe "cli"
    }

    test("PUT /api/v1/settings/execution_mode with invalid value returns 400") {
        val resp = put("/settings/execution_mode", """{"value":"invalid"}""")
        resp.statusCode() shouldBe 400
        resp.body() shouldContain "BAD_REQUEST"
    }

    test("PUT /api/v1/settings/provider_anthropic_enabled with true succeeds") {
        val resp = put("/settings/provider_anthropic_enabled", """{"value":"true"}""")
        resp.statusCode() shouldBe 200
        store!!.getSetting("provider_anthropic_enabled") shouldBe "true"
    }

    test("PUT /api/v1/settings/provider_openai_enabled with false succeeds") {
        val resp = put("/settings/provider_openai_enabled", """{"value":"false"}""")
        resp.statusCode() shouldBe 200
        store!!.getSetting("provider_openai_enabled") shouldBe "false"
    }

    test("PUT /api/v1/settings/provider_gemini_enabled with invalid value returns 400") {
        val resp = put("/settings/provider_gemini_enabled", """{"value":"yes"}""")
        resp.statusCode() shouldBe 400
    }

    test("PUT /api/v1/settings/cli_anthropic_command with valid template succeeds") {
        val resp = put("/settings/cli_anthropic_command", """{"value":"my-claude --prompt {prompt}"}""")
        resp.statusCode() shouldBe 200
        store!!.getSetting("cli_anthropic_command") shouldBe "my-claude --prompt {prompt}"
    }

    test("PUT /api/v1/settings/cli_openai_command with blank value returns 400") {
        val resp = put("/settings/cli_openai_command", """{"value":""}""")
        resp.statusCode() shouldBe 400
    }

    test("PUT /api/v1/settings/unknown_key returns 400") {
        val resp = put("/settings/unknown_key_xyz", """{"value":"something"}""")
        resp.statusCode() shouldBe 400
    }
})
