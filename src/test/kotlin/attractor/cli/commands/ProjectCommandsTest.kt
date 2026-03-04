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

class ProjectCommandsTest : FunSpec({

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
        ProjectCommands(CliContext("http://localhost:$port", format))

    test("project list makes GET to /api/v1/projects and prints table headers") {
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

    test("project get makes GET to /api/v1/projects/{id} and prints key-value pairs") {
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

    test("project create --file reads file and POSTs to /api/v1/projects") {
        var requestBody: String? = null
        val (srv, port) = startFakeServer { ex ->
            requestBody = ex.requestBody.bufferedReader().readText()
            val body = """{"id":"run-new","status":"running"}""".toByteArray()
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test-project", ".dot").toFile()
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

    test("project create without --file throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("create")) }
        ex.exitCode shouldBe 2
    }

    test("project update PATCHes correct endpoint") {
        var method: String? = null
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            method = ex.requestMethod
            path = ex.requestURI.path
            val body = """{"id":"run-1","status":"completed"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("test-project", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            cmdFor(port).dispatch(listOf("update", "run-1", "--file", tmpFile.absolutePath))
            method shouldBe "PATCH"
            path shouldBe "/api/v1/projects/run-1"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("project pause POSTs to /api/v1/projects/{id}/pause") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"paused":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            cmdFor(port).dispatch(listOf("pause", "run-1"))
            path shouldBe "/api/v1/projects/run-1/pause"
        } finally { srv.stop(0) }
    }

    test("project stages prints table with correct headers") {
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

    test("project watch exits 0 when status becomes completed") {
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

    test("project watch throws CliException exit code 1 when status becomes failed") {
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

    test("unknown project verb throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("notaverb")) }
        ex.exitCode shouldBe 2
    }

    test("project family prints table with VER, ID, NAME, STATUS, CREATED") {
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

    test("project delete sends DELETE to /api/v1/projects/{id}") {
        var method: String? = null
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            method = ex.requestMethod
            path = ex.requestURI.path
            val body = """{"deleted":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("delete", "run-1")) }
            method shouldBe "DELETE"
            path shouldBe "/api/v1/projects/run-1"
            output shouldContain "true"
        } finally { srv.stop(0) }
    }

    test("project rerun POSTs to /api/v1/projects/{id}/rerun") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"id":"run-1","status":"running"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            cmdFor(port).dispatch(listOf("rerun", "run-1"))
            path shouldBe "/api/v1/projects/run-1/rerun"
        } finally { srv.stop(0) }
    }

    test("project resume POSTs to /api/v1/projects/{id}/resume") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"id":"run-1","status":"running"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            cmdFor(port).dispatch(listOf("resume", "run-1"))
            path shouldBe "/api/v1/projects/run-1/resume"
        } finally { srv.stop(0) }
    }

    test("project cancel POSTs to /api/v1/projects/{id}/cancel and prints cancelled") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"cancelled":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("cancel", "run-1")) }
            path shouldBe "/api/v1/projects/run-1/cancel"
            output shouldContain "cancelled"
        } finally { srv.stop(0) }
    }

    test("project archive POSTs to /api/v1/projects/{id}/archive and prints archived") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"archived":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("archive", "run-1")) }
            path shouldBe "/api/v1/projects/run-1/archive"
            output shouldContain "archived"
        } finally { srv.stop(0) }
    }

    test("project unarchive POSTs to /api/v1/projects/{id}/unarchive and prints unarchived") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"unarchived":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("unarchive", "run-1")) }
            path shouldBe "/api/v1/projects/run-1/unarchive"
            output shouldContain "unarchived"
        } finally { srv.stop(0) }
    }

    test("project iterate --file POSTs to /api/v1/projects/{id}/iterations with dotSource") {
        var requestBody: String? = null
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            requestBody = ex.requestBody.bufferedReader().readText()
            val body = """{"id":"run-new","status":"running","familyId":"fam-1"}""".toByteArray()
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("iterate-test", ".dot").toFile()
        try {
            tmpFile.writeText("digraph G { a -> b }")
            captureStdout { cmdFor(port).dispatch(listOf("iterate", "run-1", "--file", tmpFile.absolutePath)) }
            path shouldBe "/api/v1/projects/run-1/iterations"
            requestBody!! shouldContain "digraph G"
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("project iterate without --file throws CliException exit code 2") {
        val ex = shouldThrow<CliException> { cmdFor(9999).dispatch(listOf("iterate", "run-1")) }
        ex.exitCode shouldBe 2
    }
})
