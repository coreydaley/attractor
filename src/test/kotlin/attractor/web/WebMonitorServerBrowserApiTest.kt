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

class WebMonitorServerBrowserApiTest : FunSpec({

    var server: WebMonitorServer? = null
    var port = 0
    var store: RunStore? = null
    var tmpDb: java.io.File? = null
    val client = HttpClient.newHttpClient()
    val testRunId = "browser-api-test-run"

    beforeSpec {
        tmpDb = Files.createTempFile("browser-api-test-", ".db").toFile()
        store = SqliteRunStore(tmpDb!!.absolutePath)
        val reg = ProjectRegistry(store!!)
        // Pre-register a project for happy-path tests
        reg.register(testRunId, "test.dot", "digraph T { start [shape=Mdiamond] exit [shape=Msquare] start -> exit }", familyId = "browser-api-family-1")
        server = WebMonitorServer(0, reg, store!!)
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

    fun post(path: String, body: String = ""): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ── Read-only routes (GET) ────────────────────────────────────────────────

    test("GET /api/projects returns 200 with JSON array") {
        val resp = get("/api/projects")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/project-view?id=known returns 200") {
        val resp = get("/api/project-view?id=$testRunId")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/project-view without id returns 400") {
        val resp = get("/api/project-view")
        resp.statusCode() shouldBe 400
    }

    test("GET /api/project-family?id=known returns 200") {
        val resp = get("/api/project-family?id=$testRunId")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/run-artifacts?id=known returns 200 with files array") {
        val resp = get("/api/run-artifacts?id=$testRunId")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "files"
    }

    test("GET /api/stage-log with missing params returns 200 with missing-param message") {
        val resp = get("/api/stage-log")
        resp.statusCode() shouldBe 200
        resp.body() shouldContain "missing"
    }

    test("GET /api/settings returns 200") {
        val resp = get("/api/settings")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/settings/cli-status returns 200") {
        val resp = get("/api/settings/cli-status")
        resp.statusCode() shouldBe 200
    }

    // ── Method rejection (405) for routes with explicit guards ────────────────

    test("GET /api/run (POST-only) returns 405") {
        val resp = get("/api/run")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/cancel (POST-only) returns 405") {
        val resp = get("/api/cancel")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/pause (POST-only) returns 405") {
        val resp = get("/api/pause")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/resume (POST-only) returns 405") {
        val resp = get("/api/resume")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/archive (POST-only) returns 405") {
        val resp = get("/api/archive")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/unarchive (POST-only) returns 405") {
        val resp = get("/api/unarchive")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/delete (POST-only) returns 405") {
        val resp = get("/api/delete")
        resp.statusCode() shouldBe 405
    }

    test("GET /api/project-view (GET-only) with wrong method returns 405") {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$port/api/project-view?id=$testRunId"))
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.statusCode() shouldBe 405
    }

    // ── Regression guard ──────────────────────────────────────────────────────

    test("GET / returns 200 (SPA still works)") {
        val resp = get("/")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/v1/projects returns 200 (REST API still works)") {
        val resp = get("/api/v1/projects")
        resp.statusCode() shouldBe 200
    }

    test("GET /docs returns 200 (docs still works)") {
        val resp = get("/docs")
        resp.statusCode() shouldBe 200
    }

    // ── Sprint 016: Closeable tabs markup presence ────────────────────────────

    test("GET / body contains closeTab JS function (closeable tabs)") {
        val resp = get("/")
        resp.body() shouldContain "closeTab"
    }

    test("GET / body contains tab-close CSS class (closeable tabs)") {
        val resp = get("/")
        resp.body() shouldContain "tab-close"
    }

    test("GET / body contains attractor-closed-tabs localStorage key (closeable tabs)") {
        val resp = get("/")
        resp.body() shouldContain "attractor-closed-tabs"
    }

    test("GET / body contains saveClosedTabs helper function (closeable tabs)") {
        val resp = get("/")
        resp.body() shouldContain "saveClosedTabs"
    }

    // ── Sprint 017: Dashboard layout toggle markup presence ───────────────────

    test("GET / body contains attractor-dashboard-layout localStorage key (layout toggle)") {
        val resp = get("/")
        resp.body() shouldContain "attractor-dashboard-layout"
    }

    test("GET / body contains setDashLayout JS function (layout toggle)") {
        val resp = get("/")
        resp.body() shouldContain "setDashLayout"
    }

    test("GET / body contains dash-layout-toggle CSS class (layout toggle)") {
        val resp = get("/")
        resp.body() shouldContain "dash-layout-toggle"
    }

    test("GET / body contains dashboard-list CSS class (list layout)") {
        val resp = get("/")
        resp.body() shouldContain "dashboard-list"
    }

    test("GET / body contains buildDashList JS function (list layout)") {
        val resp = get("/")
        resp.body() shouldContain "buildDashList"
    }

    // ── Sprint 018: Completion state clarity markup presence ──────────────────

    test("GET / body contains effectiveDone variable (stageCountStr fix)") {
        val resp = get("/")
        resp.body() shouldContain "effectiveDone"
    }

    test("GET / body contains completedPrefix variable (checkmark prefix)") {
        val resp = get("/")
        resp.body() shouldContain "completedPrefix"
    }

    test("GET / body contains dash-card-flash CSS class (completion flash)") {
        val resp = get("/")
        resp.body() shouldContain "dash-card-flash"
    }

    test("GET / body contains flashDashCard JS function (completion flash)") {
        val resp = get("/")
        resp.body() shouldContain "flashDashCard"
    }

    test("GET / body contains prevStatuses variable (completion transition tracking)") {
        val resp = get("/")
        resp.body() shouldContain "prevStatuses"
    }

    // ── Sprint 019: Pipeline → Project rename ─────────────────────────────────

    test("GET /api/v1/projects returns 200 (project rename)") {
        val resp = get("/api/v1/projects")
        resp.statusCode() shouldBe 200
    }

    test("GET /api/v1/pipelines returns 404 (old path hard cut)") {
        val resp = get("/api/v1/pipelines")
        resp.statusCode() shouldBe 404
    }

    // ── Sprint 021: Git History Panel ────────────────────────────────────────

    test("GET / body contains loadGitInfo JS function") {
        val resp = get("/")
        resp.body() shouldContain "loadGitInfo"
    }

    test("GET / body contains renderGitBar JS function") {
        val resp = get("/")
        resp.body() shouldContain "renderGitBar"
    }

    test("GET / body contains toggleGitCommit JS function") {
        val resp = get("/")
        resp.body() shouldContain "toggleGitCommit"
    }

    test("GET / body contains git-commit-list CSS class") {
        val resp = get("/")
        resp.body() shouldContain "git-commit-list"
    }

    test("GET / body contains gitPanel DOM id") {
        val resp = get("/")
        resp.body() shouldContain "gitPanel"
    }

    // ── Sprint 022: Project page two-tab layout ───────────────────────────────

    test("GET / body contains innerTabBtnRuns DOM id") {
        val resp = get("/")
        resp.body() shouldContain "innerTabBtnRuns"
    }

    test("GET / body contains innerTabBtnDetails DOM id") {
        val resp = get("/")
        resp.body() shouldContain "innerTabBtnDetails"
    }

    test("GET / body contains selectInnerTab JS function") {
        val resp = get("/")
        resp.body() shouldContain "selectInnerTab"
    }

    test("GET / body contains renderDetailsTab JS function") {
        val resp = get("/")
        resp.body() shouldContain "renderDetailsTab"
    }

    test("GET / body contains detailsMetaTable DOM id") {
        val resp = get("/")
        resp.body() shouldContain "detailsMetaTable"
    }

    test("GET / body contains attractor-project-inner-tab localStorage key") {
        val resp = get("/")
        resp.body() shouldContain "attractor-project-inner-tab"
    }

    test("GET / body contains inner-tab-btn CSS class") {
        val resp = get("/")
        resp.body() shouldContain "inner-tab-btn"
    }

    test("GET / body contains details-mode CSS class") {
        val resp = get("/")
        resp.body() shouldContain "details-mode"
    }

    test("GET /api/projects response body contains logsRoot key") {
        val resp = get("/api/projects")
        resp.body() shouldContain "logsRoot"
    }
})
