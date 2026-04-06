# LinkMe Android SDK

Deep linking, deferred deep linking, and attribution for Android apps.

[![Maven Central](https://img.shields.io/maven-central/v/me.li-nk/linkmekit)](https://central.sonatype.com/artifact/me.li-nk/linkmekit)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

- [Main Site](https://li-nk.me)
- [Setup Guide](https://help.li-nk.me/hc/link-me/en/developer-setup/android-setup-guide)
- [SDK Reference](https://help.li-nk.me/hc/link-me/en/sdks/android-sdk-reference)
- [Help Center](https://help.li-nk.me/hc/link-me/en)

## Quick start

### 1. Prerequisites

- A LinkMe account with at least one app configured
- Android package name and SHA-256 fingerprint added in the LinkMe portal
- API keys (`appId` and `appKey`) from **App Settings > API Keys**

### 2. Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}

// app/build.gradle.kts
dependencies {
  implementation("me.li-nk:linkmekit:0.2.13")
}
```

### 3. Configure App Links

Add intent filters to your `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity" android:exported="true">
  <!-- HTTPS App Links (verified) -->
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="links.yourco.com" />
  </intent-filter>

  <!-- Custom scheme (fallback) -->
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourapp" />
  </intent-filter>
</activity>
```

LinkMe hosts `assetlinks.json` automatically for domains configured in App Settings.

### 4. Initialize the SDK

```kotlin
class App : Application() {
  override fun onCreate() {
    super.onCreate()

    LinkMe.shared.configure(
      context = this,
      config = LinkMe.Config(
        appId = BuildConfig.LINKME_APP_ID,
        appKey = BuildConfig.LINKME_APP_KEY,
        sendDeviceInfo = true,
        includeAdvertisingId = false,
        debug = BuildConfig.DEBUG,
      ),
    )
  }
}
```

### 5. Handle links

```kotlin
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LinkMe.shared.handleIntent(intent)

    // Cold-start link
    LinkMe.shared.getInitialLink { payload ->
      payload?.let(::routeUser)
    }

    // Deferred deep link (first install)
    LinkMe.shared.claimDeferredIfAvailable(this) { deferred ->
      deferred?.let(::routeUser)
    }

    // Live links while app is open
    LinkMe.shared.addListener { payload ->
      routeUser(payload)
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    LinkMe.shared.onNewIntent(intent)
  }
}
```

## Deferred deep linking

The SDK supports two strategies for attributing installs:

1. **Install Referrer** (deterministic) — uses the Play Install Referrer API via `/api/install-referrer`
2. **Fingerprint** (probabilistic fallback) — calls `/api/deferred/claim` when the referrer is unavailable

Both are handled automatically by `claimDeferredIfAvailable()`.

## API reference

| Method | Description |
| --- | --- |
| `configure(context, config)` | Initialize the SDK |
| `handleIntent(intent)` / `onNewIntent(intent)` | Feed intents for link parsing |
| `getInitialLink(callback)` | Get the payload that opened the app |
| `addListener(handler)` | Subscribe to future payloads (returns unsubscribe function) |
| `claimDeferredIfAvailable(context, callback)` | Claim deferred deep link on first install |
| `track(event, props?)` | Send analytics events |
| `setUserId(id)` | Associate a user ID |
| `setAdvertisingConsent(granted)` | Toggle Advertising ID usage |

## Troubleshooting

- **Verify domain status:** `adb shell pm get-app-links --user cur com.example.app`
- **Re-verify after changes:** `adb shell pm verify-app-links --re-verify com.example.app`
- **Force allow on test device:** `adb shell pm set-app-links --package com.example.app --allow all`
- **Links open Play Store?** Check that `android:autoVerify="true"` is set, the app was rebuilt after manifest changes, and `assetlinks.json` is accessible.

## Example app

The `example-app/` directory contains a runnable sample. Set `LINKME_APP_ID` and `LINKME_APP_KEY` in `local.properties`, then run in Android Studio.

## License

Apache-2.0
