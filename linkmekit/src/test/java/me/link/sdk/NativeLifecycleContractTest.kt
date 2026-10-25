package me.link.sdk

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NativeLifecycleContractTest {
    private val main = MainQueue()
    private val scope = RequestScope()
    private val connections = CopyOnWriteArrayList<TestConnection>()
    private var stall = false
    private val sdk = LinkMe.create(LinkMe.Dependencies(scope = scope, postToMain = main::post,
        openConnection = { url -> TestConnection(url,
            """{"path":"/ready","cid":"cid-ready","ownerId":"spoofed","clientGeneration":999}""",
            stalledBody = stall).also { connections.add(it) } },
        openBrowser = { fail("Unexpected browser open"); false },
        referrerFactory = { TestReferrer(ready = false) }, packageName = "test.bundle"))

    private fun args(action: String, generation: Long = 1, owner: String = "owner-a",
                     extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mutableMapOf<String, Any?>("contractVersion" to 1, "ownerId" to owner,
            "clientGeneration" to generation, "action" to action).apply {
            if (action == "configure") putAll(mapOf("baseUrl" to "https://edge.example.test", "appId" to "app-a",
                "appKey" to "key-a", "sendDeviceInfo" to false, "includeVendorId" to false, "requestTimeoutMs" to 1_000))
            putAll(extra)
        }

    @Suppress("UNCHECKED_CAST")
    private fun call(command: Map<String, Any?>): Map<String, Any?> =
        sdk.javaClass.getMethod("linkmeLifecycle", Map::class.java, Context::class.java)
            .invoke(sdk, command, null) as Map<String, Any?>

    private fun read(command: Map<String, Any?>, callback: (Map<String, Any?>) -> Unit) {
        sdk.javaClass.getMethod("linkmeLifecycleRead", Map::class.java, Function1::class.java)
            .invoke(sdk, command, callback)
    }

    private fun connection(index: Int): TestConnection {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (connections.size <= index && System.nanoTime() < deadline) main.next(10)?.invoke()
        assertTrue("Missing HTTP request", connections.size > index)
        return connections[index].also { assertTrue(it.entered.await(1, TimeUnit.SECONDS)) }
    }

    @After fun cleanup() {
        connections.forEach { it.release.countDown() }
        sdk.dispose()
        scope.close()
        main.drain()
    }

    @Test fun reflectionContractValidatesBeforeMutationAndConfigureReplayDoesNotCancelWork() {
        assertEquals(1, sdk.javaClass.getMethod("linkmeLifecycleVersion").invoke(sdk))
        assertEquals(true, call(args("configure"))["ok"])
        stall = true
        var result: Map<String, Any?>? = null
        read(args("claimDeferredIfAvailable")) { result = it }
        val pending = connection(0)
        assertEquals(true, call(args("configure"))["duplicate"])
        assertEquals("generation_conflict", call(args("configure", extra = mapOf("appId" to "other")))["error"])
        for (invalid in listOf(0, -1, 1.5, "15", true)) {
            assertEquals("invalid_args", call(args("configure", 2, extra = mapOf("requestTimeoutMs" to invalid)))["error"])
        }
        assertEquals("invalid_args", call(args("configure", 2, extra = mapOf("sendDeviceInfo" to 1)))["error"])
        assertEquals(0, pending.disconnects.get())
        assertEquals(1_000, pending.readTimeout)
        pending.release.countDown()
        main.pumpUntil { result != null }
        val frame = requireNotNull(result)
        assertEquals(true, frame["ok"])
        assertEquals("owner-a", frame["ownerId"])
        assertEquals(1L, frame["clientGeneration"])
        assertFalse(pending.url.toString().contains("owner-a"))
        assertFalse(pending.requestProperties.keys.any { it.contains("generation", true) || it.contains("owner", true) })
        assertFalse(pending.written.toString("UTF-8").contains("owner-a"))
    }

    @Test fun cancelledReadCannotCrossGenerationAndDuplicateCancelPreservesNewRead() {
        call(args("configure"))
        stall = true
        var old: Map<String, Any?>? = null
        read(args("claimDeferredIfAvailable")) { old = it }
        val first = connection(0)
        assertEquals(true, call(args("cancelPendingRequests", 2))["ok"])
        main.pumpUntil { old != null }
        val rejected = requireNotNull(old)
        assertEquals("owner_changed", rejected["error"])
        assertFalse(rejected.containsKey("payload"))
        assertTrue(first.disconnected.await(1, TimeUnit.SECONDS))
        var fresh: Map<String, Any?>? = null
        read(args("claimDeferredIfAvailable", 2)) { fresh = it }
        val second = connection(1)
        assertEquals(true, call(args("cancelPendingRequests", 2))["duplicate"])
        assertEquals(0, second.disconnects.get())
        first.release.countDown()
        second.release.countDown()
        main.pumpUntil { fresh != null }
        val frame = requireNotNull(fresh)
        assertEquals(true, frame["ok"])
        assertEquals("owner-a", frame["ownerId"])
        assertEquals(2L, frame["clientGeneration"])
        assertEquals("/ready", (frame["payload"] as Map<*, *>)["path"])
    }

    @Test fun listenerFramesKeepPublicationGenerationAndUnsubscribeIsIdempotent() {
        call(args("configure"))
        val frames = mutableListOf<Map<String, Any?>>()
        val listener: (Map<String, Any?>) -> Unit = { frames.add(it) }
        val token = sdk.javaClass.getMethod("linkmeLifecycleListen", Function1::class.java).invoke(sdk, listener) as String
        sdk.handleUrl("https://links.example.test/?cid=first")
        main.pumpUntil { frames.size == 1 }
        call(args("cancelPendingRequests", 2))
        sdk.handleUrl("https://links.example.test/?cid=second")
        main.pumpUntil { frames.size == 2 }
        assertEquals(1L, frames[0]["clientGeneration"])
        assertEquals(2L, frames[1]["clientGeneration"])
        repeat(2) { sdk.javaClass.getMethod("linkmeLifecycleUnlisten", String::class.java).invoke(sdk, token) }
        sdk.handleUrl("https://links.example.test/?cid=third")
        val completion = main.next(1_000)
        assertNotNull(completion)
        completion!!()
        main.drain()
        assertEquals(2, frames.size)
    }

    @Test fun foreignOwnerAndDisposedTombstonesCannotRestoreOrReadOldContext() {
        call(args("configure"))
        assertEquals("owner_conflict", call(args("configure", owner = "owner-b"))["error"])
        assertEquals("owner_changed", call(args("cancelPendingRequests", owner = "owner-b"))["error"])
        var foreign: Map<String, Any?>? = null
        read(args("getInitialLink", owner = "owner-b")) { foreign = it }
        main.pumpUntil { foreign != null }
        val rejected = requireNotNull(foreign)
        assertEquals("owner_changed", rejected["error"])
        assertFalse(rejected.containsKey("payload"))
        assertEquals(true, call(args("setUserId", 2, extra = mapOf("userId" to "user-a")))["ok"])
        assertEquals(true, call(args("setUserId", 2, extra = mapOf("userId" to "user-a")))["duplicate"])
        assertEquals("generation_conflict", call(args("setUserId", 2, extra = mapOf("userId" to "user-b")))["error"])
        assertEquals(true, call(args("dispose", 3))["ok"])
        assertEquals(true, call(args("dispose", 3))["duplicate"])
        assertEquals("stale_generation", call(args("configure", 1))["error"])
        assertEquals(true, call(args("configure", owner = "owner-b"))["ok"])
        call(args("dispose", 2, owner = "owner-b"))
        assertEquals("stale_generation", call(args("configure", 2))["error"])
        assertEquals(true, call(args("configure", 4))["ok"])
    }

    @Test fun invalidHeadersAndDirectNativeChangesCannotKeepWrapperOwnership() {
        for (bad in listOf(mapOf("clientGeneration" to true), mapOf("clientGeneration" to 1.5),
            mapOf("clientGeneration" to -1), mapOf("clientGeneration" to 9_007_199_254_740_992L),
            mapOf("ownerId" to ""), mapOf("ownerId" to "x".repeat(129)), mapOf("contractVersion" to 2))) {
            assertEquals("invalid_args", call(args("configure", extra = bad))["error"])
        }
        call(args("configure"))
        sdk.setUserId("native-user")
        assertEquals("owner_changed", call(args("track", extra = mapOf("event" to "test")))["error"])
        assertEquals(true, call(args("configure", 2))["ok"])
        assertEquals("invalid_args", call(args("setUserId", 3, extra = mapOf("userId" to " ")))["error"])
        assertEquals("invalid_args", call(args("setAdvertisingConsent", 3, extra = mapOf("granted" to 1)))["error"])
        assertEquals(true, call(args("setAdvertisingConsent", 3, extra = mapOf("granted" to false)))["ok"])
        assertEquals(true, call(args("setReady", 3))["ok"])
        assertEquals("stale_generation", call(args("track", 2, extra = mapOf("event" to "test")))["error"])
    }

    @Test fun advancingSameValueIdentityOrConsentInvalidatesEarlierWork() {
        call(args("configure"))
        stall = true
        for ((index, command) in listOf(
            args("setUserId", 2, extra = mapOf("userId" to null)),
            args("setAdvertisingConsent", 3, extra = mapOf("granted" to false)),
        ).withIndex()) {
            var result: Map<String, Any?>? = null
            read(args("claimDeferredIfAvailable", index + 1L)) { result = it }
            val pending = connection(index)
            assertEquals(true, call(command)["ok"])
            main.pumpUntil { result != null }
            assertEquals("owner_changed", requireNotNull(result)["error"])
            assertTrue(pending.disconnected.await(1, TimeUnit.SECONDS))
            pending.release.countDown()
        }
    }
}
