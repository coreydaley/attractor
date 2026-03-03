package attractor.web

import attractor.db.RunStore
import attractor.db.SqliteRunStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

class CreateDotUploadTest : FunSpec({

    var server: WebMonitorServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("dot-upload-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb!!.absolutePath)
        server = WebMonitorServer(0, PipelineRegistry(store!!), store!!)
        server!!.start()
        port = server!!.port
    }

    afterSpec {
        server?.stop()
        store?.close()
        tmpDb?.delete()
    }

    fun get(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .GET().build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ── Markup presence ───────────────────────────────────────────────────────

    test("GET / returns 200") {
        get("/").statusCode() shouldBe 200
    }

    test("GET / contains dotFileInput element") {
        get("/").body() shouldContain """id="dotFileInput""""
    }

    test("GET / contains accept=.dot on file input") {
        get("/").body() shouldContain """accept=".dot""""
    }

    test("GET / contains onDotFileSelected handler") {
        get("/").body() shouldContain "onDotFileSelected()"
    }

    test("GET / contains upload .dot link label") {
        get("/").body() shouldContain "upload an existing .dot file"
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    test("GET /api/v1/pipelines still returns 200") {
        get("/api/v1/pipelines").statusCode() shouldBe 200
    }

    test("GET /docs still returns 200") {
        get("/docs").statusCode() shouldBe 200
    }
})
