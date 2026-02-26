package attractor.engine

import kotlin.math.min
import kotlin.random.Random

data class BackoffConfig(
    val initialDelayMs: Long = 200L,
    val backoffFactor: Double = 2.0,
    val maxDelayMs: Long = 60_000L,
    val jitter: Boolean = true
) {
    fun delayForAttempt(attempt: Int): Long {
        // attempt is 1-indexed; first retry is attempt=1
        val base = initialDelayMs * Math.pow(backoffFactor, (attempt - 1).toDouble())
        var delay = min(base.toLong(), maxDelayMs)
        if (jitter) {
            delay = (delay * Random.nextDouble(0.5, 1.5)).toLong()
        }
        return delay
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 1, // 1 means no retries
    val backoff: BackoffConfig = BackoffConfig(),
    val shouldRetry: (Exception) -> Boolean = { true }
) {
    companion object {
        val NONE = RetryPolicy(maxAttempts = 1)
        val STANDARD = RetryPolicy(maxAttempts = 5, backoff = BackoffConfig(200, 2.0, 60_000))
        val AGGRESSIVE = RetryPolicy(maxAttempts = 5, backoff = BackoffConfig(500, 2.0, 60_000))
        val LINEAR = RetryPolicy(maxAttempts = 3, backoff = BackoffConfig(500, 1.0, 60_000, jitter = false))
        val PATIENT = RetryPolicy(maxAttempts = 3, backoff = BackoffConfig(2000, 3.0, 60_000))

        fun fromNode(node: attractor.dot.DotNode, graphDefaultMaxRetry: Int): RetryPolicy {
            // Use node's explicit max_retries if set; otherwise fall back to graph default (Section 3.5)
            val effectiveRetries = if (node.attrs.containsKey("max_retries")) {
                node.maxRetries
            } else {
                graphDefaultMaxRetry
            }
            val maxAttempts = maxOf(1, effectiveRetries + 1)
            return RetryPolicy(maxAttempts = maxAttempts)
        }
    }
}
