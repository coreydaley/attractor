package attractor.cli.commands

import attractor.cli.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.file.Files

class DotCommandsTest : FunSpec({

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

    fun cmdFor(port: Int) = DotCommands(CliContext("http://localhost:$port"))

    test("dot generate --prompt POSTs to /api/v1/dot/generate and prints dotSource") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"dotSource":"digraph G { a -> b }"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("generate", "--prompt", "test pipeline")) }
            output shouldContain "digraph G"
        } finally { srv.stop(0) }
    }

    test("dot generate without --prompt throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("generate")) }
        ex.exitCode shouldBe 2
    }

    test("dot validate --file prints valid for valid DOT") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"valid":true,"diagnostics":[]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("validate", "--file", tmpFile.absolutePath)) }
            output shouldContain "valid"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot validate --file prints invalid with diagnostics for invalid DOT") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"valid":false,"diagnostics":[{"severity":"error","message":"bad syntax","nodeId":null}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test", ".dot").toFile()
        try {
            tmpFile.writeText("bad dot")
            val output = captureStdout { cmdFor(port).dispatch(listOf("validate", "--file", tmpFile.absolutePath)) }
            output shouldContain "invalid"
            output shouldContain "bad syntax"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot generate-stream --prompt reads SSE stream and prints deltas") {
        val (srv, port) = startFakeServer { ex ->
            val sse = "data: {\"delta\":\"digraph\"}\n\ndata: {\"delta\":\" G\"}\n\ndata: {\"done\":true,\"dotSource\":\"digraph G {}\"}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("generate-stream", "--prompt", "test")) }
            output shouldContain "digraph"
        } finally { srv.stop(0) }
    }

    test("dot render --file POSTs to /api/v1/dot/render and writes SVG to file") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"svg":"<svg><circle/></svg>"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test", ".dot").toFile()
        val tmpDir = Files.createTempDirectory("render-test").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val outFile = java.io.File(tmpDir, "output.svg")
            val output = captureStdout { cmdFor(port).dispatch(listOf("render", "--file", tmpFile.absolutePath, "--output", outFile.absolutePath)) }
            outFile.exists() shouldBe true
            outFile.readText() shouldContain "<svg>"
            output shouldContain "Saved"
        } finally {
            srv.stop(0)
            tmpFile.delete()
            tmpDir.deleteRecursively()
        }
    }

    test("dot render without --file throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("render")) }
        ex.exitCode shouldBe 2
    }

    test("dot fix --file POSTs to /api/v1/dot/fix and prints dotSource") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"dotSource":"digraph Fixed { a -> b }"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("fix-test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph Broken { }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("fix", "--file", tmpFile.absolutePath)) }
            output shouldContain "digraph Fixed"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot fix-stream --file reads SSE stream from /api/v1/dot/fix/stream and prints deltas") {
        val (srv, port) = startFakeServer { ex ->
            val sse = "data: {\"delta\":\"digraph\"}\n\ndata: {\"done\":true,\"dotSource\":\"digraph Fixed {}\"}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        val tmpFile = Files.createTempFile("fix-stream-test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("fix-stream", "--file", tmpFile.absolutePath)) }
            output shouldContain "digraph"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot iterate --file --changes POSTs to /api/v1/dot/iterate and prints dotSource") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"dotSource":"digraph Updated { a -> b -> c }"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("iterate-test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("iterate", "--file", tmpFile.absolutePath, "--changes", "add node c")) }
            output shouldContain "digraph Updated"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot iterate-stream --file --changes reads SSE stream and prints deltas") {
        val (srv, port) = startFakeServer { ex ->
            val sse = "data: {\"delta\":\"digraph\"}\n\ndata: {\"done\":true,\"dotSource\":\"digraph Updated {}\"}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.write(sse)
        }
        val tmpFile = Files.createTempFile("iterate-stream-test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("iterate-stream", "--file", tmpFile.absolutePath, "--changes", "add node c")) }
            output shouldContain "digraph"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("dot iterate without --changes throws CliException exit code 2") {
        val tmpFile = Files.createTempFile("iterate-no-changes", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("iterate", "--file", tmpFile.absolutePath)) }
            ex.exitCode shouldBe 2
        } finally { tmpFile.delete() }
    }
})
