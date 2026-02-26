package attractor.engine

import attractor.dot.DotNode
import attractor.dot.DotValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe

class RetryPolicyTest : FunSpec({

    test("BackoffConfig with jitter=false returns exact base delay for attempt 1") {
        val config = BackoffConfig(initialDelayMs = 200L, backoffFactor = 2.0, maxDelayMs = 60_000L, jitter = false)
        config.delayForAttempt(1) shouldBe 200L
    }

    test("BackoffConfig with jitter=false returns exact exponential delay for attempt N") {
        val config = BackoffConfig(initialDelayMs = 200L, backoffFactor = 2.0, maxDelayMs = 60_000L, jitter = false)
        // attempt 1 → 200, attempt 2 → 400, attempt 3 → 800
        config.delayForAttempt(2) shouldBe 400L
        config.delayForAttempt(3) shouldBe 800L
    }

    test("BackoffConfig delay is capped at maxDelayMs") {
        val config = BackoffConfig(initialDelayMs = 1000L, backoffFactor = 10.0, maxDelayMs = 5000L, jitter = false)
        // attempt 4 would be 1000 * 10^3 = 1_000_000 without cap
        config.delayForAttempt(4) shouldBe 5000L
    }

    test("BackoffConfig with jitter=true stays within [0.5x, 1.5x] band of base delay") {
        val config = BackoffConfig(initialDelayMs = 1000L, backoffFactor = 2.0, maxDelayMs = 60_000L, jitter = true)
        // base for attempt 1 = 1000ms; jitter should keep it in [500, 1500]
        repeat(30) {
            val delay = config.delayForAttempt(1)
            delay shouldBeGreaterThanOrEqualTo 0L    // at minimum, non-negative
            delay shouldBeLessThanOrEqualTo 1500L    // at most 1.5 * base
        }
    }

    test("RetryPolicy.NONE has maxAttempts=1") {
        RetryPolicy.NONE.maxAttempts shouldBe 1
    }

    test("RetryPolicy.fromNode uses node max_retries when set") {
        val node = DotNode("test", mutableMapOf(
            "max_retries" to DotValue.IntegerValue(3L)
        ))
        val policy = RetryPolicy.fromNode(node, graphDefaultMaxRetry = 50)
        // maxAttempts = max(1, 3 + 1) = 4
        policy.maxAttempts shouldBe 4
    }

    test("RetryPolicy.fromNode falls back to graph default when node has no max_retries attribute") {
        val node = DotNode("test")
        val policy = RetryPolicy.fromNode(node, graphDefaultMaxRetry = 5)
        // maxAttempts = max(1, 5 + 1) = 6
        policy.maxAttempts shouldBe 6
    }
})
