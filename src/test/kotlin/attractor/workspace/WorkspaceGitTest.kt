package attractor.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Returns true if git is available on PATH in this test environment. */
private fun gitOnPath(): Boolean = runCatching {
    ProcessBuilder("git", "--version")
        .redirectErrorStream(true)
        .start()
        .waitFor(10, TimeUnit.SECONDS)
}.getOrDefault(false)

private fun gitLogOneline(dir: File): String {
    val proc = ProcessBuilder("git", "log", "--oneline")
        .directory(dir).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor(10, TimeUnit.SECONDS)
    return out
}

class WorkspaceGitTest : FunSpec({

    test("init() on nonexistent dir is a no-op") {
        // Should not throw
        WorkspaceGit.init("/nonexistent/path/that/does/not/exist")
    }

    test("init() creates .git directory") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-test-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, ".git").exists() shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    test("init() is idempotent") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-idempotent-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            WorkspaceGit.init(dir.absolutePath)   // second call — no error
            File(dir, ".git").exists() shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    test("commitIfChanged() with no .git is a no-op") {
        val dir = Files.createTempDirectory("attractor-git-no-git-").toFile()
        try {
            // Should not throw
            WorkspaceGit.commitIfChanged(dir.absolutePath, "test message")
        } finally {
            dir.deleteRecursively()
        }
    }

    test("commitIfChanged() with no changes creates no commit") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-no-changes-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            WorkspaceGit.commitIfChanged(dir.absolutePath, "should not appear")
            // git log --oneline on a repo with zero commits outputs a fatal error — not a commit line
            val log = gitLogOneline(dir)
            (log.isEmpty() || log.contains("fatal")) shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    test("commitIfChanged() stages and commits a new file") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-commit-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, "output.txt").writeText("stage result")
            WorkspaceGit.commitIfChanged(dir.absolutePath, "Run run-1 completed: 2 stages")
            val log = gitLogOneline(dir)
            log.lines().size shouldBe 1
            log shouldContain "Run run-1 completed"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("commitIfChanged() does not double-commit unchanged state") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-no-double-commit-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, "output.txt").writeText("stage result")
            WorkspaceGit.commitIfChanged(dir.absolutePath, "first commit")
            WorkspaceGit.commitIfChanged(dir.absolutePath, "should not appear")
            val log = gitLogOneline(dir)
            log.lines().size shouldBe 1
        } finally {
            dir.deleteRecursively()
        }
    }
})
