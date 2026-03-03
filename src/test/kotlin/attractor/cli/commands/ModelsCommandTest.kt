package attractor.cli.commands

import attractor.cli.*
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress

class ModelsCommandTest : FunSpec({

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

    fun cmdFor(port: Int, format: OutputFormat = OutputFormat.TEXT) =
        ModelsCommand(CliContext("http://localhost:$port", format))

    val modelsJson = """{"models":[{"id":"claude-sonnet-4-6","provider":"anthropic","displayName":"Claude Sonnet 4.6","contextWindow":200000,"supportsTools":true,"supportsVision":true}]}"""

    test("models list makes GET to /api/v1/models and prints table headers") {
        val (srv, port) = startFakeServer { ex ->
            val body = modelsJson.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("list")) }
            output shouldContain "ID"
            output shouldContain "PROVIDER"
            output shouldContain "NAME"
            output shouldContain "CONTEXT"
            output shouldContain "TOOLS"
            output shouldContain "VISION"
            output shouldContain "claude-sonnet-4-6"
        } finally { srv.stop(0) }
    }

    test("models list --output json prints raw JSON") {
        val (srv, port) = startFakeServer { ex ->
            val body = modelsJson.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port, OutputFormat.JSON).dispatch(listOf("list")) }
            output shouldContain "claude-sonnet-4-6"
            output shouldContain "anthropic"
        } finally { srv.stop(0) }
    }

    test("models with no args defaults to list behavior") {
        val (srv, port) = startFakeServer { ex ->
            val body = modelsJson.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(emptyList()) }
            output shouldContain "ID"
        } finally { srv.stop(0) }
    }

    test("models unknown verb throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("notaverb")) }
        ex.exitCode shouldBe 2
    }

    test("models --help prints help without error") {
        val output = captureStdout { cmdFor(9999).dispatch(listOf("--help")) }
        output shouldContain "models"
    }
})
