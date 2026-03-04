package attractor.db

import java.sql.DriverManager

class SqliteRunStore(dbPath: String) : RunStore {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA cache_size=-32000")   // 32 MB (negative = KB)
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA temp_store=MEMORY")
        }
        // Migration: rename pipeline_runs table and columns to project_runs (idempotent)
        try { conn.createStatement().execute("ALTER TABLE pipeline_runs RENAME TO project_runs") } catch (_: Exception) {}
        try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log") } catch (_: Exception) {}
        try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_family_id TO project_family_id") } catch (_: Exception) {}
        try {
            conn.createStatement().execute("DROP INDEX IF EXISTS idx_pipeline_runs_family_created")
            conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_project_runs_family_created ON project_runs(project_family_id, created_at)")
        } catch (_: Exception) {}
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS project_runs (
                    id             TEXT    PRIMARY KEY,
                    file_name      TEXT    NOT NULL,
                    dot_source     TEXT    NOT NULL,
                    status         TEXT    NOT NULL DEFAULT 'running',
                    logs_root      TEXT    NOT NULL DEFAULT '',
                    simulate       INTEGER NOT NULL DEFAULT 0,
                    auto_approve   INTEGER NOT NULL DEFAULT 1,
                    created_at     INTEGER NOT NULL,
                    project_log    TEXT    NOT NULL DEFAULT '',
                    archived       INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
        // Migration: add project_log column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN project_log TEXT NOT NULL DEFAULT ''")
            }
        }
        // Migration: add archived column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
        // Migration: add original_prompt column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN original_prompt TEXT NOT NULL DEFAULT ''")
            }
        }
        // Migration: add finished_at column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN finished_at INTEGER NOT NULL DEFAULT 0")
            }
        }
        // Migration: add display_name column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN display_name TEXT NOT NULL DEFAULT ''")
            }
        }
        // Migration: add project_family_id column to existing databases
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE project_runs ADD COLUMN project_family_id TEXT NOT NULL DEFAULT ''")
            }
        }
        // Index for fast family lookups
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_project_runs_family_created ON project_runs(project_family_id, created_at)")
            }
        }
        conn.createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """.trimIndent())
        }
        conn.createStatement().use { stmt ->
            stmt.execute("INSERT OR IGNORE INTO settings (key, value) VALUES ('fireworks_enabled', 'true')")
        }
    }

    @Synchronized
    override fun insert(id: String, fileName: String, dotSource: String, simulate: Boolean, autoApprove: Boolean, originalPrompt: String, displayName: String, familyId: String) {
        val effectiveFamilyId = familyId.ifBlank { id }
        conn.prepareStatement(
            "INSERT OR IGNORE INTO project_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, original_prompt, display_name, project_family_id) VALUES (?,?,?,'running','',?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, fileName)
            ps.setString(3, dotSource)
            ps.setInt(4, if (simulate) 1 else 0)
            ps.setInt(5, if (autoApprove) 1 else 0)
            ps.setLong(6, System.currentTimeMillis())
            ps.setString(7, originalPrompt)
            ps.setString(8, displayName)
            ps.setString(9, effectiveFamilyId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateFamilyId(id: String, familyId: String) {
        conn.prepareStatement("UPDATE project_runs SET project_family_id=? WHERE id=?").use { ps ->
            ps.setString(1, familyId)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateStatus(id: String, status: String) {
        conn.prepareStatement("UPDATE project_runs SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateLogsRoot(id: String, logsRoot: String) {
        conn.prepareStatement("UPDATE project_runs SET logs_root=? WHERE id=?").use { ps ->
            ps.setString(1, logsRoot)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateDotAndPrompt(id: String, dotSource: String, originalPrompt: String) {
        conn.prepareStatement("UPDATE project_runs SET dot_source=?, original_prompt=? WHERE id=?").use { ps ->
            ps.setString(1, dotSource)
            ps.setString(2, originalPrompt)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateLog(id: String, log: String) {
        conn.prepareStatement("UPDATE project_runs SET project_log=? WHERE id=?").use { ps ->
            ps.setString(1, log)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateFinishedAt(id: String, finishedAt: Long) {
        conn.prepareStatement("UPDATE project_runs SET finished_at=? WHERE id=?").use { ps ->
            ps.setLong(1, finishedAt)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getAll(): List<StoredRun> {
        val result = mutableListOf<StoredRun>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, project_log, archived, original_prompt, finished_at, display_name, project_family_id FROM project_runs ORDER BY created_at ASC"
            ).use { rs ->
                while (rs.next()) {
                    result.add(StoredRun(
                        id             = rs.getString("id"),
                        fileName       = rs.getString("file_name"),
                        dotSource      = rs.getString("dot_source"),
                        status         = rs.getString("status"),
                        logsRoot       = rs.getString("logs_root"),
                        simulate       = rs.getInt("simulate") != 0,
                        autoApprove    = rs.getInt("auto_approve") != 0,
                        createdAt      = rs.getLong("created_at"),
                        projectLog     = rs.getString("project_log") ?: "",
                        archived       = rs.getInt("archived") != 0,
                        originalPrompt = rs.getString("original_prompt") ?: "",
                        finishedAt     = rs.getLong("finished_at"),
                        displayName    = rs.getString("display_name") ?: "",
                        familyId       = rs.getString("project_family_id") ?: ""
                    ))
                }
            }
        }
        return result
    }

    @Synchronized
    override fun getById(id: String): StoredRun? {
        conn.prepareStatement(
            "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, project_log, archived, original_prompt, finished_at, display_name, project_family_id FROM project_runs WHERE id=?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return StoredRun(
                    id             = rs.getString("id"),
                    fileName       = rs.getString("file_name"),
                    dotSource      = rs.getString("dot_source"),
                    status         = rs.getString("status"),
                    logsRoot       = rs.getString("logs_root"),
                    simulate       = rs.getInt("simulate") != 0,
                    autoApprove    = rs.getInt("auto_approve") != 0,
                    createdAt      = rs.getLong("created_at"),
                    projectLog     = rs.getString("project_log") ?: "",
                    archived       = rs.getInt("archived") != 0,
                    originalPrompt = rs.getString("original_prompt") ?: "",
                    finishedAt     = rs.getLong("finished_at"),
                    displayName    = rs.getString("display_name") ?: "",
                    familyId       = rs.getString("project_family_id") ?: ""
                )
            }
        }
    }

    @Synchronized
    override fun insertOrReplaceImported(run: StoredRun) {
        val familyId = run.familyId
        conn.prepareStatement(
            "INSERT OR REPLACE INTO project_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, project_log, archived, original_prompt, finished_at, display_name, project_family_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, run.id)
            ps.setString(2, run.fileName)
            ps.setString(3, run.dotSource)
            ps.setString(4, run.status)
            ps.setString(5, run.logsRoot)
            ps.setInt(6, if (run.simulate) 1 else 0)
            ps.setInt(7, if (run.autoApprove) 1 else 0)
            ps.setLong(8, run.createdAt)
            ps.setString(9, run.projectLog)
            ps.setInt(10, if (run.archived) 1 else 0)
            ps.setString(11, run.originalPrompt)
            ps.setLong(12, run.finishedAt)
            ps.setString(13, run.displayName)
            ps.setString(14, familyId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getByFamilyId(familyId: String): List<StoredRun> {
        if (familyId.isBlank()) return emptyList()
        val result = mutableListOf<StoredRun>()
        conn.prepareStatement(
            "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, project_log, archived, original_prompt, finished_at, display_name, project_family_id FROM project_runs WHERE project_family_id=? ORDER BY created_at ASC"
        ).use { ps ->
            ps.setString(1, familyId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(StoredRun(
                        id             = rs.getString("id"),
                        fileName       = rs.getString("file_name"),
                        dotSource      = rs.getString("dot_source"),
                        status         = rs.getString("status"),
                        logsRoot       = rs.getString("logs_root"),
                        simulate       = rs.getInt("simulate") != 0,
                        autoApprove    = rs.getInt("auto_approve") != 0,
                        createdAt      = rs.getLong("created_at"),
                        projectLog     = rs.getString("project_log") ?: "",
                        archived       = rs.getInt("archived") != 0,
                        originalPrompt = rs.getString("original_prompt") ?: "",
                        finishedAt     = rs.getLong("finished_at"),
                        displayName    = rs.getString("display_name") ?: "",
                        familyId       = rs.getString("project_family_id") ?: ""
                    ))
                }
            }
        }
        return result
    }

    @Synchronized
    override fun archiveRun(id: String) {
        conn.prepareStatement("UPDATE project_runs SET archived=1 WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun unarchiveRun(id: String) {
        conn.prepareStatement("UPDATE project_runs SET archived=0 WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun deleteRun(id: String) {
        conn.prepareStatement("DELETE FROM project_runs WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getSetting(key: String): String? {
        conn.prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString("value") else null
            }
        }
    }

    @Synchronized
    override fun setSetting(key: String, value: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)").use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { conn.close() }
    }
}
