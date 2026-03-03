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

class RestApiSseTest : FunSpec({

    var httpServer: HttpServer? = null
    var port = 0
    var registry: PipelineRegistry? = null
    var tmpDb: java.io.File? = null

    beforeSpec {
        tmpDb = Files.createTempFile("attractor-sse-test-", ".db").toFile()
        val s = SqliteRunStore(tmpDb!!.absolutePath)
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

    test("GET /api/v1/events returns 200 with text/event-stream content type") {
        val url = URI.create("http://localhost:$port/api/v1/events").toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        try {
            conn.connect()
            conn.responseCode shouldBe 200
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            contentType shouldContain "text/event-stream"
        } finally {
            conn.disconnect()
        }
    }

    test("GET /api/v1/events delivers initial snapshot event on connect") {
        val url = URI.create("http://localhost:$port/api/v1/events").toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        try {
            conn.connect()
            val stream = conn.inputStream
            // Read enough bytes to capture the first SSE event (data: ... \n\n)
            val buffer = StringBuilder()
            val bytes = ByteArray(512)
            val read = stream.read(bytes)
            if (read > 0) buffer.append(String(bytes, 0, read, Charsets.UTF_8))
            // The initial snapshot should appear as "data: ..." in the stream
            buffer.toString() shouldContain "data:"
        } finally {
            conn.disconnect()
        }
    }

    test("GET /api/v1/events client disconnect does not crash server") {
        val url = URI.create("http://localhost:$port/api/v1/events").toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        conn.connect()
        // Read the first byte then disconnect immediately
        runCatching { conn.inputStream.read() }
        conn.disconnect()

        // Server should still be responsive after abrupt client disconnect
        val checkUrl = URI.create("http://localhost:$port/api/v1/pipelines").toURL()
        val checkConn = checkUrl.openConnection() as java.net.HttpURLConnection
        checkConn.connectTimeout = 2000
        checkConn.readTimeout = 2000
        try {
            checkConn.responseCode shouldBe 200
        } finally {
            checkConn.disconnect()
        }
    }

    test("GET /api/v1/events/{id} for unknown pipeline id returns 404") {
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/v1/events/no-such-pipeline-id"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 404
        resp.body() shouldContain "NOT_FOUND"
    }

    test("GET /api/v1/events/{id} for known pipeline id returns 200 with text/event-stream") {
        registry!!.register(
            "sse-pipeline-1", "sse.dot",
            "digraph S { start [shape=Mdiamond] }",
            familyId = "sse-family-1"
        )
        val url = URI.create("http://localhost:$port/api/v1/events/sse-pipeline-1").toURL()
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
        try {
            conn.connect()
            conn.responseCode shouldBe 200
            val contentType = conn.getHeaderField("Content-Type") ?: ""
            contentType shouldContain "text/event-stream"
        } finally {
            conn.disconnect()
        }
    }
})
