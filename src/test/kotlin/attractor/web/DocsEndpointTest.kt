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

class DocsEndpointTest : FunSpec({

    var server: WebMonitorServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()

    beforeSpec {
        tmpDb = Files.createTempFile("docs-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb!!.absolutePath)
        server = WebMonitorServer(0, ProjectRegistry(store!!), store!!)
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

    // ── Route contract ────────────────────────────────────────────────────────

    test("GET /docs returns 404 (endpoint removed, docs moved to external site)") {
        get("/docs").statusCode() shouldBe 404
    }

    test("GET /docs/ trailing slash returns 404") {
        get("/docs/").statusCode() shouldBe 404
    }

    test("GET /docs/anything sub-path returns 404") {
        get("/docs/anything").statusCode() shouldBe 404
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    test("GET / root SPA still returns 200") {
        get("/").statusCode() shouldBe 200
    }

    test("GET / root page Docs nav button opens external docs site") {
        get("/").body() shouldContain "coreydaley.github.io/attractor"
    }
})
