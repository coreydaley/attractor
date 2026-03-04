package attractor.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.sql.DriverManager

class SqliteRunStoreTest : RunStoreContractTest() {
    private val tmpFile = Files.createTempFile("attractor-test-", ".db").toFile()

    override fun createStore(): RunStore = SqliteRunStore(tmpFile.absolutePath)

    init {
        afterSpec {
            tmpFile.delete()
        }
    }
}

/**
 * Tests that the SqliteRunStore migration correctly renames the legacy
 * pipeline_runs table and its columns to project_runs on startup.
 */
class SqliteRunStoreMigrationTest : FunSpec({

    test("migration: legacy pipeline_runs schema is renamed to project_runs on store init") {
        val tmpFile = Files.createTempFile("attractor-migration-test-", ".db").toFile()
        try {
            // Create a legacy DB with old pipeline_runs schema
            val conn = DriverManager.getConnection("jdbc:sqlite:${tmpFile.absolutePath}")
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS pipeline_runs (
                    id TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL DEFAULT '',
                    dot_source TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'running',
                    logs_root TEXT NOT NULL DEFAULT '',
                    simulate INTEGER NOT NULL DEFAULT 0,
                    auto_approve INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    pipeline_log TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0,
                    original_prompt TEXT NOT NULL DEFAULT '',
                    finished_at INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT NOT NULL DEFAULT '',
                    pipeline_family_id TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            conn.createStatement().execute(
                "INSERT INTO pipeline_runs (id, file_name, dot_source, status, created_at, pipeline_log, pipeline_family_id) VALUES ('test-id', 'test.dot', 'digraph G {}', 'completed', 1234567890, 'log line 1', 'fam-1')"
            )
            conn.close()

            // Open SqliteRunStore — should trigger migration
            val store = SqliteRunStore(tmpFile.absolutePath)

            // Verify data is accessible via new schema
            val run = store.getById("test-id")
            run shouldNotBe null
            run!!.id shouldBe "test-id"
            run.projectLog shouldBe "log line 1"
            run.familyId shouldBe "fam-1"

            // Verify new table name is used
            val byFamily = store.getByFamilyId("fam-1")
            byFamily.size shouldBe 1

            store.close()
        } finally {
            tmpFile.delete()
        }
    }

    test("migration: fresh DB creates project_runs directly (no legacy table)") {
        val tmpFile = Files.createTempFile("attractor-fresh-test-", ".db").toFile()
        try {
            val store = SqliteRunStore(tmpFile.absolutePath)
            store.insert("fresh-id", "fresh.dot", "digraph G {}", false, true, "", "Fresh Project", "fresh-fam")
            val run = store.getById("fresh-id")
            run shouldNotBe null
            run!!.id shouldBe "fresh-id"
            run.projectLog shouldBe ""
            store.close()
        } finally {
            tmpFile.delete()
        }
    }

    test("migration: already-migrated DB is a no-op (idempotent)") {
        val tmpFile = Files.createTempFile("attractor-idempotent-test-", ".db").toFile()
        try {
            // Open store once to create project_runs schema
            val store1 = SqliteRunStore(tmpFile.absolutePath)
            store1.insert("run-1", "test.dot", "digraph G {}", false, true)
            store1.close()

            // Open store again — migration is a no-op (project_runs already exists)
            val store2 = SqliteRunStore(tmpFile.absolutePath)
            val run = store2.getById("run-1")
            run shouldNotBe null
            store2.close()
        } finally {
            tmpFile.delete()
        }
    }

    test("logs_root migration: renames logs/ prefix to workspace/") {
        val tmpFile = Files.createTempFile("attractor-logsroot-migrate-test-", ".db").toFile()
        try {
            // Create DB with a legacy logs/ prefix in logs_root
            val conn = DriverManager.getConnection("jdbc:sqlite:${tmpFile.absolutePath}")
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS project_runs (
                    id TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL DEFAULT '',
                    dot_source TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'running',
                    logs_root TEXT NOT NULL DEFAULT '',
                    simulate INTEGER NOT NULL DEFAULT 0,
                    auto_approve INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    project_log TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0,
                    original_prompt TEXT NOT NULL DEFAULT '',
                    finished_at INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT NOT NULL DEFAULT '',
                    project_family_id TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            conn.createStatement().execute(
                "INSERT INTO project_runs (id, file_name, dot_source, status, logs_root, created_at, project_family_id) VALUES ('lr-test-1', 'test.dot', 'digraph G {}', 'completed', 'logs/my-project', 1000, 'fam-lr')"
            )
            conn.close()

            // Open SqliteRunStore — should trigger migration
            val store = SqliteRunStore(tmpFile.absolutePath)
            val run = store.getById("lr-test-1")
            run shouldNotBe null
            run!!.logsRoot shouldBe "workspace/my-project"
            store.close()
        } finally {
            tmpFile.delete()
        }
    }

    test("logs_root migration: idempotent for workspace/ entries") {
        val tmpFile = Files.createTempFile("attractor-logsroot-idempotent-test-", ".db").toFile()
        try {
            // Create DB with an already-correct workspace/ prefix
            val conn = DriverManager.getConnection("jdbc:sqlite:${tmpFile.absolutePath}")
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS project_runs (
                    id TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL DEFAULT '',
                    dot_source TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'running',
                    logs_root TEXT NOT NULL DEFAULT '',
                    simulate INTEGER NOT NULL DEFAULT 0,
                    auto_approve INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    project_log TEXT NOT NULL DEFAULT '',
                    archived INTEGER NOT NULL DEFAULT 0,
                    original_prompt TEXT NOT NULL DEFAULT '',
                    finished_at INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT NOT NULL DEFAULT '',
                    project_family_id TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            conn.createStatement().execute(
                "INSERT INTO project_runs (id, file_name, dot_source, status, logs_root, created_at, project_family_id) VALUES ('lr-test-2', 'test.dot', 'digraph G {}', 'completed', 'workspace/my-project', 1000, 'fam-lr2')"
            )
            conn.close()

            val store = SqliteRunStore(tmpFile.absolutePath)
            val run = store.getById("lr-test-2")
            run shouldNotBe null
            run!!.logsRoot shouldBe "workspace/my-project"
            store.close()
        } finally {
            tmpFile.delete()
        }
    }

    test("logs_root migration: blank logsRoot remains unchanged") {
        val tmpFile = Files.createTempFile("attractor-logsroot-blank-test-", ".db").toFile()
        try {
            val store = SqliteRunStore(tmpFile.absolutePath)
            store.insert("lr-test-3", "test.dot", "digraph G {}", false, true)
            store.close()

            val store2 = SqliteRunStore(tmpFile.absolutePath)
            val run = store2.getById("lr-test-3")
            run shouldNotBe null
            run!!.logsRoot shouldBe ""
            store2.close()
        } finally {
            tmpFile.delete()
        }
    }
})
