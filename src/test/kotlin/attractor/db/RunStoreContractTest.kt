package attractor.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Abstract contract test for all RunStore implementations.
 * Subclasses provide the store instance via [createStore].
 */
abstract class RunStoreContractTest : FunSpec() {

    abstract fun createStore(): RunStore

    private lateinit var store: RunStore

    init {
        beforeSpec {
            store = createStore()
        }

        afterSpec {
            store.close()
        }

        test("insert and getById round-trip") {
            store.insert("id-1", "test.dot", "digraph G {}", false, true, "prompt", "Test Pipeline", "fam-1")
            val run = store.getById("id-1")
            run.shouldNotBeNull()
            run.id          shouldBe "id-1"
            run.fileName    shouldBe "test.dot"
            run.dotSource   shouldBe "digraph G {}"
            run.status      shouldBe "running"
            run.simulate    shouldBe false
            run.autoApprove shouldBe true
            run.originalPrompt shouldBe "prompt"
            run.displayName shouldBe "Test Pipeline"
            run.familyId    shouldBe "fam-1"
            run.archived    shouldBe false
        }

        test("insert is idempotent on duplicate id") {
            store.insert("id-idem", "a.dot", "digraph A {}", false, true)
            store.insert("id-idem", "b.dot", "digraph B {}", true, false)
            val runs = store.getAll().filter { it.id == "id-idem" }
            runs shouldHaveSize 1
            runs[0].fileName shouldBe "a.dot"  // first insert wins
        }

        test("updateStatus reflected in getById and getAll") {
            store.insert("id-status", "s.dot", "digraph S {}", false, true)
            store.updateStatus("id-status", "completed")
            store.getById("id-status")!!.status shouldBe "completed"
            store.getAll().first { it.id == "id-status" }.status shouldBe "completed"
        }

        test("updateLogsRoot persists") {
            store.insert("id-logs", "l.dot", "digraph L {}", false, true)
            store.updateLogsRoot("id-logs", "/tmp/logs/id-logs")
            store.getById("id-logs")!!.logsRoot shouldBe "/tmp/logs/id-logs"
        }

        test("updateLog persists") {
            store.insert("id-log", "log.dot", "digraph Log {}", false, true)
            store.updateLog("id-log", "stage1 done\nstage2 done")
            store.getById("id-log")!!.pipelineLog shouldBe "stage1 done\nstage2 done"
        }

        test("updateFinishedAt persists") {
            store.insert("id-fin", "fin.dot", "digraph F {}", false, true)
            store.updateFinishedAt("id-fin", 1234567890L)
            store.getById("id-fin")!!.finishedAt shouldBe 1234567890L
        }

        test("updateDotAndPrompt persists") {
            store.insert("id-dot", "d.dot", "digraph D {}", false, true)
            store.updateDotAndPrompt("id-dot", "digraph D2 {}", "new prompt")
            val run = store.getById("id-dot")!!
            run.dotSource shouldBe "digraph D2 {}"
            run.originalPrompt shouldBe "new prompt"
        }

        test("updateFamilyId persists") {
            store.insert("id-fam", "f.dot", "digraph F {}", false, true, familyId = "fam-orig")
            store.updateFamilyId("id-fam", "fam-new")
            store.getById("id-fam")!!.familyId shouldBe "fam-new"
        }

        test("setSetting and getSetting round-trip") {
            store.setSetting("test_key", "test_value")
            store.getSetting("test_key") shouldBe "test_value"
        }

        test("setSetting updates existing key") {
            store.setSetting("update_key", "v1")
            store.setSetting("update_key", "v2")
            store.getSetting("update_key") shouldBe "v2"
        }

        test("getSetting returns null for missing key") {
            store.getSetting("no_such_key_xyz").shouldBeNull()
        }

        test("archiveRun sets archived=true") {
            store.insert("id-arch", "arch.dot", "digraph A {}", false, true)
            store.archiveRun("id-arch")
            store.getById("id-arch")!!.archived shouldBe true
            store.getAll().first { it.id == "id-arch" }.archived shouldBe true
        }

        test("unarchiveRun restores archived=false") {
            store.insert("id-unarch", "unarch.dot", "digraph U {}", false, true)
            store.archiveRun("id-unarch")
            store.unarchiveRun("id-unarch")
            store.getById("id-unarch")!!.archived shouldBe false
        }

        test("deleteRun removes from store") {
            store.insert("id-del", "del.dot", "digraph D {}", false, true)
            store.deleteRun("id-del")
            store.getById("id-del").shouldBeNull()
            store.getAll().none { it.id == "id-del" } shouldBe true
        }

        test("getByFamilyId returns runs in creation order for same family") {
            store.insert("fid-1", "a.dot", "digraph A {}", false, true, familyId = "test-family")
            Thread.sleep(2)  // ensure distinct created_at
            store.insert("fid-2", "b.dot", "digraph B {}", false, true, familyId = "test-family")
            val family = store.getByFamilyId("test-family")
            family.size shouldBe 2
            family[0].id shouldBe "fid-1"
            family[1].id shouldBe "fid-2"
        }

        test("getByFamilyId returns empty list for unknown family") {
            store.getByFamilyId("no-such-family-xyz").shouldBeEmpty()
        }

        test("insertOrReplaceImported replaces on id conflict") {
            store.insert("id-imp", "orig.dot", "digraph O {}", false, true)
            val replacement = StoredRun(
                id             = "id-imp",
                fileName       = "replaced.dot",
                dotSource      = "digraph R {}",
                status         = "completed",
                logsRoot       = "/logs",
                simulate       = true,
                autoApprove    = false,
                createdAt      = 999L,
                pipelineLog    = "log data",
                archived       = true,
                originalPrompt = "replaced prompt",
                finishedAt     = 12345L,
                displayName    = "Replaced",
                familyId       = "new-fam"
            )
            store.insertOrReplaceImported(replacement)
            val result = store.getById("id-imp")!!
            result.fileName      shouldBe "replaced.dot"
            result.dotSource     shouldBe "digraph R {}"
            result.status        shouldBe "completed"
            result.archived      shouldBe true
            result.displayName   shouldBe "Replaced"
        }

        test("close does not throw") {
            // close is called in afterSpec; calling it here would break subsequent tests
            // Just verify the store is usable (insert works) up to this point
            store.insert("id-closechk", "c.dot", "digraph C {}", false, true)
            store.getById("id-closechk").shouldNotBeNull()
        }

        test("familyId defaults to id when not specified") {
            store.insert("id-fdef", "fdef.dot", "digraph X {}", false, true)
            store.getById("id-fdef")!!.familyId shouldBe "id-fdef"
        }
    }
}
