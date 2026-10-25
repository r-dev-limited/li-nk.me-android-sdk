package nz.co.rdev.example

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.link.sdk.LinkMe
import me.link.sdk.LinkPayload
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

/** Public SDK adapter on Android's real Looper/Context/HTTP stack; no store or association claim. */
class SDKLifecycleRuntimeTest {
    private val sdk = LinkMe.shared
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = mutableListOf<LoopbackEndpoint>()

    @Before fun reset() { sdk.dispose() }
    @After fun cleanup() { sdk.dispose(); fixtures.forEach { it.close() } }
    private fun endpoint(json: String, count: Int = 1, stallBody: Boolean = false) =
        LoopbackEndpoint(json, count, stallBody).also { fixtures.add(it) }
    private fun configure(server: LoopbackEndpoint, app: String = "runtime-a", timeout: Long = 1_500) {
        sdk.configure(context, LinkMe.Config(server.baseUrl, appId = app, appKey = "key-$app", sendDeviceInfo = false,
            includeVendorId = false, includeAdvertisingId = false, requestTimeoutMs = timeout))
    }
    private fun forward(cid: String) { sdk.handleIntent(Intent(Intent.ACTION_VIEW, Uri.parse("example://open?cid=$cid"))) }
    private fun initial(): LinkPayload? {
        val done = CountDownLatch(1)
        val result = AtomicReference<LinkPayload?>()
        sdk.getInitialLink {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            result.set(it); done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun preconfigureIntentUsesRealContextHeadersAndMainThreadDelivery() {
        val server = endpoint("""{"cid":"runtime-cid","path":"/runtime","linkId":"runtime-link"}""")
        val delivered = CountDownLatch(1)
        sdk.addListener {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            assertEquals("/runtime", it.path)
            delivered.countDown()
        }
        forward("runtime-cid")
        configure(server)
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals("/runtime", initial()?.path)
        assertEquals(1, server.requests.size)
        assertEquals("runtime-a", server.requests[0].headers["x-app-id"])
        assertEquals("key-runtime-a", server.requests[0].headers["x-api-key"])
        assertTrue(server.requests[0].target.contains("cid=runtime-cid"))
        server.assertHealthy()
    }

    @Test fun completeDeadlineClosesRealAndroidBodyReadWithoutPublishingAPayload() {
        val server = endpoint("""{"path":"/must-not-route"}""", stallBody = true)
        sdk.addListener { fail("Stalled body routed") }
        configure(server, timeout = 100)
        val started = System.nanoTime()
        forward("stalled-body")
        assertTrue(server.received.await(2, TimeUnit.SECONDS))
        assertTrue("Android transport did not disconnect", server.peerClosed.await(2, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
        assertNull(initial())
        server.assertHealthy()
    }

    @Test fun appSwitchCancelsOldBodyAndResetsPayloadIdentityAndTrackingScope() {
        val old = endpoint("""{"path":"/old-app","cid":"old-cid"}""", stallBody = true)
        val fresh = endpoint("""{"path":"/new-app","cid":"new-cid","linkId":"new-link"}""", count = 2)
        configure(old)
        sdk.setUserId("old-user")
        forward("old-cid")
        assertTrue(old.received.await(2, TimeUnit.SECONDS))
        configure(fresh, app = "runtime-b")
        assertTrue(old.peerClosed.await(2, TimeUnit.SECONDS))
        assertNull(initial())
        val delivered = CountDownLatch(1)
        sdk.addListener { assertEquals("/new-app", it.path); delivered.countDown() }
        forward("new-cid")
        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        sdk.track("new-event")
        assertTrue(fresh.completed.await(2, TimeUnit.SECONDS))
        assertEquals("/new-app", initial()?.path)
        for (request in fresh.requests) {
            assertEquals("runtime-b", request.headers["x-app-id"])
            assertEquals("key-runtime-b", request.headers["x-api-key"])
        }
        val event = JSONObject(fresh.requests.single { it.target.contains("/api/app-events") }.body)
        assertEquals("new-event", event.getString("type"))
        assertFalse(event.has("userId"))
        assertEquals("new-cid", event.getString("cid"))
        assertEquals("new-link", event.getString("linkId"))
        old.assertHealthy(); fresh.assertHealthy()
    }

    @Test fun reentrantAppChangeOnAndroidMainStopsRemainingListenersAndClearsInitialPayload() {
        val server = endpoint("""{"path":"/first"}""")
        configure(server)
        val changed = CountDownLatch(1)
        sdk.addListener {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            configure(server, app = "runtime-b")
            changed.countDown()
        }
        sdk.addListener { fail("Later listener received revoked generation") }
        forward("reentrant")
        assertTrue(changed.await(2, TimeUnit.SECONDS))
        assertNull(initial())
        server.assertHealthy()
    }

    private class LoopbackEndpoint(private val json: String, private val count: Int, private val stallBody: Boolean) : AutoCloseable {
        data class Request(val target: String, val headers: Map<String, String>, val body: String)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val received = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val completed = CountDownLatch(count)
        private val failure = AtomicReference<Throwable?>()
        @Volatile private var closing = false
        @Volatile private var accepted: Socket? = null
        private val worker = Thread({
            try {
                repeat(count) {
                    server.accept().use { socket ->
                        accepted = socket
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream().buffered()
                        val target = line(input)
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val raw = line(input)
                            if (raw.isEmpty()) break
                            headers[raw.substringBefore(':').lowercase()] = raw.substringAfter(':').trim()
                        }
                        val body = if (headers["transfer-encoding"] == "chunked") {
                            val bytes = ByteArrayOutputStream()
                            while (true) {
                                val size = line(input).substringBefore(';').toInt(16)
                                if (size == 0) { line(input); break }
                                bytes.write(exact(input, size)); line(input)
                            }
                            bytes.toString("UTF-8")
                        } else String(exact(input, headers["content-length"]?.toInt() ?: 0), Charsets.UTF_8)
                        requests.add(Request(target, headers, body))
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (stallBody) {
                            output.write(bytes, 0, 2)
                            output.flush()
                            received.countDown()
                            try {
                                while (input.read() != -1) { }
                                peerClosed.countDown()
                            } catch (error: SocketTimeoutException) { throw error }
                            catch (_: java.io.IOException) { peerClosed.countDown() }
                        } else {
                            output.write(bytes); output.flush(); received.countDown()
                        }
                        completed.countDown()
                    }
                    accepted = null
                }
            } catch (error: Throwable) { if (!closing) failure.set(error) }
        }, "linkme-owned-http").apply { isDaemon = true; start() }
        private fun line(input: InputStream): String {
            val result = ByteArrayOutputStream()
            while (result.size() < 8_192) {
                val byte = input.read()
                if (byte < 0 || byte == 10) return result.toString("UTF-8").trimEnd('\r')
                result.write(byte)
            }
            throw AssertionError("Fixture header too large")
        }
        private fun exact(input: InputStream, size: Int): ByteArray {
            require(size in 0..32_768)
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(bytes, offset, size - offset)
                if (read < 0) throw AssertionError("Early fixture EOF")
                offset += read
            }
            return bytes
        }
        fun assertHealthy() { assertNull(failure.get()) }
        override fun close() { closing = true; accepted?.close(); server.close(); worker.join(1_000) }
    }
}
