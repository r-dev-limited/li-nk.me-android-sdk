package me.link.sdk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import org.junit.Assert.*
import org.junit.Test

internal fun awaitIgnoringInterrupts(latch: CountDownLatch) {
    while (true) {
        try { if (latch.await(3, TimeUnit.SECONDS)) return else throw AssertionError("Fixture release timed out") }
        catch (_: InterruptedException) { }
    }
}

class RequestScopeTest {
    @Test fun deadlineSettlesDespiteIgnoredInterruptAndAllowsRecovery() {
        RequestScope().use { scope ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cleanup = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val calls = AtomicInteger()
            scope.submit(50, { cancellation ->
                cancellation.onCleanup { cleanup.countDown() }
                started.countDown()
                awaitIgnoringInterrupts(release)
                "late"
            }) { _, error -> assertTrue(error is TimeoutException); calls.incrementAndGet(); completed.countDown() }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            assertTrue(cleanup.await(1, TimeUnit.SECONDS))
            val recovered = CountDownLatch(1)
            scope.submit(500, { 42 }) { value, error -> assertNull(error); assertEquals(42, value); recovered.countDown() }
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
            release.countDown()
            scope.cancelAll()
            assertEquals(1, calls.get())
        }
    }

    @Test fun queuedWorkTimesOutBeforeAWorkerCanStartIt() {
        RequestScope().use { scope ->
            val occupied = CountDownLatch(4)
            val release = CountDownLatch(1)
            repeat(4) { scope.submit(2_000, { occupied.countDown(); awaitIgnoringInterrupts(release) }) { _, _ -> } }
            assertTrue(occupied.await(1, TimeUnit.SECONDS))
            val queuedRan = AtomicInteger()
            val completed = CountDownLatch(1)
            scope.submit(30, { queuedRan.incrementAndGet() }) { _, error -> assertTrue(error is TimeoutException); completed.countDown() }
            assertTrue(completed.await(1, TimeUnit.SECONDS))
            scope.cancelAll()
            val recovered = CountDownLatch(1)
            scope.submit(500, { 1 }) { _, error -> assertNull(error); recovered.countDown() }
            release.countDown()
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
            assertEquals(0, queuedRan.get())
        }
    }

    @Test fun pendingCancellationDoesNotCancelRecoveryStartedByItsCallback() {
        RequestScope().use { scope ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val recovered = CountDownLatch(1)
            scope.submit(2_000, { started.countDown(); awaitIgnoringInterrupts(release) }) { _, error ->
                assertTrue(error is CancellationException)
                scope.submit(500, { 7 }) { value, nextError -> assertNull(nextError); assertEquals(7, value); recovered.countDown() }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            scope.cancelAll()
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
            release.countDown()
        }
    }

    @Test fun concurrentCompletionCancellationAndDeadlinesSettleOnce() {
        RequestScope().use { scope ->
            val callers = Executors.newFixedThreadPool(4)
            val count = AtomicIntegerArray(100)
            val completed = CountDownLatch(100)
            try {
                repeat(100) { index ->
                    scope.submit(5, { Thread.yield(); index }) { _, _ -> count.incrementAndGet(index); completed.countDown() }
                    callers.submit { scope.cancelAll() }
                }
                callers.shutdown()
                assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS))
                assertTrue(completed.await(2, TimeUnit.SECONDS))
                scope.cancelAll()
                repeat(100) { assertEquals(1, count.get(it)) }
            } finally { callers.shutdownNow() }
        }
    }

    @Test fun blockingCleanupCannotPreventOtherRequestDeadlinesFromSettling() {
        RequestScope().use { scope ->
            val started = CountDownLatch(3)
            val release = CountDownLatch(1)
            val releaseCleanup = CountDownLatch(1)
            val completed = CountDownLatch(3)
            repeat(3) {
                scope.submit(50, { cancellation ->
                    cancellation.onCleanup { awaitIgnoringInterrupts(releaseCleanup) }
                    started.countDown()
                    awaitIgnoringInterrupts(release)
                }) { _, error -> assertTrue(error is TimeoutException); completed.countDown() }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            try { assertTrue(completed.await(1, TimeUnit.SECONDS)) }
            finally { release.countDown(); releaseCleanup.countDown() }
        }
    }

    @Test fun workerQueueRejectsOverloadWithoutAnUnboundedThreadOrJobBacklog() {
        RequestScope().use { scope ->
            val active = CountDownLatch(4)
            val release = CountDownLatch(1)
            repeat(4) { scope.submit(2_000, { active.countDown(); awaitIgnoringInterrupts(release) }) { _, _ -> } }
            assertTrue(active.await(1, TimeUnit.SECONDS))
            val queuedRuns = AtomicInteger()
            repeat(64) { scope.submit(2_000, { queuedRuns.incrementAndGet() }) { _, _ -> } }
            val rejected = CountDownLatch(1)
            scope.submit(2_000, { fail("Overload operation ran") }) { _, error -> assertTrue(error is RejectedExecutionException); rejected.countDown() }
            assertTrue(rejected.await(1, TimeUnit.SECONDS))
            scope.cancelAll()
            val recovered = CountDownLatch(1)
            scope.submit(500, { 1 }) { _, error -> assertNull(error); recovered.countDown() }
            release.countDown()
            assertTrue(recovered.await(1, TimeUnit.SECONDS))
            assertEquals(0, queuedRuns.get())
        }
    }
}
