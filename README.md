# LinkMe Android SDK

Android SDK for LinkMe — deep linking and attribution.

- **Main Site**: [li-nk.me](https://li-nk.me)
- **Documentation**: [Android Setup](https://li-nk.me/docs/developer/setup/android)
- **Package**: [Maven Central](https://central.sonatype.com/artifact/me.li-nk/linkmekit)

## Installation

```kotlin
implementation("me.li-nk:linkmekit:0.2.12")
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

## Manual deep-link config mapping

Use these values for your Android deep-link setup:

```json
{
  "hosts": ["links.yourco.com"],
  "associatedDomains": ["links.yourco.com"],
  "schemes": ["yourapp"]
}
```

What each field does and why it must be set:

- `hosts`: HTTPS deep-link domain(s), mapped to verified App Links intent filters.
- `associatedDomains`: keep aligned with `hosts`; this is the same domain set used for verification on Android.
- `schemes`: fallback custom URL scheme(s) for explicit scheme opens.

Required: if `hosts` / `schemes` are not declared in `AndroidManifest.xml`, LinkMe links will not route reliably into your app.

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
