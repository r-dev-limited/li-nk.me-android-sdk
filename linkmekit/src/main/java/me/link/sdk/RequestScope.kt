package me.link.sdk

import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Complete-operation deadlines include queued work, connection and body I/O. */
internal class RequestScope : AutoCloseable {
    internal class Cancellation(private val cleanup: ExecutorService) {
        private val lock = Any()
        private var closed = false
        private val hooks = mutableListOf<() -> Unit>()

        fun check() { synchronized(lock) { if (closed) throw CancellationException("LinkMe request cancelled") } }
        fun onCleanup(hook: () -> Unit) {
            val runNow = synchronized(lock) { if (closed) true else { hooks.add(hook); false } }
            if (runNow) { schedule(hook); throw CancellationException("LinkMe request cancelled") }
        }
        fun close() {
            val work = synchronized(lock) {
                if (closed) return
                closed = true
                hooks.toList().also { hooks.clear() }
            }
            work.forEach { schedule(it) }
        }
        private fun schedule(hook: () -> Unit) {
            // Disconnect/IPC close can itself block. Never run it on the deadline
            // scheduler or delay the caller's once-only completion behind it.
            try { cleanup.execute { runCatching(hook) } } catch (_: RejectedExecutionException) { }
        }
    }

    private class Job(
        val cancellation: Cancellation,
        val complete: (Any?, Throwable?) -> Unit,
        var worker: Future<*>? = null,
        var deadline: ScheduledFuture<*>? = null,
    )

    private val lock = Any()
    private val jobs = mutableMapOf<UUID, Job>()
    private val workers = pool(4, 64, "linkme-http")
    private val cleanup = pool(2, 16, "linkme-close")
    private val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "linkme-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun <T> submit(timeoutMs: Long, operation: (Cancellation) -> T, complete: (T?, Throwable?) -> Unit) {
        require(timeoutMs in 1..Int.MAX_VALUE.toLong())
        val id = UUID.randomUUID()
        val cancellation = Cancellation(cleanup)
        val job = Job(cancellation, { value, error ->
            @Suppress("UNCHECKED_CAST")
            complete(value as T?, error)
        })
        var rejection: RejectedExecutionException? = null
        synchronized(lock) {
            jobs[id] = job
            try {
                job.deadline = deadlines.schedule({ finish(id, null, TimeoutException("LinkMe request deadline exceeded"), true) }, timeoutMs, TimeUnit.MILLISECONDS)
                job.worker = workers.submit {
                    try {
                        cancellation.check()
                        val value = operation(cancellation)
                        finish(id, value, null, false)
                    } catch (error: Throwable) {
                        finish(id, null, error, false)
                    }
                }
            } catch (error: RejectedExecutionException) {
                rejection = error
            }
        }
        rejection?.let { finish(id, null, it, true) }
    }

    private fun finish(id: UUID, value: Any?, error: Throwable?, cancel: Boolean) {
        val job = synchronized(lock) { jobs.remove(id) } ?: return
        job.deadline?.cancel(false)
        if (cancel) {
            job.worker?.cancel(true)
            (job.worker as? Runnable)?.let { workers.remove(it) }
        }
        job.cancellation.close()
        job.complete(value, error)
    }

    fun cancelAll() {
        val pending = synchronized(lock) { jobs.values.toList().also { jobs.clear() } }
        pending.forEach { job ->
            job.deadline?.cancel(false)
            job.worker?.cancel(true)
            (job.worker as? Runnable)?.let { workers.remove(it) }
            job.cancellation.close()
            job.complete(null, CancellationException("LinkMe request cancelled"))
        }
    }

    override fun close() {
        cancelAll()
        workers.shutdownNow()
        deadlines.shutdownNow()
        cleanup.shutdown()
    }

    private fun pool(size: Int, capacity: Int, name: String): ThreadPoolExecutor = ThreadPoolExecutor(
        size, size, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
}
