package attractor.db

enum class DbType { SQLITE, MYSQL, POSTGRESQL }

data class DatabaseConfig(
    val type: DbType,
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
    val displayName: String
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): DatabaseConfig {
            val urlRaw = env["ATTRACTOR_DB_URL"]

            if (urlRaw != null) {
                return fromUrl(urlRaw)
            }

            val typeRaw = env["ATTRACTOR_DB_TYPE"]?.lowercase()

            if (typeRaw == null || typeRaw == "sqlite") {
                val name = env["ATTRACTOR_DB_NAME"] ?: "attractor.db"
                return DatabaseConfig(
                    type        = DbType.SQLITE,
                    jdbcUrl     = "jdbc:sqlite:$name",
                    user        = null,
                    password    = null,
                    displayName = "SQLite ($name)"
                )
            }

            val dbType = when (typeRaw) {
                "mysql"                  -> DbType.MYSQL
                "postgresql", "postgres" -> DbType.POSTGRESQL
                else -> throw IllegalArgumentException(
                    "Unknown ATTRACTOR_DB_TYPE: '$typeRaw'. Valid values: sqlite, mysql, postgresql"
                )
            }

            val host     = env["ATTRACTOR_DB_HOST"] ?: "localhost"
            val port     = env["ATTRACTOR_DB_PORT"]?.toIntOrNull()
                           ?: if (dbType == DbType.MYSQL) 3306 else 5432
            val name     = env["ATTRACTOR_DB_NAME"] ?: "attractor"
            val user     = env["ATTRACTOR_DB_USER"]
            val password = env["ATTRACTOR_DB_PASSWORD"]
            val params   = env["ATTRACTOR_DB_PARAMS"]

            val scheme = if (dbType == DbType.MYSQL) "mysql" else "postgresql"
            val baseUrl = "jdbc:$scheme://$host:$port/$name"
            val jdbcUrl = if (params != null) "$baseUrl?$params" else baseUrl

            val typeName = if (dbType == DbType.MYSQL) "MySQL" else "PostgreSQL"
            return DatabaseConfig(
                type        = dbType,
                jdbcUrl     = jdbcUrl,
                user        = user,
                password    = password,
                displayName = "$typeName at $host:$port/$name"
            )
        }

        private fun fromUrl(rawUrl: String): DatabaseConfig {
            // Normalize simplified URL schemes to JDBC form
            val url = when {
                rawUrl.startsWith("postgres://")     -> rawUrl.replaceFirst("postgres://", "jdbc:postgresql://")
                rawUrl.startsWith("postgresql://")   -> rawUrl.replaceFirst("postgresql://", "jdbc:postgresql://")
                rawUrl.startsWith("mysql://")        -> rawUrl.replaceFirst("mysql://", "jdbc:mysql://")
                rawUrl.startsWith("sqlite:")
                    && !rawUrl.startsWith("jdbc:")   -> "jdbc:$rawUrl"
                else                                 -> rawUrl
            }

            val type = when {
                url.startsWith("jdbc:postgresql:") -> DbType.POSTGRESQL
                url.startsWith("jdbc:mysql:")      -> DbType.MYSQL
                url.startsWith("jdbc:sqlite:")     -> DbType.SQLITE
                else -> throw IllegalArgumentException(
                    "Cannot determine database type from ATTRACTOR_DB_URL: '$rawUrl'. " +
                    "Expected a JDBC URL starting with jdbc:sqlite:, jdbc:mysql://, or jdbc:postgresql://"
                )
            }

            // Extract user/password from query params for safe displayName construction
            val user     = extractQueryParam(url, "user")
            val password = extractQueryParam(url, "password")

            // Build a credential-free display name
            val displayName = buildDisplayName(type, url)

            return DatabaseConfig(
                type        = type,
                jdbcUrl     = url,
                user        = user,
                password    = password,
                displayName = displayName
            )
        }

        private fun extractQueryParam(url: String, param: String): String? {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return null
            val query = url.substring(queryStart + 1)
            return query.split('&').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) null
                else if (part.substring(0, eq) == param) part.substring(eq + 1)
                else null
            }.firstOrNull()
        }

        private fun buildDisplayName(type: DbType, url: String): String {
            return when (type) {
                DbType.SQLITE -> {
                    val path = url.removePrefix("jdbc:sqlite:")
                    "SQLite ($path)"
                }
                DbType.MYSQL, DbType.POSTGRESQL -> {
                    // Parse host:port/db from jdbc:scheme://[userinfo@]host[:port]/db[?params]
                    val typeName = if (type == DbType.MYSQL) "MySQL" else "PostgreSQL"
                    try {
                        val withoutScheme = url.substringAfter("://")
                        val withoutUserInfo = if ('@' in withoutScheme) withoutScheme.substringAfter('@') else withoutScheme
                        val withoutQuery   = withoutUserInfo.substringBefore('?')
                        val hostPort       = withoutQuery.substringBefore('/')
                        val db             = withoutQuery.substringAfter('/', "")
                        "$typeName at $hostPort/$db"
                    } catch (_: Exception) {
                        typeName
                    }
                }
            }
        }
    }
}
