package attractor.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    test("init() creates .git directory and .gitignore") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-test-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, ".git").exists() shouldBe true
            val gitignore = File(dir, ".gitignore")
            gitignore.exists() shouldBe true
            gitignore.readText() shouldContain ".DS_Store"
            gitignore.readText() shouldContain "*.class"
            gitignore.readText() shouldContain "node_modules/"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("init() creates the target directory if it does not exist") {
        if (!gitOnPath()) return@test
        val parent = Files.createTempDirectory("attractor-git-mkdirs-").toFile()
        val dir = File(parent, "workspace")
        try {
            dir.exists() shouldBe false
            WorkspaceGit.init(dir.absolutePath)
            dir.exists() shouldBe true
            File(dir, ".git").exists() shouldBe true
        } finally {
            parent.deleteRecursively()
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
            // init() creates 1 initial commit for .gitignore; no run outputs added
            WorkspaceGit.commitIfChanged(dir.absolutePath, "should not appear")
            // Only the init commit should exist; "should not appear" must NOT be in the log
            val log = gitLogOneline(dir)
            log shouldNotContain "should not appear"
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
            // init commit + 1 run commit = 2 lines
            log.lines().size shouldBe 2
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
            // init commit + 1 run commit = 2; "should not appear" must not be present
            log.lines().size shouldBe 2
            log shouldNotContain "should not appear"
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── summary() tests ──────────────────────────────────────────────────────

    test("summary() returns available=false when git not initialized in dir") {
        val dir = Files.createTempDirectory("attractor-git-summary-nogit-").toFile()
        try {
            val s = WorkspaceGit.summary(dir.absolutePath)
            // No .git directory — repoExists should be false regardless of git availability
            s.repoExists shouldBe false
            s.commitCount shouldBe 0
            s.lastCommit shouldBe null
            s.recent.isEmpty() shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() returns repoExists=false on dir without .git") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-norepo-").toFile()
        try {
            val s = WorkspaceGit.summary(dir.absolutePath)
            s.available shouldBe true
            s.repoExists shouldBe false
            s.commitCount shouldBe 0
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() on initialized repo shows init commit") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-nocommits-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            // init() creates 1 commit for .gitignore — no run outputs added yet
            val s = WorkspaceGit.summary(dir.absolutePath)
            s.available shouldBe true
            s.repoExists shouldBe true
            s.commitCount shouldBe 1
            s.lastCommit?.subject shouldBe "init: workspace repository"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() returns correct values after one run commit") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-onecommit-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, "result.txt").writeText("hello")
            WorkspaceGit.commitIfChanged(dir.absolutePath, "Run run-001 completed: 2 stages")
            val s = WorkspaceGit.summary(dir.absolutePath)
            s.available shouldBe true
            s.repoExists shouldBe true
            // init commit + 1 run commit = 2
            s.commitCount shouldBe 2
            s.lastCommit?.subject shouldBe "Run run-001 completed: 2 stages"
            s.recent.size shouldBe 2
            s.recent[0].subject shouldBe "Run run-001 completed: 2 stages"
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() recent list is capped at recentLimit") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-cap-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            for (i in 1..7) {
                File(dir, "file$i.txt").writeText("content $i")
                WorkspaceGit.commitIfChanged(dir.absolutePath, "commit $i")
            }
            val s = WorkspaceGit.summary(dir.absolutePath, recentLimit = 5)
            // init commit + 7 run commits = 8
            s.commitCount shouldBe 8
            s.recent.size shouldBe 5
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() dirty=true when uncommitted files present") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-dirty-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, "unstaged.txt").writeText("not committed")
            val s = WorkspaceGit.summary(dir.absolutePath)
            s.dirty shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }

    test("summary() dirty=false after commit") {
        if (!gitOnPath()) return@test
        val dir = Files.createTempDirectory("attractor-git-summary-clean-").toFile()
        try {
            WorkspaceGit.init(dir.absolutePath)
            File(dir, "result.txt").writeText("done")
            WorkspaceGit.commitIfChanged(dir.absolutePath, "Run completed")
            val s = WorkspaceGit.summary(dir.absolutePath)
            s.dirty shouldBe false
        } finally {
            dir.deleteRecursively()
        }
    }
})
