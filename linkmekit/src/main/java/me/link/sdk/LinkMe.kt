package me.link.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class LinkPayload(
    val linkId: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
    val utm: Map<String, String>? = null,
    val custom: Map<String, String>? = null,
    val url: String? = null,
    val isLinkMe: Boolean? = null,
    val forceRedirectWeb: Boolean? = null,
    val webFallbackUrl: String? = null,
    val cid: String? = null,
    val duplicate: Boolean? = null,
    val claimId: String? = null,
    val claimMethod: String? = null,
    val matchConfidence: String? = null,
    val attributionStatus: String? = null,
    val contractVersion: Int? = null,
    val claimPolicyVersion: Int? = null,
    val navigationAllowed: Boolean? = null,

)

class LinkMe private constructor(private val dependencies: Dependencies) {
    internal class Dependencies(
        val scope: RequestScope = RequestScope(),
        val postToMain: ((() -> Unit) -> Unit)? = null,
        val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        val openBrowser: ((String) -> Boolean)? = null,
        val referrerFactory: ((Context?) -> ReferrerConnection)? = null,
        val deviceProvider: (() -> Map<String, Any>?)? = null,
        val packageName: String? = null,
        val referrerTimeoutMs: Long = 6_000,
    )
    private val utmKeys = setOf(
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_source_platform",
        "utm_creative_format",
        "utm_marketing_tactic",
        "tags"
    )

    data class Config(
        val baseUrl: String,
        val appId: String? = null,
        val appKey: String? = null,
        /**
         * @deprecated Pasteboard is iOS-only and now controlled from the Portal.
         * This parameter is ignored on Android.
         */
        @Deprecated("Pasteboard is iOS-only and now controlled from the Portal")
        val enablePasteboard: Boolean = false,
        val sendDeviceInfo: Boolean = true,
        val includeVendorId: Boolean = true,
        val includeAdvertisingId: Boolean = false,
        val debug: Boolean = false,
        /** Total HTTP deadline, including queue, connection and response body. */
        val requestTimeoutMs: Long = 15_000,
    ) {
        init { require(requestTimeoutMs in 1..Int.MAX_VALUE.toLong()) { "requestTimeoutMs must be between 1 and 2147483647" } }
    }

    private val stateLock = Any()
    private var generation = 0L
    private var lifecycleOwner: String? = null
    private var lifecycleClientGeneration = 0L
    private val lifecycleKnownGenerations = linkedMapOf<String, Long>()
    private var lifecycleLastCommand: Triple<String, Long, String>? = null
    private val lifecycleSubscriptions = mutableMapOf<String, () -> Unit>()
    private var config: Config? = null
    private var userId: String? = null
    private val listeners = linkedMapOf<UUID, (LinkPayload) -> Unit>()
    private var lastPayload: LinkPayload? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingUrls = mutableListOf<String>()
    private var advertisingConsentEnabled = false
    private var appContext: Context? = null

    private data class Snapshot(
        val generation: Long,
        val config: Config,
        val context: Context?,
        val advertisingConsent: Boolean,
        val userId: String?,
        val payload: LinkPayload?,
    )
    private data class HttpResult(val status: Int, val body: String)

    private fun snapshot(): Snapshot? = config?.let { Snapshot(generation, it, appContext, advertisingConsentEnabled, userId, lastPayload) }
    private fun current(expected: Long): Boolean = generation == expected && config != null
    private fun post(action: () -> Unit) {
        val injected = dependencies.postToMain
        if (injected != null) injected(action) else mainHandler.post { action() }
    }
    private fun invalidate() { generation += 1; dependencies.scope.cancelAll() }

    fun configure(context: Context, config: Config) = configureCore(config, context.applicationContext ?: context)

