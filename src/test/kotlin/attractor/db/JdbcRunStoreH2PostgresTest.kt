package attractor.db

import java.util.UUID

class JdbcRunStoreH2PostgresTest : RunStoreContractTest() {
    override fun createStore(): RunStore {
        val dbName = "test_${UUID.randomUUID().toString().replace("-", "")}"
        return JdbcRunStore(
            jdbcUrl  = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            user     = null,
            password = null,
            dialect  = SqlDialect.Postgresql
        )
    }
}
