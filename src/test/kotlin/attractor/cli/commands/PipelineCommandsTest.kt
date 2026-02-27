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

class PipelineCommandsTest : FunSpec({

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

    fun cmdFor(port: Int, format: OutputFormat = OutputFormat.TEXT) =
        PipelineCommands(CliContext("http://localhost:$port", format))

    test("pipeline list makes GET to /api/v1/pipelines and prints table headers") {
        val (srv, port) = startFakeServer { ex ->
            val body = """[{"id":"run-1","displayName":"Falcon","status":"completed","startedAt":1700000000000}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("list")) }
            output shouldContain "ID"
            output shouldContain "NAME"
            output shouldContain "STATUS"
            output shouldContain "STARTED"
            output shouldContain "run-1"
        } finally { srv.stop(0) }
    }

    test("pipeline get makes GET to /api/v1/pipelines/{id} and prints key-value pairs") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"id":"run-1","displayName":"Falcon","status":"completed","archived":false}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("get", "run-1")) }
            output shouldContain "id:"
            output shouldContain "run-1"
        } finally { srv.stop(0) }
    }

    test("pipeline create --file reads file and POSTs to /api/v1/pipelines") {
        var requestBody: String? = null
        val (srv, port) = startFakeServer { ex ->
            requestBody = ex.requestBody.bufferedReader().readText()
            val body = """{"id":"run-new","status":"running"}""".toByteArray()
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test-pipeline", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            val output = captureStdout { cmdFor(port).dispatch(listOf("create", "--file", tmpFile.absolutePath)) }
            output shouldContain "run-new"
            requestBody!! shouldContain "digraph G"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("pipeline create without --file throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("create")) }
        ex.exitCode shouldBe 2
    }

    test("pipeline update PATCHes correct endpoint") {
        var method: String? = null
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            method = ex.requestMethod
            path = ex.requestURI.path
            val body = """{"id":"run-1","status":"completed"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test-pipeline", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            cmdFor(port).dispatch(listOf("update", "run-1", "--file", tmpFile.absolutePath))
            method shouldBe "PATCH"
            path shouldBe "/api/v1/pipelines/run-1"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("pipeline pause POSTs to /api/v1/pipelines/{id}/pause") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"paused":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            cmdFor(port).dispatch(listOf("pause", "run-1"))
            path shouldBe "/api/v1/pipelines/run-1/pause"
        } finally { srv.stop(0) }
    }

    test("pipeline stages prints table with correct headers") {
        val (srv, port) = startFakeServer { ex ->
            val body = """[{"index":0,"nodeId":"start","status":"completed","durationMs":100,"error":null}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("stages", "run-1")) }
            output shouldContain "NODE"
            output shouldContain "STATUS"
            output shouldContain "DURATION"
            output shouldContain "start"
        } finally { srv.stop(0) }
    }

    test("pipeline watch exits 0 when status becomes completed") {
        var callCount = 0
        val (srv, port) = startFakeServer { ex ->
            callCount++
            val status = if (callCount >= 2) "completed" else "running"
            val body = """{"id":"run-1","status":"$status","currentNode":null}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            // Should not throw; exits when completed
            captureStdout { cmdFor(port).dispatch(listOf("watch", "run-1", "--interval-ms", "10")) }
        } finally { srv.stop(0) }
    }

    test("pipeline watch throws CliException exit code 1 when status becomes failed") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"id":"run-1","status":"failed","currentNode":null}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val ex = shouldThrow<CliException> {
                captureStdout { cmdFor(port).dispatch(listOf("watch", "run-1", "--interval-ms", "10")) }
            }
            ex.exitCode shouldBe 1
        } finally { srv.stop(0) }
    }

    test("unknown pipeline verb throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("notaverb")) }
        ex.exitCode shouldBe 2
    }

    test("pipeline family prints table with VER, ID, NAME, STATUS, CREATED") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"familyId":"fam-1","members":[{"id":"run-1","displayName":"Falcon","status":"completed","versionNum":1,"createdAt":1700000000000,"fileName":"x.dot","originalPrompt":"test"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("family", "run-1")) }
            output shouldContain "VER"
            output shouldContain "STATUS"
            output shouldContain "Falcon"
        } finally { srv.stop(0) }
    }
})