    /** Shared installation path; the platform adapter supplies applicationContext. */
    internal fun configureCore(config: Config, context: Context? = null) {
        synchronized(stateLock) {
            val hadConfig = this.config != null
            invalidate()
            resetLifecycleOwner()
            if (hadConfig) pendingUrls.clear()
            this.config = config
            appContext = context
            userId = null
            lastPayload = null
            advertisingConsentEnabled = config.includeAdvertisingId
            val pending = pendingUrls.toList()
            pendingUrls.clear()
            pending.forEach { processUrl(it, snapshot()!!) }
        }
    }

    fun cancelPendingRequests() { synchronized(stateLock) { invalidate(); resetLifecycleOwner() } }

    fun dispose() {
        synchronized(stateLock) {
            invalidate()
            config = null
            appContext = null
            userId = null
            lastPayload = null
            advertisingConsentEnabled = false
            pendingUrls.clear()
            listeners.clear()
            lifecycleSubscriptions.clear()
            resetLifecycleOwner()
        }
    }

    fun getInitialLink(callback: (LinkPayload?) -> Unit) {
        synchronized(stateLock) {
            val expected = generation
            post { synchronized(stateLock) { callback(if (current(expected)) lastPayload else null) } }
        }
    }

    fun addListener(handler: (LinkPayload) -> Unit): () -> Unit {
        val id = UUID.randomUUID()
        synchronized(stateLock) { listeners[id] = handler }
        return { synchronized(stateLock) { listeners.remove(id); Unit } }
    }

    fun debugEmit(payload: LinkPayload) {
        synchronized(stateLock) {
            val expected = generation
            post { synchronized(stateLock) { emit(payload, expected) } }
        }
    }

    fun setAdvertisingConsent(granted: Boolean) {
        synchronized(stateLock) { if (advertisingConsentEnabled != granted) { invalidate(); resetLifecycleOwner() }; advertisingConsentEnabled = granted }
    }
    fun setUserId(id: String?) { synchronized(stateLock) { if (userId != id) { invalidate(); resetLifecycleOwner() }; userId = id } }

    companion object {
        private const val TAG = "LinkMe"
        @JvmStatic val shared: LinkMe by lazy { LinkMe(Dependencies()) }
        internal fun create(dependencies: Dependencies): LinkMe = LinkMe(dependencies)
    }

    private fun debugLog(message: String, error: Throwable? = null) {
        if (config?.debug == true) Log.d(TAG, "[LinkMe] $message", error)
    }

    fun onNewIntent(intent: Intent?) { intent?.data?.toString()?.let { handleUrl(it) } }
    fun handleIntent(intent: Intent?) { onNewIntent(intent) }

    internal fun handleUrl(url: String) {
        synchronized(stateLock) {
            val state = snapshot()
            if (state == null) pendingUrls.add(url) else processUrl(url, state)
        }
    }

