package nz.co.rdev.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.link.sdk.LinkMe
import me.link.sdk.LinkPayload
import android.content.pm.PackageManager
import android.os.Build
import android.content.pm.ApplicationInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Resolve base URL:
        // 1) BuildConfig.LINKME_BASE_URL (from env/Gradle prop)
        // 2) AndroidManifest meta-data "LinkMeBaseURL"
        // 3) Default to emulator host
        val resolvedBaseUrl = resolveBaseUrl() ?: "http://10.0.2.2:8080"

        // Capture initial intent (cold start). If not configured yet, SDK queues it until init.
        LinkMe.shared.handleIntent(intent)

        setContent {
            var lastPayload by remember { mutableStateOf("(none)") }
            var baseUrl by remember { mutableStateOf(resolvedBaseUrl) }
            var initialized by remember { mutableStateOf(false) }

            // Listen for stream events
            LinkMe.shared.addListener { payload ->
                lastPayload = "linkId=${payload.linkId ?: "nil"} utm=${payload.utm ?: emptyMap<String, String>()}"
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "LinkMe Android Example")
                        Text(text = "Base URL: $baseUrl")
                        Text(text = "Last payload: $lastPayload")
                        Button(onClick = {
                            // Initialize SDK and attempt deferred claim (parity with iOS flow)
                            LinkMe.shared.configure(
                                context = applicationContext,
                                config = LinkMe.Config(
                                    baseUrl = baseUrl,
                                    appId = "demo",
                                    appKey = "LKDEMO-0001-TESTKEY-LOCAL",
                                    enablePasteboard = false,
                                    sendDeviceInfo = true,
                                    includeVendorId = true,
                                    includeAdvertisingId = false,
                                )
                            )
                            initialized = true
                            LinkMe.shared.getInitialLink { /* optional UI update */ }
                            LinkMe.shared.claimDeferredIfAvailable(applicationContext) { }
                        }) {
                            Text(if (initialized) "Reinitialize" else "Initialize")
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
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
