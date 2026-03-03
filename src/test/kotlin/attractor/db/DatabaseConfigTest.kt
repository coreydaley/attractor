package attractor.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class DatabaseConfigTest : FunSpec({

    test("no env vars defaults to SQLite attractor.db") {
        val config = DatabaseConfig.fromEnv(emptyMap())
        config.type        shouldBe DbType.SQLITE
        config.jdbcUrl     shouldBe "jdbc:sqlite:attractor.db"
        config.user        shouldBe null
        config.password    shouldBe null
        config.displayName shouldBe "SQLite (attractor.db)"
    }

    test("ATTRACTOR_DB_NAME with no type uses SQLite with custom path") {
        val config = DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_NAME" to "custom.db"))
        config.type    shouldBe DbType.SQLITE
        config.jdbcUrl shouldBe "jdbc:sqlite:custom.db"
        config.displayName shouldBe "SQLite (custom.db)"
    }

    test("ATTRACTOR_DB_TYPE=sqlite with custom name") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_TYPE" to "sqlite",
            "ATTRACTOR_DB_NAME" to "myfile.db"
        ))
        config.type    shouldBe DbType.SQLITE
        config.jdbcUrl shouldBe "jdbc:sqlite:myfile.db"
    }

    test("ATTRACTOR_DB_TYPE=mysql defaults") {
        val config = DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_TYPE" to "mysql"))
        config.type        shouldBe DbType.MYSQL
        config.jdbcUrl     shouldBe "jdbc:mysql://localhost:3306/attractor"
        config.user        shouldBe null
        config.password    shouldBe null
        config.displayName shouldBe "MySQL at localhost:3306/attractor"
    }

    test("ATTRACTOR_DB_TYPE=mysql with full piecewise params") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_TYPE"     to "mysql",
            "ATTRACTOR_DB_HOST"     to "myhost",
            "ATTRACTOR_DB_PORT"     to "3307",
            "ATTRACTOR_DB_NAME"     to "mydb",
            "ATTRACTOR_DB_USER"     to "u",
            "ATTRACTOR_DB_PASSWORD" to "p"
        ))
        config.type     shouldBe DbType.MYSQL
        config.jdbcUrl  shouldBe "jdbc:mysql://myhost:3307/mydb"
        config.user     shouldBe "u"
        config.password shouldBe "p"
        config.displayName shouldBe "MySQL at myhost:3307/mydb"
    }

    test("ATTRACTOR_DB_TYPE=postgresql defaults") {
        val config = DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_TYPE" to "postgresql"))
        config.type        shouldBe DbType.POSTGRESQL
        config.jdbcUrl     shouldBe "jdbc:postgresql://localhost:5432/attractor"
        config.displayName shouldBe "PostgreSQL at localhost:5432/attractor"
    }

    test("ATTRACTOR_DB_TYPE=postgres is accepted as alias for postgresql") {
        val config = DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_TYPE" to "postgres"))
        config.type shouldBe DbType.POSTGRESQL
        config.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/attractor"
    }

    test("ATTRACTOR_DB_TYPE=MySQL (mixed case) is accepted") {
        val config = DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_TYPE" to "MySQL"))
        config.type shouldBe DbType.MYSQL
    }

    test("ATTRACTOR_DB_PARAMS appended as query string") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_TYPE"   to "mysql",
            "ATTRACTOR_DB_PARAMS" to "sslMode=REQUIRED"
        ))
        config.jdbcUrl shouldBe "jdbc:mysql://localhost:3306/attractor?sslMode=REQUIRED"
    }

    test("ATTRACTOR_DB_URL JDBC PostgreSQL form") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "jdbc:postgresql://host:5432/db"
        ))
        config.type    shouldBe DbType.POSTGRESQL
        config.jdbcUrl shouldBe "jdbc:postgresql://host:5432/db"
    }

    test("ATTRACTOR_DB_URL JDBC MySQL form") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "jdbc:mysql://host:3306/db"
        ))
        config.type    shouldBe DbType.MYSQL
        config.jdbcUrl shouldBe "jdbc:mysql://host:3306/db"
    }

    test("ATTRACTOR_DB_URL simplified postgres:// form is normalized") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "postgres://app:pass@host:5432/db"
        ))
        config.type shouldBe DbType.POSTGRESQL
        config.jdbcUrl shouldContain "jdbc:postgresql://"
    }

    test("ATTRACTOR_DB_URL simplified mysql:// form is normalized") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "mysql://app:pass@host:3306/db"
        ))
        config.type shouldBe DbType.MYSQL
        config.jdbcUrl shouldContain "jdbc:mysql://"
    }

    test("ATTRACTOR_DB_URL jdbc:sqlite: form") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "jdbc:sqlite:my.db"
        ))
        config.type    shouldBe DbType.SQLITE
        config.jdbcUrl shouldBe "jdbc:sqlite:my.db"
    }

    test("unknown ATTRACTOR_DB_TYPE throws IllegalArgumentException with hint") {
        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseConfig.fromEnv(mapOf("ATTRACTOR_DB_TYPE" to "oracle"))
        }
        ex.message shouldContain "oracle"
        ex.message shouldContain "sqlite"
        ex.message shouldContain "mysql"
        ex.message shouldContain "postgresql"
    }

    test("displayName never contains password from piecewise config") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_TYPE"     to "postgresql",
            "ATTRACTOR_DB_HOST"     to "pg.example.com",
            "ATTRACTOR_DB_PASSWORD" to "supersecret"
        ))
        config.displayName shouldNotContain "supersecret"
    }

    test("displayName never contains password from URL config") {
        val config = DatabaseConfig.fromEnv(mapOf(
            "ATTRACTOR_DB_URL" to "jdbc:postgresql://host:5432/db?user=app&password=topsecret"
        ))
        config.displayName shouldNotContain "topsecret"
    }
})
