package attractor.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress

class MainTest : FunSpec({

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

    test("--help prints global usage with all resource groups") {
        val output = captureStdout { run(listOf("--help")) }
        output shouldContain "pipeline"
        output shouldContain "artifact"
        output shouldContain "dot"
        output shouldContain "settings"
        output shouldContain "models"
        output shouldContain "events"
        output shouldContain "--host"
        output shouldContain "--output"
    }

    test("no args prints help without error") {
        // Should not throw
        captureStdout { run(emptyList()) }
    }

    test("unknown resource throws CliException with exit code 2") {
        val ex = shouldThrow<CliException> { run(listOf("notaresource")) }
        ex.exitCode shouldBe 2
        ex.message!! shouldContain "notaresource"
    }

    test("--host overrides base URL") {
        val (srv, port) = startFakeServer { ex ->
            val body = "[]".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            // Should use the custom host, not the default
            captureStdout { run(listOf("--host", "http://localhost:$port", "pipeline", "list")) }
        } finally { srv.stop(0) }
    }

    test("--output json passes JSON format to commands") {
        val (srv, port) = startFakeServer { ex ->
            val body = "[]".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout {
                run(listOf("--host", "http://localhost:$port", "--output", "json", "pipeline", "list"))
            }
            output.trim() shouldBe "[]"
        } finally { srv.stop(0) }
    }

    test("--output with invalid value throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { run(listOf("--output", "xml")) }
        ex.exitCode shouldBe 2
    }

    test("--version exits 0 and produces some output") {
        val output = captureStdout { run(listOf("--version")) }
        // Version may be "unknown" in test context (no JAR manifest), but should not throw
        output shouldContain "attractor-cli"
    }
})
