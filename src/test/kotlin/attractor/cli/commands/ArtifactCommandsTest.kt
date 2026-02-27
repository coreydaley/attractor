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
})
