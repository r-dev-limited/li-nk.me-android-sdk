package me.link.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

data class LinkPayload(
    val linkId: String? = null,
    val path: String? = null,
    val params: Map<String, String>? = null,
    val utm: Map<String, String>? = null,
    val custom: Map<String, String>? = null,
    val url: String? = null,
    val isLinkMe: Boolean? = null,
)

class LinkMe private constructor() {
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
    )

    private var config: Config? = null
    private var userId: String? = null
    private val listeners = CopyOnWriteArrayList<(LinkPayload) -> Unit>()
    private var lastPayload: LinkPayload? = null
    private val serial = Executors.newSingleThreadExecutor()
    private var pendingUris: MutableList<Uri> = mutableListOf()
    private var advertisingConsentEnabled: Boolean = false

    private var appContext: Context? = null

    fun configure(context: Context, config: Config) {
        this.config = config
        this.advertisingConsentEnabled = config.includeAdvertisingId
        this.appContext = context.applicationContext
        debugLog(
            "configure",
            mapOf(
                "baseUrl" to config.baseUrl,
                "appId" to (config.appId ?: "none"),
                "debug" to config.debug
            )
        )
        // Drain any pending URIs
        val toProcess = ArrayList(pendingUris)
        pendingUris.clear()
        toProcess.forEach { handleUri(it) }
    }

    // First release: no deprecated aliases; use configure(context, config)

    fun getInitialLink(callback: (LinkPayload?) -> Unit) {
        serial.execute { callback(lastPayload) }
    }

    fun addListener(handler: (LinkPayload) -> Unit): () -> Unit {
        listeners.add(handler)
        return { listeners.remove(handler) }
    }

    // Debug helper to prove wiring in sample app
    fun debugEmit(payload: LinkPayload) {
        listeners.forEach { it(payload) }
        lastPayload = payload
    }

    fun setAdvertisingConsent(granted: Boolean) {
        advertisingConsentEnabled = granted
    }

    companion object {
        private const val TAG = "LinkMe"
        @JvmStatic
        val shared: LinkMe by lazy { LinkMe() }
    }

    private fun debugLog(message: String, attrs: Map<String, Any?>? = null, error: Throwable? = null) {
        if (config?.debug != true) return
        val details = attrs?.entries
            ?.joinToString(", ") { "${it.key}=${it.value}" }
            ?.let { " {$it}" }
            ?: ""
        if (error != null) {
            Log.d(TAG, "[LinkMe] $message$details", error)
        } else {
            Log.d(TAG, "[LinkMe] $message$details")
        }
    }

    // MARK: - Public link handlers
    fun onNewIntent(intent: Intent?) {
        val data = intent?.data ?: return
        debugLog("onNewIntent", mapOf("data" to data.toString()))
        handleUri(data)
    }

    fun handleIntent(intent: Intent?) { onNewIntent(intent) }

    fun claimDeferredIfAvailable(context: Context, callback: (LinkPayload?) -> Unit) {
        debugLog("claimDeferred.start")
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                var handled = false
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    debugLog("installReferrer.connected")
                    try {
                        val response: ReferrerDetails = client.installReferrer
                        val referrer = response.installReferrer // e.g., utm_source=...&cid=abc
                        if (!referrer.isNullOrBlank()) {
                            debugLog("installReferrer.referrer_present")
                            handled = true
                            claimFromInstallReferrer(referrer) { p -> callback(p) }
                        }
                    } catch (t: Throwable) {
                        debugLog("installReferrer.error", error = t)
                        /* fall through to POST */
                    }
                } else {
                    debugLog("installReferrer.unavailable", mapOf("responseCode" to responseCode))
                }
                try { client.endConnection() } catch (_: Throwable) {}
                if (!handled) {
                    debugLog("installReferrer.fallback")
                    fallbackDeferredClaim(context, callback)
                }
            }
            override fun onInstallReferrerServiceDisconnected() { /* no-op */ }
        })
    }

    fun setUserId(id: String) { userId = id }

    fun track(event: String, props: Map<String, Any?>? = null) {
        val cfg = config ?: return
        serial.execute {
            try {
                val url = URL(cfg.baseUrl.trimEnd('/') + "/api/app-events")
                val conn = (url.openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                setHeaders(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = mutableMapOf<String, Any?>(
                    "type" to event,
                    "platform" to "android",
                    "timestamp" to (System.currentTimeMillis() / 1000)
                )
                userId?.let { body["userId"] = it }
                props?.let { body["detail"] = toJson(it) }
                val json = toJson(body)
                conn.outputStream.use { it.write(json.toByteArray()) }
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {}
        }
    }

    // MARK: - Internal
    private fun handleUri(uri: Uri) {
        if (config == null) {
            debugLog("handleUri.pending", mapOf("uri" to uri.toString()))
            pendingUris.add(uri)
            return
        }
        debugLog("handleUri", mapOf("uri" to uri.toString()))
        val cid = uri.getQueryParameter("cid")
        if (cid != null) {
            debugLog("handleUri.cid", mapOf("cid" to cid))
            resolveCid(cid) { }
        } else if ((uri.scheme ?: "").startsWith("http")) {
            debugLog("handleUri.universal", mapOf("url" to uri.toString()))
            resolveUniversalLink(uri) { }
        }
    }

    private fun resolveCid(cid: String, done: (LinkPayload?) -> Unit) {
        val cfg = config ?: return done(null)
        serial.execute {
            try {
                debugLog("resolveCid.request", mapOf("cid" to cid))
                val endpoint = cfg.baseUrl.trimEnd('/') + "/api/deeplink?cid=" + encode(cid)
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection)
                conn.requestMethod = "GET"
                setHeaders(conn)
                // Mirror iOS: include device payload header if enabled
                val dev = buildDevicePayload()
                if (cfg.sendDeviceInfo && dev != null) {
                    conn.setRequestProperty("x-linkme-device", toJson(dev))
                }
                conn.inputStream.use { stream ->
                    val json = stream.bufferedReader().readText()
                    val payload = annotatePayload(parsePayload(json), true)
                    if (payload != null) emit(payload)
                    debugLog("resolveCid.success", mapOf("cid" to cid, "hasPayload" to (payload != null)))
                    done(payload)
                }
            } catch (t: Throwable) {
                debugLog("resolveCid.error", mapOf("cid" to cid), t)
                done(null)
            }
        }
    }

    private fun claimFromInstallReferrer(referrer: String, done: (LinkPayload?) -> Unit) {
        val cfg = config ?: return done(null)
        serial.execute {
            try {
                debugLog("installReferrer.claim.request")
                val url = URL(cfg.baseUrl.trimEnd('/') + "/api/install-referrer")
                val conn = (url.openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                setHeaders(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = mutableMapOf<String, Any>("referrer" to referrer)
                conn.outputStream.use { it.write(toJson(body).toByteArray()) }
                conn.inputStream.use { stream ->
                    val resp = stream.bufferedReader().readText()
                    val payload = annotatePayload(parsePayload(resp), true)
                    if (payload != null) emit(payload)
                    debugLog("installReferrer.claim.success", mapOf("hasPayload" to (payload != null)))
                    done(payload)
                }
            } catch (t: Throwable) {
                debugLog("installReferrer.claim.error", error = t)
                done(null)
            }
        }
    }

    private fun resolveUniversalLink(uri: Uri, done: (LinkPayload?) -> Unit) {
        val cfg = config ?: return done(null)
        serial.execute {
            try {
                debugLog("resolveUniversal.request", mapOf("url" to uri.toString()))
                val url = URL(cfg.baseUrl.trimEnd('/') + "/api/deeplink/resolve-url")
                val conn = (url.openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                setHeaders(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = mutableMapOf<String, Any>("url" to uri.toString())
                val dev = buildDevicePayload()
                if (cfg.sendDeviceInfo && dev != null) body["device"] = dev
                val json = toJson(body)
                conn.outputStream.use { it.write(json.toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (status in 200..299) {
                    val payload = annotatePayload(parsePayload(resp), true)
                    if (payload != null) emit(payload)
                    debugLog("resolveUniversal.success", mapOf("url" to uri.toString(), "hasPayload" to (payload != null)))
                    done(payload)
                } else {
                    if (isDomainNotFound(resp)) {
                        val fallback = buildBasicUniversalPayload(uri)
                        emit(fallback)
                        debugLog("resolveUniversal.non_linkme", mapOf("url" to uri.toString()))
                        done(fallback)
                        return@execute
                    }
                    debugLog("resolveUniversal.http_error", mapOf("url" to uri.toString(), "status" to status))
                    done(null)
                }
            } catch (t: Throwable) {
                debugLog("resolveUniversal.error", mapOf("url" to uri.toString()), t)
                done(null)
            }
        }
    }

    private fun fallbackDeferredClaim(context: Context, callback: (LinkPayload?) -> Unit) {
        val cfg = config ?: return callback(null)
        serial.execute {
            try {
                debugLog("deferred.claim.request", mapOf("platform" to "android"))
                val url = URL(cfg.baseUrl.trimEnd('/') + "/api/deferred/claim")
                val conn = (url.openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                setHeaders(conn)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = mutableMapOf<String, Any>(
                    "bundleId" to context.packageName,
                    "platform" to "android",
                )
                val dev = buildDevicePayload()
                if (cfg.sendDeviceInfo && dev != null) body["device"] = dev
                conn.outputStream.use { it.write(toJson(body).toByteArray()) }
                conn.inputStream.use { stream ->
                    val resp = stream.bufferedReader().readText()
                    val payload = annotatePayload(parsePayload(resp), true)
                    if (payload != null) emit(payload)
                    debugLog("deferred.claim.success", mapOf("hasPayload" to (payload != null)))
                    callback(payload)
                }
            } catch (t: Throwable) {
                debugLog("deferred.claim.error", error = t)
                callback(null)
            }
        }
    }

    private fun setHeaders(conn: HttpURLConnection) {
        config?.appId?.let { conn.setRequestProperty("x-app-id", it) }
        config?.appKey?.let { conn.setRequestProperty("x-api-key", it) }
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
    }

    private fun emit(payload: LinkPayload) {
        lastPayload = payload
        listeners.forEach { it(payload) }
    }

    // Minimal JSON handling without dependencies
    private fun parsePayload(json: String): LinkPayload? {
        return try {
            val linkId = Regex("\\\"linkId\\\"\\s*:\\s*\\\"([^\\\"]*)").find(json)?.groupValues?.getOrNull(1)
            val path = Regex("\\\"path\\\"\\s*:\\s*\\\"([^\\\"]*)").find(json)?.groupValues?.getOrNull(1)
            val params = parseStringMap(json, "params")
            val utm = parseStringMap(json, "utm")
            val custom = parseStringMap(json, "custom")
            val url = Regex("\\\"url\\\"\\s*:\\s*\\\"([^\\\"]*)").find(json)?.groupValues?.getOrNull(1)
            val isLinkMeMatch = Regex("\\\"isLinkMe\\\"\\s*:\\s*(true|false)").find(json)?.groupValues?.getOrNull(1)
            val isLinkMe = when (isLinkMeMatch) {
                "true" -> true
                "false" -> false
                else -> null
            }
            LinkPayload(linkId = linkId, path = path, params = params, utm = utm, custom = custom, url = url, isLinkMe = isLinkMe)
        } catch (_: Throwable) { null }
    }

    private fun annotatePayload(payload: LinkPayload?, isLinkMe: Boolean? = true): LinkPayload? {
        if (payload == null) return null
        val next = payload.isLinkMe ?: isLinkMe
        return if (next == payload.isLinkMe) payload else payload.copy(isLinkMe = next)
    }

    private fun buildBasicUniversalPayload(uri: Uri): LinkPayload {
        val (params, utm) = splitQueryParams(uri)
        val path = uri.path ?: "/"
        return LinkPayload(path = path, params = params, utm = utm, url = uri.toString(), isLinkMe = false)
    }

    private fun splitQueryParams(uri: Uri): Pair<Map<String, String>?, Map<String, String>?> {
        val params = mutableMapOf<String, String>()
        val utm = mutableMapOf<String, String>()
        for (key in uri.queryParameterNames) {
            val value = uri.getQueryParameter(key) ?: continue
            if (utmKeys.contains(key)) {
                utm[key] = value
            } else {
                params[key] = value
            }
        }
        return Pair(if (params.isEmpty()) null else params, if (utm.isEmpty()) null else utm)
    }

    private fun isDomainNotFound(json: String): Boolean {
        return Regex("\\\"error\\\"\\s*:\\s*\\\"domain_not_found\\\"").containsMatchIn(json)
    }

    private fun parseStringMap(json: String, key: String): Map<String, String>? {
        val block = Regex("\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\{([^}]*)\\}")
            .find(json)?.groupValues?.getOrNull(1) ?: return null
        val map = mutableMapOf<String, String>()
        Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .findAll(block)
            .forEach { m -> map[m.groupValues[1]] = m.groupValues[2] }
        return if (map.isEmpty()) null else map
    }

    private fun toJson(map: Map<String, Any?>): String {
        // Minimal serializer for simple maps; only handles primitives and nested maps
        fun anyToJson(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + v.replace("\"", "\\\"") + "\""
            is Number, is Boolean -> v.toString()
            is Map<*, *> -> "{" + v.entries.joinToString(",") { anyToJson(it.key.toString()) + ":" + anyToJson(it.value) } + "}"
            else -> "\"" + v.toString().replace("\"", "\\\"") + "\""
        }
        return anyToJson(map)
    }

    private fun buildDevicePayload(): Map<String, Any>? {
        val cfg = config ?: return null
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
                val ctx = appContext
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
        if (advertisingConsentEnabled) {
            consent["advertising"] = true
            try {
                // Note: Requires play-services-ads-identifier and AD_ID permission
                val ctx = appContext
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

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
}
