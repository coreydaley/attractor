package attractor.cli.commands

import attractor.cli.*
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress

class EventsCommandTest : FunSpec({

    fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }

    fun startFakeServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            try { handler(ex) } finally { ex.close() }
        }
        server.start()
        return server to server.address.port
    }

    fun cmdFor(port: Int) = EventsCommand(CliContext("http://localhost:$port"))

    test("events (no args) GETs /api/v1/events and prints SSE data lines") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val sse = "data: {\"type\":\"update\",\"id\":\"run-1\"}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(emptyList()) }
            path shouldContain "/api/v1/events"
            output shouldContain "update"
        } finally { srv.stop(0) }
    }

    test("events with project id GETs /api/v1/events/{id} and prints SSE data lines") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val sse = "data: {\"type\":\"stage\",\"id\":\"run-1\"}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("run-1")) }
            path shouldBe "/api/v1/events/run-1"
            output shouldContain "stage"
        } finally { srv.stop(0) }
    }
})
