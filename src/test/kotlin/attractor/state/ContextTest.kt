package attractor.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextTest : FunSpec({

    test("set() + get() stores and retrieves value") {
        val ctx = Context()
        ctx.set("myKey", "myValue")
        ctx.get("myKey") shouldBe "myValue"
    }

    test("get() returns default when key is absent") {
        val ctx = Context()
        ctx.get("missing", "default") shouldBe "default"
        ctx.get("missing") shouldBe null
    }

    test("getInt() coerces String \"42\" to 42") {
        val ctx = Context()
        ctx.set("num", "42")
        ctx.getInt("num") shouldBe 42
    }

    test("getInt() returns default for non-numeric String") {
        val ctx = Context()
        ctx.set("str", "hello")
        ctx.getInt("str", default = -1) shouldBe -1
    }

    test("getInt() returns value for Number types") {
        val ctx = Context()
        ctx.set("int_val", 99)
        ctx.set("long_val", 100L)
        ctx.getInt("int_val") shouldBe 99
        ctx.getInt("long_val") shouldBe 100
    }

    test("incrementInt() starts from 0 and returns incremented value") {
        val ctx = Context()
        val v1 = ctx.incrementInt("counter")
        v1 shouldBe 1
        val v2 = ctx.incrementInt("counter")
        v2 shouldBe 2
    }

    test("clone() produces independent copy — mutations do not propagate to source") {
        val original = Context()
        original.set("shared", "original")

        val cloned = original.clone()
        cloned.set("shared", "modified")

        // Original should be unchanged
        original.getString("shared") shouldBe "original"
        cloned.getString("shared") shouldBe "modified"
    }

    test("snapshot() returns a copy — mutations to snapshot do not affect Context") {
        val ctx = Context()
        ctx.set("key", "before")

        val snap = ctx.snapshot()
        ctx.set("key", "after")

        // Snapshot captured the old value
        snap["key"] shouldBe "before"
        ctx.getString("key") shouldBe "after"
    }

    test("incrementInt() is thread-safe — 10 threads x 1000 increments = 10000") {
        val ctx = Context()
        val threads = 10
        val incrementsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)

        repeat(threads) {
            executor.submit {
                startLatch.await()
                repeat(incrementsPerThread) { ctx.incrementInt("counter") }
                doneLatch.countDown()
            }
        }

        startLatch.countDown()  // release all threads simultaneously
        doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        ctx.getInt("counter") shouldBe threads * incrementsPerThread
    }
})
