package attractor.db

object RunStoreFactory {
    fun create(config: DatabaseConfig): RunStore {
        return try {
            when (config.type) {
                DbType.SQLITE     -> SqliteRunStore(config.jdbcUrl.removePrefix("jdbc:sqlite:"))
                DbType.MYSQL      -> JdbcRunStore(config.jdbcUrl, config.user, config.password, SqlDialect.Mysql)
                DbType.POSTGRESQL -> JdbcRunStore(config.jdbcUrl, config.user, config.password, SqlDialect.Postgresql)
            }
        } catch (e: Exception) {
            System.err.println("[attractor] ERROR: Failed to connect to database (${config.displayName})")
            System.err.println("[attractor] Cause: ${e.message}")
            System.err.println("[attractor] Check your ATTRACTOR_DB_* environment variables and ensure the database server is reachable.")
            System.exit(1)
            throw e  // unreachable; satisfies Kotlin's type checker
        }
    }
}
