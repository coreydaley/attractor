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
import java.nio.file.Files

class ArtifactCommandsTest : FunSpec({

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

    fun cmdFor(port: Int) = ArtifactCommands(CliContext("http://localhost:$port"))

    test("artifact list GETs artifacts endpoint and prints table") {
        val (srv, port) = startFakeServer { ex ->
            val body = """{"files":[{"path":"live.log","size":1234,"isText":true}],"truncated":false}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("list", "run-1")) }
            output shouldContain "PATH"
            output shouldContain "SIZE"
            output shouldContain "live.log"
        } finally { srv.stop(0) }
    }

    test("artifact download-zip writes bytes to derived filename") {
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val (srv, port) = startFakeServer { ex ->
            ex.sendResponseHeaders(200, zipBytes.size.toLong())
            ex.responseBody.write(zipBytes)
        }
        val tmpDir = Files.createTempDirectory("artifact-test").toFile()
        try {
            val oldDir = System.getProperty("user.dir")
            System.setProperty("user.dir", tmpDir.absolutePath)
            try {
                captureStdout { cmdFor(port).dispatch(listOf("download-zip", "run-1", "--output", "${tmpDir.absolutePath}/artifacts-run-1.zip")) }
            } finally {
                System.setProperty("user.dir", oldDir)
            }
            val outFile = java.io.File("${tmpDir.absolutePath}/artifacts-run-1.zip")
            outFile.readBytes() shouldBe zipBytes
        } finally {
            srv.stop(0)
            tmpDir.deleteRecursively()
        }
    }

    test("artifact import posts binary data with correct Content-Type") {
        var contentType: String? = null
        var receivedBytes: ByteArray? = null
        val (srv, port) = startFakeServer { ex ->
            contentType = ex.requestHeaders.getFirst("Content-Type")
            receivedBytes = ex.requestBody.readBytes()
            val body = """{"status":"started","id":"run-new"}""".toByteArray()
            ex.sendResponseHeaders(201, body.size.toLong())
            ex.responseBody.write(body)
        }
        val tmpFile = Files.createTempFile("pipeline", ".zip").toFile()
        try {
            val zipData = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
            tmpFile.writeBytes(zipData)
            captureStdout { cmdFor(port).dispatch(listOf("import", tmpFile.absolutePath)) }
            contentType shouldContain "application/zip"
            receivedBytes shouldBe zipData
        } finally {
            srv.stop(0)
            tmpFile.delete()
        }
    }

    test("artifact get makes GET to /api/v1/pipelines/{id}/artifacts/{path} and prints content") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = "log content here".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("get", "run-1", "live.log")) }
            path shouldBe "/api/v1/pipelines/run-1/artifacts/live.log"
            output shouldContain "log content"
        } finally { srv.stop(0) }
    }

    test("artifact stage-log makes GET to /api/v1/pipelines/{id}/stages/{nodeId}/log and prints content") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = "stage output".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("stage-log", "run-1", "writeTests")) }
            path shouldBe "/api/v1/pipelines/run-1/stages/writeTests/log"
            output shouldContain "stage output"
        } finally { srv.stop(0) }
    }

    test("artifact failure-report makes GET to /api/v1/pipelines/{id}/failure-report and prints JSON") {
        var path: String? = null
        val (srv, port) = startFakeServer { ex ->
            path = ex.requestURI.path
            val body = """{"diagnosed":true,"summary":"error summary"}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        try {
            val output = captureStdout { cmdFor(port).dispatch(listOf("failure-report", "run-1")) }
            path shouldBe "/api/v1/pipelines/run-1/failure-report"
            output shouldContain "diagnosed"
        } finally { srv.stop(0) }
    }

    test("artifact export writes ZIP bytes to file and prints confirmation") {
        val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)
        val (srv, port) = startFakeServer { ex ->
            ex.sendResponseHeaders(200, zipBytes.size.toLong())
            ex.responseBody.write(zipBytes)
        }
        val tmpDir = Files.createTempDirectory("export-test").toFile()
        try {
            val outFile = java.io.File(tmpDir, "pipeline-run-1.zip")
            val output = captureStdout { cmdFor(port).dispatch(listOf("export", "run-1", "--output", outFile.absolutePath)) }
            outFile.exists() shouldBe true
            outFile.readBytes() shouldBe zipBytes
            output shouldContain "Saved"
        } finally {
            srv.stop(0)
            tmpDir.deleteRecursively()
        }
    }
})
