package attractor.state

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class Context {
    private val values: MutableMap<String, Any> = mutableMapOf()
    private val lock = ReentrantReadWriteLock()
    private val _logs: MutableList<String> = mutableListOf()

    fun set(key: String, value: Any) {
        lock.write { values[key] = value }
    }

    fun get(key: String, default: Any? = null): Any? =
        lock.read { values[key] ?: default }

    fun getString(key: String, default: String = ""): String =
        lock.read { values[key]?.toString() ?: default }

    fun getInt(key: String, default: Int = 0): Int =
        lock.read {
            when (val v = values[key]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: default
                else -> default
            }
        }

    fun contains(key: String): Boolean = lock.read { values.containsKey(key) }

    fun remove(key: String) { lock.write { values.remove(key) } }

    fun appendLog(entry: String) {
        lock.write { _logs.add(entry) }
    }

    fun snapshot(): Map<String, Any> = lock.read { values.toMap() }

    fun logs(): List<String> = lock.read { _logs.toList() }

    fun clone(): Context {
        val newCtx = Context()
        lock.read {
            newCtx.values.putAll(values)
            newCtx._logs.addAll(_logs)
        }
        return newCtx
    }

    fun applyUpdates(updates: Map<String, String>) {
        lock.write {
            for ((k, v) in updates) values[k] = v
        }
    }

    fun applyAnyUpdates(updates: Map<String, Any>) {
        lock.write {
            for ((k, v) in updates) values[k] = v
        }
    }

    fun incrementInt(key: String, by: Int = 1): Int {
        return lock.write {
            val current = when (val v = values[key]) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: 0
                else -> 0
            }
            val next = current + by
            values[key] = next
            next
        }
    }
}
