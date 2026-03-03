package attractor.db

import java.sql.DriverManager
import java.util.Properties

class JdbcRunStore(
    private val jdbcUrl: String,
    private val user: String?,
    private val password: String?,
    private val dialect: SqlDialect
) : RunStore {

    private val conn = run {
        if (user != null || password != null) {
            val props = Properties()
            user?.let { props["user"] = it }
            password?.let { props["password"] = it }
            DriverManager.getConnection(jdbcUrl, props)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }
    }

    init {
        conn.createStatement().use { stmt ->
            stmt.execute(dialect.createPipelineRunsTable())
        }
        conn.createStatement().use { stmt ->
            stmt.execute(dialect.createSettingsTable())
        }
        runCatching {
            conn.createStatement().use { stmt ->
                stmt.execute(dialect.createFamilyIndex())
            }
        }
        conn.createStatement().use { stmt ->
            stmt.execute(dialect.insertDefaultSetting("fireworks_enabled", "true"))
        }

        // Migrations: add columns that may be missing from older schemas
        // For MySQL/PostgreSQL we use IF NOT EXISTS; for SQLite we rely on runCatching
        listOf(
            Triple("pipeline_runs", "pipeline_log",       "TEXT NOT NULL DEFAULT ''"),
            Triple("pipeline_runs", "archived",           "INT  NOT NULL DEFAULT 0"),
            Triple("pipeline_runs", "original_prompt",    "TEXT NOT NULL DEFAULT ''"),
            Triple("pipeline_runs", "finished_at",        "BIGINT NOT NULL DEFAULT 0"),
            Triple("pipeline_runs", "display_name",       "TEXT NOT NULL DEFAULT ''"),
            Triple("pipeline_runs", "pipeline_family_id", "VARCHAR(255) NOT NULL DEFAULT ''")
        ).forEach { (table, col, def) ->
            runCatching {
                conn.createStatement().use { stmt ->
                    stmt.execute(dialect.addColumnIfMissing(table, col, def))
                }
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet) = StoredRun(
        id             = rs.getString("id"),
        fileName       = rs.getString("file_name"),
        dotSource      = rs.getString("dot_source"),
        status         = rs.getString("status"),
        logsRoot       = rs.getString("logs_root"),
        simulate       = rs.getInt("simulate") != 0,
        autoApprove    = rs.getInt("auto_approve") != 0,
        createdAt      = rs.getLong("created_at"),
        pipelineLog    = rs.getString("pipeline_log") ?: "",
        archived       = rs.getInt("archived") != 0,
        originalPrompt = rs.getString("original_prompt") ?: "",
        finishedAt     = rs.getLong("finished_at"),
        displayName    = rs.getString("display_name") ?: "",
        familyId       = rs.getString("pipeline_family_id") ?: ""
    )

    @Synchronized
    override fun insert(id: String, fileName: String, dotSource: String, simulate: Boolean, autoApprove: Boolean, originalPrompt: String, displayName: String, familyId: String) {
        val effectiveFamilyId = familyId.ifBlank { id }
        conn.prepareStatement(dialect.upsertIgnoreRun()).use { ps ->
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
        conn.prepareStatement("UPDATE pipeline_runs SET pipeline_family_id=? WHERE id=?").use { ps ->
            ps.setString(1, familyId)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateStatus(id: String, status: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateLogsRoot(id: String, logsRoot: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET logs_root=? WHERE id=?").use { ps ->
            ps.setString(1, logsRoot)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateDotAndPrompt(id: String, dotSource: String, originalPrompt: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET dot_source=?, original_prompt=? WHERE id=?").use { ps ->
            ps.setString(1, dotSource)
            ps.setString(2, originalPrompt)
            ps.setString(3, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateLog(id: String, log: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET pipeline_log=? WHERE id=?").use { ps ->
            ps.setString(1, log)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun updateFinishedAt(id: String, finishedAt: Long) {
        conn.prepareStatement("UPDATE pipeline_runs SET finished_at=? WHERE id=?").use { ps ->
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
                "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id FROM pipeline_runs ORDER BY created_at ASC"
            ).use { rs ->
                while (rs.next()) result.add(mapRow(rs))
            }
        }
        return result
    }

    @Synchronized
    override fun getById(id: String): StoredRun? {
        conn.prepareStatement(
            "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id FROM pipeline_runs WHERE id=?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapRow(rs)
            }
        }
    }

    @Synchronized
    override fun insertOrReplaceImported(run: StoredRun) {
        // Delete-then-insert works on all JDBC backends (MySQL, PostgreSQL, H2)
        conn.prepareStatement("DELETE FROM pipeline_runs WHERE id = ?").use { ps ->
            ps.setString(1, run.id); ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, run.id)
            ps.setString(2, run.fileName)
            ps.setString(3, run.dotSource)
            ps.setString(4, run.status)
            ps.setString(5, run.logsRoot)
            ps.setInt(6, if (run.simulate) 1 else 0)
            ps.setInt(7, if (run.autoApprove) 1 else 0)
            ps.setLong(8, run.createdAt)
            ps.setString(9, run.pipelineLog)
            ps.setInt(10, if (run.archived) 1 else 0)
            ps.setString(11, run.originalPrompt)
            ps.setLong(12, run.finishedAt)
            ps.setString(13, run.displayName)
            ps.setString(14, run.familyId)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getByFamilyId(familyId: String): List<StoredRun> {
        if (familyId.isBlank()) return emptyList()
        val result = mutableListOf<StoredRun>()
        conn.prepareStatement(
            "SELECT id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id FROM pipeline_runs WHERE pipeline_family_id=? ORDER BY created_at ASC"
        ).use { ps ->
            ps.setString(1, familyId)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(mapRow(rs))
            }
        }
        return result
    }

    @Synchronized
    override fun archiveRun(id: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET archived=1 WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun unarchiveRun(id: String) {
        conn.prepareStatement("UPDATE pipeline_runs SET archived=0 WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun deleteRun(id: String) {
        conn.prepareStatement("DELETE FROM pipeline_runs WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    @Synchronized
    override fun getSetting(key: String): String? {
        conn.prepareStatement(dialect.selectSettingByKey()).use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString(dialect.settingValueColumn()) else null
            }
        }
    }

    @Synchronized
    override fun setSetting(key: String, value: String) {
        // Delete-then-insert works on all JDBC backends (MySQL, PostgreSQL, H2)
        val kCol = dialect.settingKeyColumn()
        val vCol = dialect.settingValueColumn()
        conn.prepareStatement("DELETE FROM settings WHERE $kCol = ?").use { ps ->
            ps.setString(1, key); ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO settings ($kCol, $vCol) VALUES (?, ?)").use { ps ->
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
