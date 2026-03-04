package attractor.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress

class ApiClientTest : FunSpec({

    fun startFakeServer(handler: (HttpExchange) -> Unit): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            try { handler(ex) } finally { ex.close() }
        }
        server.start()
        return server to server.address.port
    }

    fun clientFor(port: Int) = ApiClient(CliContext(baseUrl = "http://localhost:$port"))

    test("GET returns response body on 200") {
        val (srv, port) = startFakeServer { ex ->
            val body = """[{"id":"run-1"}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val result = clientFor(port).get("/api/v1/projects")
            result shouldBe """[{"id":"run-1"}]"""
        } finally { srv.stop(0) }
    }

    test("GET on 404 with JSON error throws CliException with API message") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"error":"project not found","code":"NOT_FOUND"}""".toByteArray()
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val ex = shouldThrow<CliException> { clientFor(port).get("/api/v1/projects/bad") }
            ex.message shouldBe "project not found"
            ex.exitCode shouldBe 1
        } finally { srv.stop(0) }
    }

    test("connection refused throws CliException with cannot connect message") {
        val client = ApiClient(CliContext(baseUrl = "http://localhost:19999"))
        val ex = shouldThrow<CliException> { client.get("/api/v1/projects") }
        ex.message!! shouldContain "Cannot connect to"
        ex.exitCode shouldBe 1
    }

    test("getBinary returns byte array from server response") {
        val expected = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // ZIP magic bytes
        val (srv, port) = startFakeServer { ex ->
            ex.sendResponseHeaders(200, expected.size.toLong())
            ex.responseBody.write(expected)
        }
        try {
            val bytes = clientFor(port).getBinary("/api/v1/projects/x/artifacts.zip")
            bytes shouldBe expected
        } finally { srv.stop(0) }
    }

    test("postBinary sends raw bytes as request body") {
        val sentBytes = "test zip content".toByteArray()
        var receivedBytes: ByteArray? = null
        val (srv, port) = startFakeServer { ex ->
            receivedBytes = ex.requestBody.readBytes()
            val resp = """{"status":"started","id":"run-1"}""".toByteArray()
            ex.sendResponseHeaders(201, resp.size.toLong())
            ex.responseBody.write(resp)
        }
        try {
            clientFor(port).postBinary("/api/v1/projects/import", sentBytes)
            receivedBytes shouldBe sentBytes
        } finally { srv.stop(0) }
    }

    test("getStream yields SSE data lines") {
        val (srv, port) = startFakeServer { ex ->
            val sse = "data: {\"delta\":\"hello\"}\n\ndata: {\"done\":true}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        try {
            val results = clientFor(port).getStream("/api/v1/dot/generate/stream").toList()
            results shouldBe listOf("""{"delta":"hello"}""", """{"done":true}""")
        } finally { srv.stop(0) }
    }
})
