# LinkMeKit Android SDK

Android SDK for LinkMe - Deep linking and attribution tracking.

## Installation

Add JitPack repository to your root `build.gradle`:

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Add the dependency:

```gradle
dependencies {
    implementation 'com.github.r-dev-limited:li-nk.me-android-sdk:0.1.0'
}
```

## Usage

```kotlin
import me.link.sdk.LinkMe

// Initialize
LinkMe.initialize(
    context = this,
    baseUrl = "https://your-domain.com",
    appId = "your-app-id",
    appKey = "your-app-key"
)

// Handle deep links
LinkMe.handleDeepLink(uri) { payload ->
    // Handle the link payload
    payload?.let {
        println("Link ID: ${it.linkId}")
        println("Path: ${it.path}")
        println("Params: ${it.params}")
    }
}

// Track events
LinkMe.trackEvent("purchase", mapOf("amount" to 99.99))
```

## Requirements

- Android API 23+ (Android 6.0+)
- Kotlin 1.9+

## License

Apache-2.0
