package attractor.db

data class StoredRun(
    val id: String,
    val fileName: String,
    val dotSource: String,
    val status: String,
    val logsRoot: String,
    val simulate: Boolean,
    val autoApprove: Boolean,
    val createdAt: Long,
    val pipelineLog: String = "",
    val archived: Boolean = false,
    val originalPrompt: String = "",
    val finishedAt: Long = 0L,
    val displayName: String = "",
    val familyId: String = ""
)

interface RunStore {
    fun insert(id: String, fileName: String, dotSource: String, simulate: Boolean, autoApprove: Boolean, originalPrompt: String = "", displayName: String = "", familyId: String = "")
    fun updateFamilyId(id: String, familyId: String)
    fun updateStatus(id: String, status: String)
    fun updateLogsRoot(id: String, logsRoot: String)
    fun updateDotAndPrompt(id: String, dotSource: String, originalPrompt: String)
    fun updateLog(id: String, log: String)
    fun updateFinishedAt(id: String, finishedAt: Long)
    fun getAll(): List<StoredRun>
    fun getById(id: String): StoredRun?
    fun insertOrReplaceImported(run: StoredRun)
    fun getByFamilyId(familyId: String): List<StoredRun>
    fun archiveRun(id: String)
    fun unarchiveRun(id: String)
    fun deleteRun(id: String)
    fun getSetting(key: String): String?
    fun setSetting(key: String, value: String)
    fun close()
}
