package attractor.workspace

import java.io.File
import java.util.concurrent.TimeUnit

object WorkspaceGit {

    private val gitAvailable: Boolean by lazy {
        runCatching {
            ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start()
                .waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    /**
     * Initializes a git repository in [dir] if not already initialized.
     * Also writes a local git identity so commits work without a global git config.
     * No-op if git is unavailable or [dir] does not exist.
     */
    fun init(dir: String) {
        if (!gitAvailable) return
        val f = File(dir)
        if (!f.isDirectory) return
        if (File(f, ".git").exists()) return
        runCatching {
            ProcessBuilder("git", "init")
                .directory(f).redirectErrorStream(true).start()
                .waitFor(30, TimeUnit.SECONDS)
            ProcessBuilder("git", "config", "user.name", "Attractor")
                .directory(f).redirectErrorStream(true).start()
                .waitFor(10, TimeUnit.SECONDS)
            ProcessBuilder("git", "config", "user.email", "attractor@localhost")
                .directory(f).redirectErrorStream(true).start()
                .waitFor(10, TimeUnit.SECONDS)
        }
    }

    /**
     * Stages all workspace changes and commits if anything changed.
     * Calls [init] first as a guard (workspace may not have existed at the earlier init call).
     * No-op if git is unavailable, workspace has no .git, or there are no changes to commit.
     */
    fun commitIfChanged(dir: String, message: String) {
        if (!gitAvailable) return
        init(dir)
        val f = File(dir)
        if (!File(f, ".git").exists()) return
        runCatching {
            ProcessBuilder("git", "add", "-A")
                .directory(f).redirectErrorStream(true).start()
                .waitFor(30, TimeUnit.SECONDS)
            val statusProc = ProcessBuilder("git", "status", "--porcelain")
                .directory(f).redirectErrorStream(true).start()
            val statusOut = statusProc.inputStream.bufferedReader().readText().trim()
            statusProc.waitFor(10, TimeUnit.SECONDS)
            if (statusOut.isEmpty()) return
            ProcessBuilder("git", "commit", "-m", message)
                .directory(f).redirectErrorStream(true).start()
                .waitFor(30, TimeUnit.SECONDS)
        }
    }
}
