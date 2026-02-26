package attractor.state

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class ArtifactInfo(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val storedAt: Instant = Instant.now(),
    val isFileBacked: Boolean = false
)

class ArtifactStore(private val baseDir: String? = null) {
    private val artifacts: MutableMap<String, Pair<ArtifactInfo, Any>> = mutableMapOf()
    private val lock = ReentrantReadWriteLock()
    private val json = Json { prettyPrint = true }

    companion object {
        const val FILE_BACKING_THRESHOLD = 100 * 1024L // 100KB
    }

    fun store(artifactId: String, name: String, data: Any): ArtifactInfo {
        val serialized = when (data) {
            is String -> data
            else -> json.encodeToString(data.toString())
        }
        val size = serialized.length.toLong()
        val isFileBacked = size > FILE_BACKING_THRESHOLD && baseDir != null

        val storedData: Any
        if (isFileBacked && baseDir != null) {
            val artDir = File(baseDir, "artifacts")
            artDir.mkdirs()
            val artFile = File(artDir, "$artifactId.json")
            artFile.writeText(serialized)
            storedData = artFile.absolutePath
        } else {
            storedData = data
        }

        val info = ArtifactInfo(
            id = artifactId,
            name = name,
            sizeBytes = size,
            isFileBacked = isFileBacked
        )

        lock.write { artifacts[artifactId] = Pair(info, storedData) }
        return info
    }

    fun retrieve(artifactId: String): Any {
        val entry = lock.read { artifacts[artifactId] }
            ?: throw IllegalArgumentException("Artifact not found: $artifactId")
        val (info, data) = entry
        return if (info.isFileBacked) {
            File(data as String).readText()
        } else {
            data
        }
    }

    fun has(artifactId: String): Boolean = lock.read { artifacts.containsKey(artifactId) }

    fun list(): List<ArtifactInfo> = lock.read { artifacts.values.map { it.first } }

    fun remove(artifactId: String) {
        lock.write {
            val entry = artifacts.remove(artifactId)
            if (entry != null) {
                val (info, data) = entry
                if (info.isFileBacked) {
                    File(data as String).delete()
                }
            }
        }
    }

    fun clear() {
        lock.write {
            artifacts.forEach { (_, entry) ->
                val (info, data) = entry
                if (info.isFileBacked) {
                    File(data as String).delete()
                }
            }
            artifacts.clear()
        }
    }
}
