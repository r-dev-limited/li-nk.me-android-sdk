# LinkMe Android SDK

Deep linking, deferred deep linking, and attribution for Android apps.

[![JitPack](https://jitpack.io/v/r-dev-limited/li-nk.me-android-sdk.svg)](https://jitpack.io/#r-dev-limited/li-nk.me-android-sdk)
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
  implementation("com.github.r-dev-limited:li-nk.me-android-sdk:0.2.14")
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
| `setUserId(id)` | Associate a user ID; pass `null` to clear it |
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

## Claim metadata (unreleased)

Updated Edge claims can include `claimId`, `claimMethod`, `matchConfidence`,
`attributionStatus`, `contractVersion`, and `claimPolicyVersion`. These optional
fields are preserved in the payload. Treat `fingerprint_exact` / `inferred` as
heuristic attribution, not a verified install or authenticated identity.
Replaying a CID returns its saved outcome; `duplicate` is a deprecated legacy
projection and must not suppress a new link navigation based on shared IP/UA.

Supply your app ID for deferred matching. New apps have fingerprint fallback
disabled; enable inferred attribution explicitly in app settings if appropriate.
Enabled matching requires a unique exact IP/UA candidate within 30 minutes.
Explicit CID and Play referrer claims use the deterministic 72-hour token window,
subject to link expiry and current app/link/domain policy.


Fingerprint matches return campaign/claim metadata with `navigationAllowed: false`.
They withhold the CID, link ID, path, custom data, query parameters and destination
URLs. The SDK suppresses app-routing callbacks and automatic browser launches for
these responses. Web callers can inspect returned metadata explicitly; native
routing APIs may return no link. A claim receipt is not a redeemable link token.
An independently supplied valid CID/referrer can still authorize navigation with
`navigationAllowed: true`; it retains the original claim receipt and attribution
classification rather than generating another claim.

## Tracking credentials and acceptance (unreleased Edge)

Configure an app ID and a key with `can_ingest` for client telemetry. In Portal,
select **Client analytics and link reading**; it cannot manage links, settings,
or keys, and cannot read private management/visitor diagnostics. A read-only key cannot ingest events. Both event routes require validated
credentials even when link resolution permits optional keys.

The existing `track` API uses the legacy `/api/app-events` route. Its return does
not prove delivery, durable webhook acknowledgement, or a verified conversion.
The SDK currently has no durable telemetry queue and does not promise event-ID
retry semantics. New Edge stores legacy events with app scope; an omitted event
ID receives a server ID, so calling `track` again creates another event. Optional
`userId` is a caller-provided pseudonym, not authenticated identity.

For explicit persistence receipts and safe HTTP retries, `/api/app-events/v1`
accepts a versioned envelope with a stable `eventId`, `occurredAt`, SDK/version,
platform, consent and structured properties. Reuse the same event ID and content
on retry; a successful receipt is 202, changed content is 409. The route requires
`can_ingest`, bounds the body to 32 KiB, and rate-limits by app. Claim events are
server-owned. This SDK has not yet adopted the versioned sender/queue contract.

## Request lifecycle (unreleased)

`LinkMe.Config` accepts `requestTimeoutMs` with a 15000 ms default. Positive values
up to 2147483647 are accepted; invalid overrides throw `IllegalArgumentException`.
The deadline includes queued work, optional device collection, connection and
response-body I/O. Tasks and workers are bounded. `cancelPendingRequests()`
cancels active HTTP/referrer discovery work and invalidates queued results.
`dispose()` also clears configuration, listeners, pending URLs, identity and
payload context. Reconfiguration clears app context/identity/payload state while
keeping listeners; identity changes invalidate pending work. Later requests can
run after cancellation or reconfiguration.

All payload callbacks, including `getInitialLink`, run on Android main. Listeners
receive results in subscription order without automatic replay; use
`getInitialLink` to read the current payload. Removal is idempotent and belongs to
one subscription, even if the same handler was registered twice. A listener's
reentrant lifecycle change stops later delivery and the old completion payload;
a listener exception does not prevent other listeners or claim completion.
Preconfigure URLs are queued once, and stale queued results cannot cross apps.

Install Referrer service discovery uses at most six seconds (or the shorter
configured deadline). Binder reading runs on a background worker. Once a valid
referrer is obtained, the referrer HTTP claim gets its own request deadline;
fingerprint fallback cannot race that claim. Unavailable/timed-out discovery or
an unsuccessful referrer claim may fall back once. Cancellation requests closure of the
service and does not start fallback. This is not evidence of a real Play install.

HTTP credentials, identity, attribution and optional device policy are captured
for the initiating app. A cancelled optional read cannot later start HTTP under a
new app's configuration. No automatic retry is added, and cancelling cannot
retract a server mutation already accepted. Tracking still uses its existing
best-effort sender; the versioned queue/receipt contract remains separate work.


The local core also exposes lifecycle protocol V1 for wrappers that discover
support at runtime. Commands and payload frames carry an opaque owner and client
generation; control retries do not cancel newer work, and foreign/stale owners
cannot read payloads. Direct typed-core lifecycle/identity/changed advertising
consent calls withdraw wrapper ownership. Changed advertising consent also
invalidates pending work. This does not implement all collection controls.
Flutter public Dart integration with the native channel adapters and coordinated
published core dependencies remain pending; the current Flutter pins do not provide this protocol.
