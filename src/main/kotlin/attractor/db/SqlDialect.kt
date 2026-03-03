package attractor.db

sealed class SqlDialect {

    object Sqlite : SqlDialect()
    object Mysql : SqlDialect()
    object Postgresql : SqlDialect()

    fun createPipelineRunsTable(): String = """
        CREATE TABLE IF NOT EXISTS pipeline_runs (
            id                   VARCHAR(255) PRIMARY KEY,
            file_name            TEXT         NOT NULL,
            dot_source           TEXT         NOT NULL,
            status               VARCHAR(64)  NOT NULL DEFAULT 'running',
            logs_root            TEXT         NOT NULL DEFAULT '',
            simulate             INT          NOT NULL DEFAULT 0,
            auto_approve         INT          NOT NULL DEFAULT 1,
            created_at           BIGINT       NOT NULL,
            pipeline_log         TEXT         NOT NULL DEFAULT '',
            archived             INT          NOT NULL DEFAULT 0,
            original_prompt      TEXT         NOT NULL DEFAULT '',
            finished_at          BIGINT       NOT NULL DEFAULT 0,
            display_name         TEXT         NOT NULL DEFAULT '',
            pipeline_family_id   VARCHAR(255) NOT NULL DEFAULT ''
        )
    """.trimIndent()

    // SQLite keeps legacy column names (key, value) for backwards compat with existing databases.
    // MySQL and PostgreSQL use (setting_key, setting_value) to avoid reserved-word conflicts.
    fun createSettingsTable(): String = when (this) {
        is Sqlite     -> "CREATE TABLE IF NOT EXISTS settings (\n    key   TEXT PRIMARY KEY,\n    value TEXT NOT NULL\n)"
        is Mysql      -> "CREATE TABLE IF NOT EXISTS settings (\n    setting_key   VARCHAR(255) PRIMARY KEY,\n    setting_value TEXT         NOT NULL\n)"
        is Postgresql -> "CREATE TABLE IF NOT EXISTS settings (\n    setting_key   VARCHAR(255) PRIMARY KEY,\n    setting_value TEXT         NOT NULL\n)"
    }

    fun createFamilyIndex(): String =
        "CREATE INDEX IF NOT EXISTS idx_pipeline_runs_family_created ON pipeline_runs(pipeline_family_id, created_at)"

    /** Idempotent insert for initial fireworks_enabled default setting */
    fun insertDefaultSetting(key: String, value: String): String = when (this) {
        is Sqlite      -> "INSERT OR IGNORE INTO settings (key, value) VALUES ('$key', '$value')"
        is Mysql       -> "INSERT IGNORE INTO settings (setting_key, setting_value) VALUES ('$key', '$value')"
        is Postgresql  -> "INSERT INTO settings (setting_key, setting_value) VALUES ('$key', '$value') ON CONFLICT DO NOTHING"
    }

    /** SELECT for a single setting by key */
    fun selectSettingByKey(): String = when (this) {
        is Sqlite     -> "SELECT value FROM settings WHERE key = ?"
        is Mysql      -> "SELECT setting_value FROM settings WHERE setting_key = ?"
        is Postgresql -> "SELECT setting_value FROM settings WHERE setting_key = ?"
    }

    /** Column name for the key in the settings table */
    fun settingKeyColumn(): String = when (this) {
        is Sqlite -> "key"
        is Mysql, is Postgresql -> "setting_key"
    }

    /** Column name for the value in the settings table */
    fun settingValueColumn(): String = when (this) {
        is Sqlite -> "value"
        is Mysql, is Postgresql -> "setting_value"
    }

    /** INSERT that silently skips on duplicate id */
    fun upsertIgnoreRun(): String = when (this) {
        is Sqlite     -> "INSERT OR IGNORE INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, original_prompt, display_name, pipeline_family_id) VALUES (?,?,?,'running','',?,?,?,?,?,?)"
        is Mysql      -> "INSERT IGNORE INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, original_prompt, display_name, pipeline_family_id) VALUES (?,?,?,'running','',?,?,?,?,?,?)"
        is Postgresql -> "INSERT INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, original_prompt, display_name, pipeline_family_id) VALUES (?,?,?,'running','',?,?,?,?,?,?) ON CONFLICT DO NOTHING"
    }

    /** Full-column upsert used by insertOrReplaceImported */
    fun upsertReplaceImported(): String = when (this) {
        is Sqlite -> "INSERT OR REPLACE INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        is Mysql  -> "REPLACE INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        is Postgresql -> """
            INSERT INTO pipeline_runs (id, file_name, dot_source, status, logs_root, simulate, auto_approve, created_at, pipeline_log, archived, original_prompt, finished_at, display_name, pipeline_family_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (id) DO UPDATE SET
              file_name          = EXCLUDED.file_name,
              dot_source         = EXCLUDED.dot_source,
              status             = EXCLUDED.status,
              logs_root          = EXCLUDED.logs_root,
              simulate           = EXCLUDED.simulate,
              auto_approve       = EXCLUDED.auto_approve,
              created_at         = EXCLUDED.created_at,
              pipeline_log       = EXCLUDED.pipeline_log,
              archived           = EXCLUDED.archived,
              original_prompt    = EXCLUDED.original_prompt,
              finished_at        = EXCLUDED.finished_at,
              display_name       = EXCLUDED.display_name,
              pipeline_family_id = EXCLUDED.pipeline_family_id
        """.trimIndent()
    }

    /** Upsert for settings key/value */
    fun upsertReplaceSetting(): String = when (this) {
        is Sqlite     -> "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
        is Mysql      -> "REPLACE INTO settings (setting_key, setting_value) VALUES (?, ?)"
        is Postgresql -> "INSERT INTO settings (setting_key, setting_value) VALUES (?, ?) ON CONFLICT(setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value"
    }

    /** ALTER TABLE to add a column; returns null if NOT EXISTS clause is not available (SQLite). */
    fun addColumnIfMissing(table: String, column: String, definition: String): String = when (this) {
        is Sqlite     -> "ALTER TABLE $table ADD COLUMN $column $definition"  // no IF NOT EXISTS in SQLite; wrapped in runCatching
        is Mysql      -> "ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column $definition"
        is Postgresql -> "ALTER TABLE $table ADD COLUMN IF NOT EXISTS $column $definition"
    }
}
