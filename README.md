# LinkMe Android SDK

Android SDK for LinkMe — deep linking and attribution.

- **Main Site**: [li-nk.me](https://li-nk.me)
- **Documentation**: [Android Setup](https://li-nk.me/docs/developer/setup/android)
- **Package**: [Maven Central](https://central.sonatype.com/artifact/me.li-nk/linkmekit)

## Installation

```kotlin
implementation("me.li-nk:linkmekit:0.2.9")
```

## Basic Usage

```kotlin
LinkMe.shared.configure(
  context = applicationContext,
  config = LinkMe.Config(
    appId = BuildConfig.LINKME_APP_ID,
    appKey = BuildConfig.LINKME_APP_KEY,
    debug = BuildConfig.DEBUG
  )
)
```

## Manual deep-link setup (equivalent to React Native plugin)

If you are comparing to React Native Expo plugin config:

```json
{
  "hosts": ["links.yourco.com"],
  "associatedDomains": ["links.yourco.com"],
  "schemes": ["yourapp"]
}
```

configure Android manually as:

- `hosts` / `associatedDomains` -> HTTPS App Links intent filter:

```xml
<intent-filter android:autoVerify="true">
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="https" android:host="links.yourco.com" />
</intent-filter>
```

- `schemes` -> custom scheme intent filter:

```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="yourapp" />
</intent-filter>
```

## API

| Method | Description |
| --- | --- |
| `configure(context, config)` | Initialize the SDK. |
| `handleIntent(intent)` / `onNewIntent(intent)` | Feed intents for link parsing. |
| `getInitialLink(callback)` | Get the payload that opened the app. |
| `addListener(handler)` | Subscribe to future payloads. Returns unsubscribe function. |
| `claimDeferredIfAvailable(context, callback)` | Install Referrer + fingerprint fallback. |
| `track(event, props?)` | Send analytics events. |
| `setUserId(id)` | Associate a user ID. |
| `setAdvertisingConsent(granted)` | Toggle Advertising ID usage. |

## Deferred linking (Android)
- Deterministic claim uses **Play Install Referrer** (`/api/install-referrer`).
- Fallback uses probabilistic fingerprint claim (`/api/deferred/claim`).

For full documentation, guides, and API reference, please visit our [Help Center](https://li-nk.me/docs/help).

## License

Apache-2.0
