package me.link.sdk

import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LinkMeLifecycleTest {
    private data class Harness(val sdk: LinkMe, val main: MainQueue, val scope: RequestScope)
    private val harnesses = mutableListOf<Harness>()
    private val connections = CopyOnWriteArrayList<TestConnection>()
    private fun config(app: String = "app-a", timeout: Long = 2_000, device: Boolean = false) =
        LinkMe.Config("https://edge.example.test", appId = app, appKey = "key-$app", sendDeviceInfo = device, requestTimeoutMs = timeout)
    private fun client(referrer: TestReferrer = TestReferrer(ready = false), discoveryMs: Long = 6_000,
                       open: (String) -> Boolean = { fail("Unexpected browser open"); false },
                       device: (() -> Map<String, Any>?)? = null,
                       connection: (URL) -> TestConnection = { TestConnection(it, """{"path":"/ready","cid":"cid-ready"}""") }): Harness {
        val main = MainQueue()
        val scope = RequestScope()
        val sdk = LinkMe.create(LinkMe.Dependencies(scope = scope, postToMain = main::post,
            openConnection = { url -> connection(url).also { connections.add(it) } }, openBrowser = open,
            referrerFactory = { referrer }, deviceProvider = device, packageName = "test.bundle", referrerTimeoutMs = discoveryMs))
        return Harness(sdk, main, scope).also { harnesses.add(it) }
    }
    @After fun cleanup() {
        connections.forEach { it.release.countDown() }
        harnesses.forEach { it.sdk.dispose(); it.scope.close(); it.main.drain() }
    }

    @Test fun configHasFifteenSecondDefaultAndRejectsInvalidOverrides() {
        assertEquals(15_000, LinkMe.Config("https://edge.example").requestTimeoutMs)
        for (timeout in listOf(0L, -1, Int.MAX_VALUE.toLong() + 1)) {
            try { config(timeout = timeout); fail("Invalid deadline accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun stalledHeadersAndBodiesMeetTotalDeadlineAndDisconnectEvenWhenIoIgnoresInterrupt() {
        for (body in listOf(false, true)) {
            val h = client(connection = { TestConnection(it, stalledHeaders = !body, stalledBody = body) })
            h.sdk.configureCore(config(timeout = 50))
            var completed = false
            h.sdk.claimDeferredCore { assertNull(it); completed = true }
            h.main.pumpUntil { completed }
            assertEquals(1, connections.size)
            val connection = connections[0]
            assertTrue(connection.entered.await(1, TimeUnit.SECONDS))
            assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
            connection.release.countDown()
            h.sdk.dispose()
            h.main.drain()
            connections.clear()
        }
    }

    @Test fun lifecycleChangesInvalidatePendingBodiesAndBrowserLaunches() {
        for (change in listOf("configure", "identity", "dispose", "cancel")) {
            val h = client(connection = { TestConnection(it, """{"path":"/old","forceRedirectWeb":true,"webFallbackUrl":"https://must-not-open.example"}""", stalledBody = true) })
            h.sdk.configureCore(config())
            var routed = 0
            h.sdk.addListener { routed += 1 }
            h.sdk.handleUrl("https://links.example.test/?cid=pending1")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (connections.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, connections.size)
            val connection = connections[0]
            assertTrue(connection.entered.await(1, TimeUnit.SECONDS))
            when (change) {
                "configure" -> h.sdk.configureCore(config("app-b"))
                "identity" -> h.sdk.setUserId("new-user")
                "dispose" -> h.sdk.dispose()
                else -> h.sdk.cancelPendingRequests()
            }
            val action = h.main.next(1_000)
            assertNotNull(action)
            action!!()
            connection.release.countDown()
            assertTrue(connection.disconnected.await(1, TimeUnit.SECONDS))
            var initialDone = false
            h.sdk.getInitialLink { assertNull(it); initialDone = true }
            h.main.pumpUntil { initialDone }
            assertEquals(0, routed)
            connections.clear()
        }
    }

    @Test fun completedButQueuedResponseCannotCrossAnAppSwitch() {
        val h = client()
        h.sdk.configureCore(config())
        h.sdk.addListener { fail("Stale payload delivered") }
        h.sdk.handleUrl("https://links.example.test/?cid=finished")
        val queued = h.main.next(1_000)
        assertNotNull(queued)
        h.sdk.configureCore(config("app-b"))
        queued!!()
        var checked = false
        h.sdk.getInitialLink { assertNull(it); checked = true }
        h.main.pumpUntil { checked }
    }

    @Test fun aValidReferrerHttpClaimOutlivesDiscoveryBudgetWithoutStartingFingerprintFallback() {
        val referrer = TestReferrer("utm_source=play&cid=referrer1")
        val h = client(referrer, discoveryMs = 30, connection = {
            assertEquals("/api/install-referrer", it.path)
            TestConnection(it, """{"path":"/referrer","claimMethod":"install_referrer"}""", stalledBody = true)
        })
        h.sdk.configureCore(config(timeout = 1_000))
        var result: LinkPayload? = null
        var completed = false
        h.sdk.claimDeferredCore { result = it; completed = true }
        val lookup = h.main.next(1_000)
        assertNotNull(lookup)
        lookup!!()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (connections.isEmpty() && System.nanoTime() < deadline) Thread.yield()
        assertEquals(1, connections.size)
        assertTrue(connections[0].entered.await(1, TimeUnit.SECONDS))
        assertNull("Discovery timer must be removed before HTTP", h.main.next(80))
        assertEquals(1, connections.size)
        connections[0].release.countDown()
        h.main.pumpUntil { completed }
        assertEquals("install_referrer", result?.claimMethod)
        assertEquals("/referrer", result?.path)
        assertTrue(referrer.closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun discoveryTimeoutClosesServiceAndFallbackReturnsAttributionWithoutRouting() {
        val referrer = TestReferrer(stalled = true)
        val h = client(referrer, discoveryMs = 30, connection = {
            assertEquals("/api/deferred/claim", it.path)
            TestConnection(it, """{"navigationAllowed":false,"claimId":"inferred","path":"/must-not-route","forceRedirectWeb":true,"webFallbackUrl":"https://must-not-open.example"}""")
        })
        h.sdk.configureCore(config())
        h.sdk.addListener { fail("Metadata routed") }
        var completed = false
        h.sdk.claimDeferredCore { assertNull(it); completed = true }
        h.main.pumpUntil { completed }
        assertEquals(1, connections.size)
        assertTrue(referrer.closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun invalidReferrerFallsBackOnceAndKeepsDeterministicRequestFirst() {
        val h = client(TestReferrer("cid=old"), connection = {
            if (it.path == "/api/install-referrer") TestConnection(it, "{}", status = 404)
            else TestConnection(it, """{"contractVersion":1,"navigationAllowed":false,"claimId":"fallback","claimMethod":"fingerprint_exact","matchConfidence":"medium","attributionStatus":"inferred","duplicate":false,"claimPolicyVersion":2,"utm":{}}""")
        })
        h.sdk.configureCore(config())
        var completed = false
        h.sdk.claimDeferredCore { assertNull(it); completed = true }
        h.main.pumpUntil { completed }
        assertEquals(listOf("/api/install-referrer", "/api/deferred/claim"), connections.map { it.url.path })
    }

    @Test fun cancellingDiscoverySettlesOnceClosesServiceAndDoesNotStartFallback() {
        val referrer = TestReferrer(stalled = true)
        val h = client(referrer)
        h.sdk.configureCore(config())
        var calls = 0
        h.sdk.claimDeferredCore { assertNull(it); calls += 1 }
        assertTrue(referrer.connected.await(1, TimeUnit.SECONDS))
        h.sdk.cancelPendingRequests()
        h.main.pumpUntil { calls == 1 }
        assertTrue(referrer.closed.await(1, TimeUnit.SECONDS))
        assertTrue(connections.isEmpty())
        h.main.drain()
        assertEquals(1, calls)
    }

    @Test fun reentrantLifecycleChangeStopsLaterListenersAndClearsOldCompletionPayload() {
        for (change in listOf("configure", "identity", "dispose", "cancel")) {
            val h = client()
            h.sdk.configureCore(config())
            var first = 0
            h.sdk.addListener {
                when (change) {
                    "configure" -> h.sdk.configureCore(config("app-b"))
                    "identity" -> h.sdk.setUserId("new-user")
                    "dispose" -> h.sdk.dispose()
                    else -> h.sdk.cancelPendingRequests()
                }
                first += 1
            }
            h.sdk.addListener { fail("Later listener received old result") }
            var completed = false
            h.sdk.claimDeferredCore { assertNull(it); completed = true }
            h.main.pumpUntil { completed }
            assertEquals(1, first)
            connections.clear()
        }
    }

    @Test fun appSwitchClearsPayloadIdentityAndSendsOnlyNewCredentialsAndContext() {
        val h = client(connection = { TestConnection(it) })
        h.sdk.configureCore(config())
        h.sdk.setUserId("user-a")
        h.sdk.debugEmit(LinkPayload(cid = "old-cid", linkId = "old-link"))
        h.main.drain()
        h.sdk.configureCore(config("app-b"))
        h.sdk.track("new-event")
        val action = h.main.next(1_000)
        assertNotNull(action)
        action!!()
        val connection = connections.single()
        assertEquals("app-b", connection.getRequestProperty("x-app-id"))
        assertEquals("key-app-b", connection.getRequestProperty("x-api-key"))
        val body = JSONObject(connection.written.toString("UTF-8"))
        assertEquals("new-event", body.getString("type"))
        for (field in listOf("userId", "cid", "linkId")) assertFalse(body.has(field))
    }

    @Test fun cancelledOptionalReadCannotStartHttpUsingAnOldAppSnapshot() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = AtomicInteger()
        val h = client(device = { started.countDown(); awaitIgnoringInterrupts(release); mapOf("platform" to "android") },
            connection = { opened.incrementAndGet(); TestConnection(it) })
        h.sdk.configureCore(config(device = true))
        h.sdk.handleUrl("https://links.example.test/product")
        assertTrue(started.await(1, TimeUnit.SECONDS))
        h.sdk.configureCore(config("app-b"))
        release.countDown()
        val action = h.main.next(1_000)
        assertNotNull(action)
        action!!()
        assertEquals(0, opened.get())
    }

    @Test fun listenerRemovalUsesSubscriptionIdentityAndColdUrlHasExplicitReplayOnly() {
        val h = client()
        var received = 0
        val listener: (LinkPayload) -> Unit = { received += 1 }
        val removeFirst = h.sdk.addListener(listener)
        val removeSecond = h.sdk.addListener(listener)
        removeFirst(); removeFirst()
        h.sdk.handleUrl("https://links.example.test/?cid=cold1234")
        h.sdk.configureCore(config())
        h.main.pumpUntil { received == 1 }
        removeSecond(); removeSecond()
        h.sdk.addListener { fail("Listener must not replay automatically") }
        var checked = false
        h.sdk.getInitialLink { assertEquals("/ready", it?.path); checked = true }
        h.main.pumpUntil { checked }
        assertEquals(1, connections.size)
    }

    @Test fun aThrowingListenerDoesNotBlockOtherListenersOrClaimCompletion() {
        val h = client()
        h.sdk.configureCore(config())
        var routed = 0
        h.sdk.addListener { throw IllegalStateException("Host listener failure") }
        h.sdk.addListener { routed += 1 }
        var completed = false
        h.sdk.claimDeferredCore { assertEquals("/ready", it?.path); completed = true }
        h.main.pumpUntil { completed }
        assertEquals(1, routed)
    }

    @Test fun unknownDomainFallbackPreservesUnicodeQueryDecodingAndFirstDuplicateValue() {
        val h = client(connection = { TestConnection(it, """{"error":"domain_not_found"}""", status = 404) })
        h.sdk.configureCore(config())
        var result: LinkPayload? = null
        h.sdk.addListener { result = it }
        h.sdk.handleUrl("https://other.example/welcome/%E6%98%A5?utm_source=mail+campaign&x=one%2Btwo&x=ignored")
        h.main.pumpUntil { result != null }
        assertEquals("/welcome/春", result?.path)
        assertEquals("mail campaign", result?.utm?.get("utm_source"))
        assertEquals("one+two", result?.params?.get("x"))
        assertEquals(false, result?.isLinkMe)
    }

    @Test fun forcedWebLaunchesOnPostedMainOnlyOnceAndSuppressesRoutingAndPayload() {
        var opens = 0
        val mainThread = Thread.currentThread()
        val h = client(open = { assertSame(mainThread, Thread.currentThread()); assertEquals("https://browser.example/destination", it); opens += 1; true },
            connection = { TestConnection(it, """{"forceRedirectWeb":true,"webFallbackUrl":"https://browser.example/destination"}""") })
        h.sdk.configureCore(config())
        h.sdk.addListener { fail("Forced web routed to app") }
        var completed = false
        h.sdk.claimDeferredCore { assertNull(it); completed = true }
        h.main.pumpUntil { completed }
        h.main.drain()
        assertEquals(1, opens)
    }
}
