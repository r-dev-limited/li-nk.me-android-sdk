package nz.co.rdev.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.link.sdk.LinkMe
import me.link.sdk.LinkPayload
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ApplicationInfo

private const val DEFAULT_BASE_URL = "https://0jk2u2h9.li-nk.me"
private const val APP_ID = "e0qcsxfc"
private const val APP_KEY = "ak_nMqCl4QwFSVvjC5VrrAvTH0ziWH06WLhua6EtCvFO6o"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Resolve base URL:
        // 1) BuildConfig.LINKME_BASE_URL (from env/Gradle prop)
        // 2) AndroidManifest meta-data "LinkMeBaseURL"
        // 3) Default to emulator host
        val resolvedBaseUrl = resolveBaseUrl() ?: DEFAULT_BASE_URL

        // Capture initial intent (cold start). If not configured yet, SDK queues it until init.
        LinkMe.shared.handleIntent(intent)

        setContent {
            val activity = this@MainActivity
            val appContext = activity.applicationContext

            var baseUrl by remember { mutableStateOf(resolvedBaseUrl) }
            var initialized by remember { mutableStateOf(false) }
            var lastPayload by remember { mutableStateOf<LinkPayload?>(null) }
            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            var userId by remember { mutableStateOf("") }
            var consentStatus by remember { mutableStateOf(ConsentStatus.NOT_DETERMINED) }
            var showConsentDialog by remember { mutableStateOf(false) }

            fun updateFromPayload(payload: LinkPayload?) {
                lastPayload = payload
                currentScreen = Screen.fromPath(payload?.path)
                
                // EXAMPLE: Log to Analytics
                // This helper demonstrates how to map to Firebase and PostHog
                AnalyticsHelper.logToAnalytics(context, payload)
            }

            fun initializeSdk() {
                val sanitizedBase = baseUrl.trim().ifEmpty { resolvedBaseUrl }
                baseUrl = sanitizedBase
                println("[LinkMe Android Example] Initializing LinkMe SDK with base URL: $sanitizedBase")
                // Note: enablePasteboard is iOS-only and now portal-controlled
                LinkMe.shared.configure(
                    context = appContext,
                    config = LinkMe.Config(
                        baseUrl = sanitizedBase,
                        appId = APP_ID,
                        appKey = APP_KEY,
                        sendDeviceInfo = true,
                        includeVendorId = true,
                        includeAdvertisingId = false,
                    )
                )
                initialized = true
                LinkMe.shared.setAdvertisingConsent(consentStatus == ConsentStatus.AUTHORIZED)
                LinkMe.shared.getInitialLink { payload ->
                    if (payload != null) {
                        activity.runOnUiThread { updateFromPayload(payload) }
                    } else {
                        LinkMe.shared.claimDeferredIfAvailable(appContext) { deferred ->
                            activity.runOnUiThread { updateFromPayload(deferred) }
                        }
                    }
                }
                LinkMe.shared.track("open")
                if (consentStatus == ConsentStatus.NOT_DETERMINED) {
                    showConsentDialog = true
                }
            }

            fun claimDeferredLink() {
                LinkMe.shared.claimDeferredIfAvailable(appContext) { payload ->
                    activity.runOnUiThread { updateFromPayload(payload) }
                }
            }

            DisposableEffect(Unit) {
                val remove = LinkMe.shared.addListener { payload ->
                    activity.runOnUiThread { updateFromPayload(payload) }
                }
                onDispose { remove() }
            }

            LaunchedEffect(Unit) {
                initializeSdk()
            }

            if (showConsentDialog) {
                AdvertisingConsentDialog(
                    onGrant = {
                        consentStatus = ConsentStatus.AUTHORIZED
                        LinkMe.shared.setAdvertisingConsent(true)
                        showConsentDialog = false
                    },
                    onDeny = {
                        consentStatus = ConsentStatus.DENIED
                        LinkMe.shared.setAdvertisingConsent(false)
                        showConsentDialog = false
                    },
                    onDismiss = { showConsentDialog = false }
                )
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (currentScreen) {
                        Screen.HOME -> HomeScreen(
                            initialized = initialized,
                            lastPayload = lastPayload,
                            userId = userId,
                            onUserIdChange = { userId = it },
                            onInitialize = { initializeSdk() },
                            onTrack = { event, props -> LinkMe.shared.track(event, props) },
                            onSetUserId = {
                                LinkMe.shared.setUserId(userId)
                                println("[LinkMe Android Example] Set userId=$userId")
                            },
                            onClearUserId = {
                                userId = ""
                                LinkMe.shared.setUserId("")
                                println("[LinkMe Android Example] Cleared userId")
                            },
                            onClaimDeferred = { claimDeferredLink() },
                            onNavigate = { currentScreen = it },
                        )

                        Screen.PROFILE -> ProfileScreen(
                            lastPayload = lastPayload,
                            onTrack = {
                                LinkMe.shared.track("profile_viewed")
                            },
                            onNavigate = { currentScreen = it },
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            baseUrl = baseUrl,
                            appId = APP_ID,
                            consentStatus = consentStatus,
                            lastPayload = lastPayload,
                            onRequestConsent = { showConsentDialog = true },
                            onTrack = { LinkMe.shared.track("settings_viewed") },
                            onNavigate = { currentScreen = it },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LinkMe.shared.onNewIntent(intent)
    }

    private fun resolveBaseUrl(): String? {
        // Read from manifest meta-data (configured via Gradle placeholder)
        return try {
            val pm = packageManager
            val ai: ApplicationInfo =
                if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(
                    packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                ) else pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val v = ai.metaData?.getString("LinkMeBaseURL")
            v?.takeIf { it.isNotBlank() }?.trim()
        } catch (_: Throwable) { null }
    }
}

private enum class Screen {
    HOME, PROFILE, SETTINGS;

    companion object {
        fun fromPath(path: String?): Screen {
            val normalized = path
                ?.trim('/')
                ?.lowercase()
                ?: ""
            return when (normalized) {
                "", "index", "home" -> HOME
                "profile" -> PROFILE
                "settings" -> SETTINGS
                else -> HOME
            }
        }
    }
}

private enum class ConsentStatus(val label: String) {
    NOT_DETERMINED("Not Determined"),
    AUTHORIZED("Authorized"),
    DENIED("Denied");
}

@Composable
private fun HomeScreen(
    initialized: Boolean,
    lastPayload: LinkPayload?,
    userId: String,
    onUserIdChange: (String) -> Unit,
    onInitialize: () -> Unit,
    onTrack: (String, Map<String, Any?>?) -> Unit,
    onSetUserId: () -> Unit,
    onClearUserId: () -> Unit,
    onClaimDeferred: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "LinkMe Example",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        LinkInfoCard(lastPayload)

        SectionCard(title = "SDK Features") {
            Text(
                text = "Interact with the LinkMe SDK without leaving the sample app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onTrack("button_click", mapOf("screen" to "home")) }
            ) {
                Text("Track Event: 'button_click'")
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onTrack("test_event", mapOf("feature" to "home")) }
            ) {
                Text("Track Event: 'test_event'")
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onInitialize
            ) {
                Text(if (initialized) "Reinitialize SDK" else "Initialize SDK")
            }

            if (userId.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = userId,
                        onValueChange = onUserIdChange,
                        label = { Text("Enter User ID") }
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = onSetUserId,
                        enabled = userId.isNotBlank()
                    ) {
                        Text("Set")
                    }
                }
            } else {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClearUserId
                ) {
                    Text("Clear User ID")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClaimDeferred
            ) {
                Text("Claim Deferred Link")
            }
        }

        SectionCard(title = "Navigation") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.PROFILE) }
            ) {
                Text("Go to Profile")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.SETTINGS) }
            ) {
                Text("Go to Settings")
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    lastPayload: LinkPayload?,
    onTrack: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        LinkInfoCard(lastPayload)

        SectionCard(title = "Navigation Test") {
            Text(
                text = "This screen can be opened via LinkMe deep links with path 'profile'.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onTrack
            ) {
                Text("Track: 'profile_viewed'")
            }
        }

        SectionCard(title = "Navigation") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.HOME) }
            ) {
                Text("Go to Home")
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.SETTINGS) }
            ) {
                Text("Go to Settings")
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    baseUrl: String,
    appId: String,
    consentStatus: ConsentStatus,
    lastPayload: LinkPayload?,
    onRequestConsent: () -> Unit,
    onTrack: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        LinkInfoCard(lastPayload)

        SectionCard(title = "LinkMe Configuration") {
            LabelValueRow(label = "Base URL", value = baseUrl)
            LabelValueRow(label = "App ID", value = appId)
            LabelValueRow(label = "Advertising Consent", value = consentStatus.label)

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRequestConsent
            ) {
                Text("Request Advertising Consent")
            }
        }

        SectionCard(title = "Testing") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onTrack
            ) {
                Text("Track: 'settings_viewed'")
            }
        }

        SectionCard(title = "Navigation") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.HOME) }
            ) {
                Text("Go to Home")
            }

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onNavigate(Screen.PROFILE) }
            ) {
                Text("Go to Profile")
            }
        }
    }
}

