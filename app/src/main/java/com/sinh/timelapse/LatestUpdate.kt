package com.sinh.timelapse

import java.util.concurrent.Executor

/** One pending routine update. Never use for journals, reservations, errors or terminal transitions. */
internal class LatestUpdate<T : Any>(private val executor: Executor, private val consume: (T) -> Unit) {
    private val lock = Any()
    private var latest: T? = null
    private var scheduled = false
    fun offer(value: T) {
        val submit = synchronized(lock) {
            latest = value
            if (scheduled) false else { scheduled = true; true }
        }
        if (submit) enqueue()
    }
    private fun enqueue() {
        try { executor.execute {
            val value = synchronized(lock) { latest.also { latest = null } }
            try { if (value != null) consume(value) }
            finally {
                val again = synchronized(lock) {
                    if (latest == null) { scheduled = false; false } else true
                }
                if (again) enqueue()
            }
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            synchronized(lock) { scheduled = false; latest = null }
        }
    }
}
