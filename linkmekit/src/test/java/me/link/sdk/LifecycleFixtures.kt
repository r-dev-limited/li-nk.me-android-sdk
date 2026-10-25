package me.link.sdk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class MainQueue {
    private val actions = LinkedBlockingQueue<() -> Unit>()
    fun post(action: () -> Unit) { actions.put(action) }
    fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AssertionError("SDK main callback timed out")
            val action = actions.poll(remaining, TimeUnit.NANOSECONDS) ?: throw AssertionError("No SDK main action")
            action()
        }
    }
    fun drain() { while (true) { val action = actions.poll() ?: return; action() } }
    fun next(timeoutMs: Long): (() -> Unit)? = actions.poll(timeoutMs, TimeUnit.MILLISECONDS)
}

internal class TestConnection(url: URL, val json: String = "{}", private val status: Int = 200,
                              private val stalledHeaders: Boolean = false, private val stalledBody: Boolean = false) : HttpURLConnection(url) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val disconnects = AtomicInteger()
    val written = ByteArrayOutputStream()
    override fun connect() { }
    override fun usingProxy() = false
    override fun disconnect() { disconnects.incrementAndGet(); disconnected.countDown() }
    override fun getOutputStream() = written
    override fun getResponseCode(): Int {
        if (stalledHeaders) { entered.countDown(); awaitIgnoringInterrupts(release) }
        return status
    }
    override fun getInputStream(): InputStream = responseStream()
    override fun getErrorStream(): InputStream = responseStream()
    private fun responseStream(): InputStream {
        val stream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        return object : InputStream() {
            override fun read(): Int {
                if (stalledBody) { entered.countDown(); awaitIgnoringInterrupts(release) }
                return stream.read()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (stalledBody) { entered.countDown(); awaitIgnoringInterrupts(release) }
                return stream.read(buffer, offset, length)
            }
        }
    }
}

internal class TestReferrer(private val result: String? = null, private val ready: Boolean = true,
                            private val stalled: Boolean = false) : ReferrerConnection {
    val connected = CountDownLatch(1)
    val closed = CountDownLatch(1)
    override fun connect(ready: (Boolean) -> Unit) { connected.countDown(); if (!stalled) ready(this.ready) }
    override fun read(): String? = result
    override fun close() { closed.countDown() }
}