@Composable
private fun LinkInfoCard(payload: LinkPayload?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Last Link Payload",
                style = MaterialTheme.typography.titleMedium
            )

            if (payload == null) {
                Text(
                    text = "No link received yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                payload.linkId?.let { LabelValueRow("Link ID", it) }
                payload.path?.let { LabelValueRow("Path", it) }

                payload.params?.takeIf { it.isNotEmpty() }?.let { params ->
                    Text(
                        text = "Params:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        params.toSortedMap().forEach { (key, value) ->
                            LabelValueRow(label = key, value = value)
                        }
                    }
                }

                payload.utm?.takeIf { it.isNotEmpty() }?.let { utm ->
                    Text(
                        text = "UTM:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        utm.toSortedMap().forEach { (key, value) ->
                            LabelValueRow(label = key, value = value)
                        }
                    }
                }

                payload.custom?.takeIf { it.isNotEmpty() }?.let { custom ->
                    Text(
                        text = "Custom:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        custom.toSortedMap().forEach { (key, value) ->
                            LabelValueRow(label = key, value = value)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AdvertisingConsentDialog(
    onGrant: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Advertising Consent") },
        text = {
            Text(
                text = "Allow LinkMe to use the advertising identifier to improve attribution?",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text("Don't Allow")
            }
        }
    )
}
