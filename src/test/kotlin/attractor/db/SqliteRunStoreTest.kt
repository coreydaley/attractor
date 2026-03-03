package attractor.db

import java.nio.file.Files

class SqliteRunStoreTest : RunStoreContractTest() {
    private val tmpFile = Files.createTempFile("attractor-test-", ".db").toFile()

    override fun createStore(): RunStore = SqliteRunStore(tmpFile.absolutePath)

    init {
        afterSpec {
            tmpFile.delete()
        }
    }
}
