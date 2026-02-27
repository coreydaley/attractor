package attractor.cli.commands

import attractor.cli.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress

class SettingsCommandsTest : FunSpec({

    fun captureStdout(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(baos))
        try { block() } finally { System.setOut(old) }
        return baos.toString()
    }

    fun startFakeServer(handler: (HttpExchange) -> Unit): Pair<HttpServer, Int> {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            try { handler(ex) } finally { ex.close() }
        }
        server.start()
        return server to server.address.port
    }

    fun cmdFor(port: Int) = SettingsCommands(CliContext("http://localhost:$port"))

    test("settings list GETs /api/v1/settings and prints table") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"fireworks_enabled":"true"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("list")) }
            output shouldContain "KEY"
            output shouldContain "VALUE"
            output shouldContain "fireworks_enabled"
            output shouldContain "true"
        } finally { srv.stop(0) }
    }

    test("settings get prints key: value") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"key":"fireworks_enabled","value":"false"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("get", "fireworks_enabled")) }
            output shouldContain "fireworks_enabled: false"
        } finally { srv.stop(0) }
    }

    test("settings set PUTs to /api/v1/settings/{key} with correct JSON body") {
        var requestBody: String? = null
        var path: String? = null
        var method: String? = null
        val (srv, port) = startFakeServer { ex ->
            method = ex.requestMethod
            path = ex.requestURI.path
            requestBody = ex.requestBody.bufferedReader().readText()
            val body = """{"key":"fireworks_enabled","value":"false"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            captureStdout { cmdFor(port).dispatch(listOf("set", "fireworks_enabled", "false")) }
            method shouldBe "PUT"
            path shouldBe "/api/v1/settings/fireworks_enabled"
            requestBody!! shouldContain """"value":"false""""
        } finally { srv.stop(0) }
    }
})
