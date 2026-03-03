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

class RestApiSpecRoutesTest : FunSpec({

    var httpServer: HttpServer? = null
    var port = 0
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("attractor-spec-test-", ".db").toFile()
        val s = SqliteRunStore(tmpDb!!.absolutePath)
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

    test("GET /api/v1/openapi.json returns 200 with application/json content-type") {
        val resp = get("/openapi.json")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "application/json"
    }

    test("GET /api/v1/openapi.yaml returns 200 with yaml content-type") {
        val resp = get("/openapi.yaml")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("").lowercase().let { ct ->
            (ct.contains("yaml") || ct.contains("text/plain")) shouldBe true
        }
    }

    test("GET /api/v1/swagger.json returns 200 with application/json content-type") {
        val resp = get("/swagger.json")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "application/json"
    }

    test("GET /api/v1/docs returns 200 with text/html content-type") {
        val resp = get("/docs")
        resp.statusCode() shouldBe 200
        resp.headers().firstValue("content-type").orElse("") shouldContain "text/html"
    }
})
