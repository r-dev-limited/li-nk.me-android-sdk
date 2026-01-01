# LinkMe Android SDK

Android SDK for LinkMe — deep linking and attribution.

- **Main Site**: [li-nk.me](https://li-nk.me)
- **Documentation**: [Android Setup](https://li-nk.me/docs/developer/setup/android)
- **Package**: [Maven Central](https://central.sonatype.com/artifact/me.li-nk/linkmekit)

## Installation

```kotlin
implementation("me.li-nk:linkmekit:0.2.7")
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

For full documentation, guides, and API reference, please visit our [Help Center](https://li-nk.me/docs/help).

## Deferred linking (Android)
- Deterministic claim uses **Play Install Referrer** (`/api/install-referrer`).
- Fallback uses probabilistic fingerprint claim (`/api/deferred/claim`).

## License

Apache-2.0