    private fun processUrl(url: String, state: Snapshot) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val cid = queryParams(uri)["cid"]
        if (cid != null) {
            requestPayload(state, "/api/deeplink?cid=" + encode(cid), cid = cid, includeDeviceHeader = true) { deliver(it, state) }
        } else if (uri.scheme == "https" || uri.scheme == "http") {
            requestPayload(state, "/api/deeplink/resolve-url", method = "POST", body = { mapOf("url" to url) },
                includeDeviceBody = true, fallbackUri = uri) { deliver(it, state) }
        }
    }

    fun claimDeferredIfAvailable(context: Context, callback: (LinkPayload?) -> Unit) {
        claimDeferredCore(context.applicationContext ?: context, callback)
    }

    internal fun claimDeferredCore(context: Context? = null, callback: (LinkPayload?) -> Unit) {
        synchronized(stateLock) {
            val state = snapshot()
            if (state == null) { post { callback(null) }; return }
            val settled = AtomicBoolean(false)
            val finish: (LinkPayload?) -> Unit = { payload ->
                if (settled.compareAndSet(false, true)) callback(if (current(state.generation)) payload else null)
            }
            fun fallback() {
                if (!current(state.generation)) { finish(null); return }
                requestPayload(state, "/api/deferred/claim", method = "POST", includeDeviceBody = true,
                    body = { mapOf("platform" to "android", "bundleId" to (dependencies.packageName ?: state.context?.packageName ?: "")) }) {
                    finish(deliver(it, state))
                }
            }
            // The discovery budget ends before HTTP begins. A valid referrer
            // claim cannot race a six-second timer into fingerprint fallback.
            dependencies.scope.submit(minOf(dependencies.referrerTimeoutMs, state.config.requestTimeoutMs), { cancellation ->
                val source = dependencies.referrerFactory?.invoke(context ?: state.context)
                    ?: (context ?: state.context)?.let { AndroidReferrerConnection(it) }
                if (source == null) null else {
                    cancellation.onCleanup { source.close() }
                    val readiness = ArrayBlockingQueue<Boolean>(1)
                    source.connect { ready -> readiness.offer(ready) }
                    if (readiness.take()) { cancellation.check(); source.read()?.trim()?.takeIf { it.isNotEmpty() } } else null
                }
            }) { referrer, _ ->
                post {
                    synchronized(stateLock) {
                        if (!current(state.generation)) { finish(null); return@synchronized }
                        if (referrer == null) fallback() else {
                            requestPayload(state, "/api/install-referrer", method = "POST", body = { mapOf("referrer" to referrer) }) { payload ->
                                if (!current(state.generation)) finish(null)
                                else if (payload == null) fallback()
                                else finish(deliver(payload, state))
                            }
                        }
                    }
                }
            }
        }
    }

    fun track(event: String, props: Map<String, Any?>? = null) {
        synchronized(stateLock) {
            val state = snapshot() ?: return
            if (event.isEmpty()) return
            requestPayload(state, "/api/app-events", method = "POST", body = {
                mutableMapOf<String, Any?>("type" to event, "platform" to "android", "timestamp" to System.currentTimeMillis() / 1000).apply {
                    state.payload?.cid?.let { put("cid", it) }
                    state.payload?.linkId?.let { put("linkId", it) }
                    state.userId?.let { put("userId", it) }
                    props?.let { put("detail", toJson(it)) }
                }
            }) { }
        }
    }

    private fun requestPayload(state: Snapshot, path: String, method: String = "GET",
                               body: (() -> Map<String, Any?>)? = null, includeDeviceHeader: Boolean = false,
                               includeDeviceBody: Boolean = false, cid: String? = null, fallbackUri: URI? = null,
                               done: (LinkPayload?) -> Unit) {
        dependencies.scope.submit(state.config.requestTimeoutMs, { cancellation ->
            val data = body?.invoke()?.toMutableMap()
            val device = if (state.config.sendDeviceInfo && (includeDeviceHeader || includeDeviceBody)) {
                if (dependencies.deviceProvider != null) dependencies.deviceProvider.invoke() else buildDevicePayload(state)
            } else null
            cancellation.check()
            if (includeDeviceBody && device != null) data?.put("device", device)
            val connection = dependencies.openConnection(URL(state.config.baseUrl.trimEnd('/') + path))
            cancellation.onCleanup { connection.disconnect() }
            connection.requestMethod = method
            connection.connectTimeout = state.config.requestTimeoutMs.toInt()
            connection.readTimeout = state.config.requestTimeoutMs.toInt()
            state.config.appId?.let { connection.setRequestProperty("x-app-id", it) }
            state.config.appKey?.let { connection.setRequestProperty("x-api-key", it) }
            connection.setRequestProperty("Accept", "application/json")
            if (includeDeviceHeader && device != null) connection.setRequestProperty("x-linkme-device", toJson(device))
            cancellation.check()
            if (data != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(toJson(data).toByteArray(Charsets.UTF_8)) }
            }
            cancellation.check()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(status, response)
        }) { result, error ->
            post {
                synchronized(stateLock) {
                    if (!current(state.generation) || error != null || result == null) {
                        if (error != null && current(state.generation)) debugLog("request.error", error)
                        done(null)
                    } else {
                        val payload = if (result.status in 200..299) annotatePayload(parsePayload(result.body), true)?.let {
                            if (cid != null && it.cid == null) it.copy(cid = cid) else it
                        } else if (fallbackUri != null && isDomainNotFound(result.body)) buildBasicUniversalPayload(fallbackUri) else null
                        done(payload)
                    }
                }
            }
        }
    }

    /** Called only in a posted main action while holding stateLock. */
    private fun deliver(payload: LinkPayload?, state: Snapshot): LinkPayload? {
        if (payload == null || !current(state.generation)) return null
        if (handleForcedWebRedirect(payload)) return null
        emit(payload, state.generation)
        return if (current(state.generation)) payload else null
    }

    private fun emit(payload: LinkPayload, expected: Long) {
        if (!current(expected) || payload.navigationAllowed == false) return
        lastPayload = payload
        for (id in listeners.keys.toList()) {
            if (!current(expected)) break
            try { listeners[id]?.invoke(payload) } catch (error: Exception) { debugLog("listener.error", error) }
        }
    }

    // Android's platform JSON parser preserves escaped strings and Unicode correctly.
    internal fun parsePayload(json: String): LinkPayload? {
        return try {
            val obj = JSONObject(json)
            if (!obj.isNull("navigationAllowed") && obj.opt("navigationAllowed") !is Boolean) return null
            fun string(name: String): String? {
                val value = obj.opt(name)
                return (value as? String)?.takeIf { it.isNotEmpty() }
            }
            fun bool(name: String): Boolean? = obj.opt(name) as? Boolean
            val payload = LinkPayload(
                cid = string("cid"),
                linkId = string("linkId"),
                path = string("path"),
                params = parseStringMap(obj.optJSONObject("params")),
                utm = parseStringMap(obj.optJSONObject("utm")),
                custom = parseStringMap(obj.optJSONObject("custom")),
                url = string("url"),
                isLinkMe = bool("isLinkMe"),
                duplicate = bool("duplicate"),
                claimId = string("claimId"),
                claimMethod = string("claimMethod"),
                matchConfidence = string("matchConfidence"),
                attributionStatus = string("attributionStatus"),
                contractVersion = (obj.opt("contractVersion") as? Int)?.takeIf { it > 0 },
                claimPolicyVersion = (obj.opt("claimPolicyVersion") as? Int)?.takeIf { it > 0 },

                navigationAllowed = bool("navigationAllowed"),
                forceRedirectWeb = bool("forceRedirectWeb"),
                webFallbackUrl = string("webFallbackUrl")
            )
            if (payload.cid == null && payload.linkId == null && payload.path == null && payload.params == null &&
                payload.utm == null && payload.custom == null && payload.url == null &&
                payload.isLinkMe == null && payload.duplicate == null && payload.forceRedirectWeb == null &&
                payload.webFallbackUrl == null && !(payload.navigationAllowed == false && !payload.claimId.isNullOrEmpty())
            ) null else payload
        } catch (_: Throwable) { null }
    }

    private fun annotatePayload(payload: LinkPayload?, isLinkMe: Boolean? = true): LinkPayload? {
        if (payload == null) return null
        val next = payload.isLinkMe ?: isLinkMe
        return if (next == payload.isLinkMe) payload else payload.copy(isLinkMe = next)
    }

    internal fun handleForcedWebRedirect(payload: LinkPayload): Boolean {
        if (payload.navigationAllowed == false) return true
        if (payload.forceRedirectWeb != true) return false
        val raw = payload.webFallbackUrl?.trim()
        if (raw.isNullOrEmpty()) {
            debugLog("forceWeb.enabled_but_missing_url")
            return false
        }
        return try {
            dependencies.openBrowser?.let { return it(raw) }
            val ctx = appContext ?: return false
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            debugLog("forceWeb.browser_open")
            true
        } catch (t: Throwable) {
            debugLog("forceWeb.browser_open_failed", t)
            false
        }
    }

    private fun buildBasicUniversalPayload(uri: URI): LinkPayload {
        val all = queryParams(uri)
        val params = all.filterKeys { !utmKeys.contains(it) }.takeIf { it.isNotEmpty() }
        val utm = all.filterKeys { utmKeys.contains(it) }.takeIf { it.isNotEmpty() }
        return LinkPayload(path = uri.path ?: "/", params = params, utm = utm, url = uri.toString(), isLinkMe = false)
    }

    private fun queryParams(uri: URI): Map<String, String> = runCatching {
        val result = linkedMapOf<String, String>()
        uri.rawQuery?.split("&")?.forEach { pair ->
            val parts = pair.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            if (!result.containsKey(key)) result[key] = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        result
    }.getOrDefault(emptyMap())

    private fun isDomainNotFound(json: String): Boolean {
        return Regex("\\\"error\\\"\\s*:\\s*\\\"domain_not_found\\\"").containsMatchIn(json)
    }

    private fun parseStringMap(obj: JSONObject?): Map<String, String>? {
        if (obj == null) return null
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!obj.isNull(key)) (obj.opt(key) as? String)?.let { map[key] = it }
        }
        return if (map.isEmpty()) null else map
    }

    private fun toJson(map: Map<String, Any?>): String {
        return JSONObject(map).toString()
    }

    @SuppressLint("HardwareIds")
    private fun buildDevicePayload(state: Snapshot): Map<String, Any>? {
        val cfg = state.config
        val dev = mutableMapOf<String, Any>()
        dev["platform"] = "android"
        // App info not available here; supply where possible at call sites
        dev["osVersion"] = Build.VERSION.RELEASE ?: ""
        dev["deviceModel"] = (Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "")
        dev["locale"] = Locale.getDefault().toString()
        dev["timezone"] = TimeZone.getDefault().id
        val consent = mutableMapOf<String, Any>()
        if (cfg.includeVendorId) {
            consent["vendor"] = true
            try {
                val ctx = state.context
                val ssaid = if (ctx != null) Settings.Secure.getString(
                    ctx.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) else null
                if (!ssaid.isNullOrEmpty()) {
                    dev["id_type"] = "android_id"
                    dev["device_id"] = ssaid
                }
            } catch (_: Throwable) { /* context not available here */ }
        }
        if (state.advertisingConsent) {
            consent["advertising"] = true
            try {
                // Note: Requires play-services-ads-identifier and AD_ID permission
                val ctx = state.context
                val info = if (ctx != null) AdvertisingIdClient.getAdvertisingIdInfo(ctx) else null
                val id = info?.id
                if (!id.isNullOrEmpty() && id != "00000000-0000-0000-0000-000000000000") {
                    dev["id_type"] = "adid"
                    dev["device_id"] = id
                }
            } catch (_: Throwable) { /* ignore */ }
        }
        dev["consent"] = consent
        return dev
    }

    // Optional wrapper protocol. Public method names are preserved by consumer-rules.pro;
    // wrappers compiled with an older core can probe version support reflectively.
    private data class LifecycleHeader(val owner: String, val generation: Long)
    private fun resetLifecycleOwner() { lifecycleOwner = null; lifecycleClientGeneration = 0; lifecycleLastCommand = null }
    private fun lifecycleInteger(value: Any?): Long? {
        if (value !is Byte && value !is Short && value !is Int && value !is Long) return null
        return (value as Number).toLong().takeIf { it in 0..9_007_199_254_740_991L }
    }
    private fun lifecycleHeader(args: Map<String, Any?>): LifecycleHeader? {
        if (lifecycleInteger(args["contractVersion"]) != 1L) return null
        val owner = args["ownerId"] as? String ?: return null
        if (owner.isEmpty() || owner.toByteArray(Charsets.UTF_8).size > 128) return null
        return LifecycleHeader(owner, lifecycleInteger(args["clientGeneration"]) ?: return null)
    }
    private fun lifecycleReply(header: LifecycleHeader?, error: String? = null, duplicate: Boolean = false): Map<String, Any?> =
        mutableMapOf<String, Any?>("contractVersion" to 1, "ok" to (error == null)).apply {
            if (header != null) { put("ownerId", header.owner); put("clientGeneration", header.generation) }
            if (error != null) put("error", error)
            if (duplicate) put("duplicate", true)
        }
    private fun lifecycleFrame(header: LifecycleHeader, payload: LinkPayload?): Map<String, Any?> =
        lifecycleReply(header).toMutableMap().apply {
            put("payload", payload?.let { p -> mapOf(
                "cid" to p.cid, "linkId" to p.linkId, "path" to p.path, "params" to p.params, "utm" to p.utm,
                "custom" to p.custom, "url" to p.url, "isLinkMe" to p.isLinkMe, "duplicate" to p.duplicate,
                "claimId" to p.claimId, "claimMethod" to p.claimMethod, "matchConfidence" to p.matchConfidence,
                "attributionStatus" to p.attributionStatus, "contractVersion" to p.contractVersion,
                "claimPolicyVersion" to p.claimPolicyVersion, "navigationAllowed" to p.navigationAllowed,
                "forceRedirectWeb" to p.forceRedirectWeb, "webFallbackUrl" to p.webFallbackUrl,
            ).filterValues { it != null } })
        }
    private fun recordLifecycle(header: LifecycleHeader, signature: String) {
        lifecycleKnownGenerations.remove(header.owner)
        lifecycleKnownGenerations[header.owner] = header.generation
        if (lifecycleKnownGenerations.size > 64) lifecycleKnownGenerations.remove(lifecycleKnownGenerations.keys.first())
        lifecycleLastCommand = Triple(header.owner, header.generation, signature)
        lifecycleClientGeneration = header.generation
    }

    fun linkmeLifecycleVersion(): Int = 1

    fun linkmeLifecycle(args: Map<String, Any?>, context: Context?): Map<String, Any?> {
        val header = lifecycleHeader(args) ?: return lifecycleReply(null, "invalid_args")
        val action = args["action"] as? String ?: return lifecycleReply(null, "invalid_args")
        val normalized = sortedMapOf<String, Any?>("action" to action)
        var preparedConfig: Config? = null
        var identity: String? = null
        var consent = false
        when (action) {
            "configure" -> {
                val raw = args["baseUrl"] as? String ?: return lifecycleReply(header, "invalid_args")
                val url = runCatching { URL(raw) }.getOrNull() ?: return lifecycleReply(header, "invalid_args")
                val timeout = lifecycleInteger(args["requestTimeoutMs"] ?: 15_000) ?: return lifecycleReply(header, "invalid_args")
                if (url.protocol.lowercase() !in listOf("http", "https") || url.host.isBlank() || url.query != null || url.ref != null || timeout !in 1..Int.MAX_VALUE.toLong()) return lifecycleReply(header, "invalid_args")
                for (key in listOf("sendDeviceInfo", "includeVendorId", "includeAdvertisingId", "debug")) {
                    if (args[key] != null && args[key] !is Boolean) return lifecycleReply(header, "invalid_args")
                }
                for (key in listOf("appId", "appKey")) if (args[key] != null && args[key] !is String) return lifecycleReply(header, "invalid_args")
                val cfg = Config(raw, appId = args["appId"] as? String, appKey = args["appKey"] as? String,
                    sendDeviceInfo = args["sendDeviceInfo"] as? Boolean ?: true, includeVendorId = args["includeVendorId"] as? Boolean ?: true,
                    includeAdvertisingId = args["includeAdvertisingId"] as? Boolean ?: false, debug = args["debug"] as? Boolean ?: false,
                    requestTimeoutMs = timeout)
                preparedConfig = cfg
                normalized.putAll(mapOf("baseUrl" to raw, "appId" to cfg.appId, "appKey" to cfg.appKey,
                    "sendDeviceInfo" to cfg.sendDeviceInfo, "includeVendorId" to cfg.includeVendorId,
                    "includeAdvertisingId" to cfg.includeAdvertisingId, "debug" to cfg.debug, "requestTimeoutMs" to timeout))
            }
            "setUserId" -> {
                if (!args.containsKey("userId") || (args["userId"] != null && args["userId"] !is String)) return lifecycleReply(header, "invalid_args")
                identity = args["userId"] as? String
                if (identity != null && identity.isBlank()) return lifecycleReply(header, "invalid_args")
                normalized["userId"] = identity
            }
            "setAdvertisingConsent" -> {
                consent = args["granted"] as? Boolean ?: return lifecycleReply(header, "invalid_args")
                normalized["granted"] = consent
            }
            "cancelPendingRequests", "dispose", "setReady" -> Unit
            "track" -> {
                if ((args["event"] as? String).isNullOrBlank() || (args["properties"] != null && (args["properties"] !is Map<*, *> || (args["properties"] as Map<*, *>).keys.any { it !is String }))) return lifecycleReply(header, "invalid_args")
            }
            else -> return lifecycleReply(header, "unsupported_action")
        }
        val signature = JSONObject(normalized).toString()
        return synchronized(stateLock) {
            val mutation = action !in listOf("track", "setReady")
            val known = lifecycleKnownGenerations[header.owner]
            if (mutation && known != null) {
                if (header.generation < known) return@synchronized lifecycleReply(header, "stale_generation")
                if (header.generation == known) {
                    if (lifecycleLastCommand == Triple(header.owner, header.generation, signature)) return@synchronized lifecycleReply(header, duplicate = true)
                    return@synchronized lifecycleReply(header, "generation_conflict")
                }
            }
            if (action == "configure") {
                if (lifecycleOwner != null && lifecycleOwner != header.owner) return@synchronized lifecycleReply(header, "owner_conflict")
                configureCore(preparedConfig!!, context?.applicationContext ?: context)
                lifecycleOwner = header.owner
            } else {
                if (lifecycleOwner != header.owner || config == null) return@synchronized lifecycleReply(header, "owner_changed")
                if (!mutation && lifecycleClientGeneration != header.generation) return@synchronized lifecycleReply(header, "stale_generation")
                when (action) {
                    "cancelPendingRequests" -> invalidate()
                    "dispose" -> dispose()
                    "setUserId" -> { invalidate(); userId = identity }
                    "setAdvertisingConsent" -> { invalidate(); advertisingConsentEnabled = consent }
                    "setReady" -> Unit
                    "track" -> {
                        @Suppress("UNCHECKED_CAST")
                        track(args["event"] as String, args["properties"] as? Map<String, Any?>)
                    }
                }
            }
            if (mutation) recordLifecycle(header, signature)
            lifecycleReply(header)
        }
    }

    fun linkmeLifecycleRead(args: Map<String, Any?>, completion: (Map<String, Any?>) -> Unit) {
        val header = lifecycleHeader(args)
        val action = args["action"] as? String
        if (header == null || action !in listOf("getInitialLink", "claimDeferredIfAvailable")) { post { completion(lifecycleReply(null, "invalid_args")) }; return }
        synchronized(stateLock) {
            if (lifecycleOwner != header.owner || config == null || lifecycleClientGeneration != header.generation) {
                post { completion(lifecycleReply(header, "owner_changed")) }; return
            }
            val deliver: (LinkPayload?) -> Unit = { payload ->
                synchronized(stateLock) {
                    completion(if (lifecycleOwner != header.owner || lifecycleClientGeneration != header.generation) lifecycleReply(header, "owner_changed") else lifecycleFrame(header, payload))
                }
            }
            if (action == "getInitialLink") getInitialLink(deliver) else claimDeferredCore(callback = deliver)
        }
    }

    fun linkmeLifecycleListen(listener: (Map<String, Any?>) -> Unit): String = synchronized(stateLock) {
        val token = UUID.randomUUID().toString()
        lifecycleSubscriptions[token] = addListener { payload ->
            lifecycleOwner?.let { owner -> listener(lifecycleFrame(LifecycleHeader(owner, lifecycleClientGeneration), payload)) }
        }
        token
    }

    fun linkmeLifecycleUnlisten(token: String) { synchronized(stateLock) { lifecycleSubscriptions.remove(token)?.invoke() } }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
}
