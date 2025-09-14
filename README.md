## LinkMe Android SDK (Kotlin)

### Installation
- Gradle (Maven Central): coming soon
- Source integration (temporary)
  - Clone this repository and import the `linkmekit` module into your Android project
  - Add a dependency on the module in your app module `build.gradle`:
```gradle
dependencies {
  implementation project(":linkmekit")
}
```

### Quick start
```kotlin
// Initialize (e.g., in Application or at app startup)
LinkMe.shared.configure(
  context,
  LinkMe.Config(
    baseUrl = "https://your-link-domain.tld",
    appId = "<APP_ID>",
    appKey = "<APP_KEY>",
    sendDeviceInfo = true,
    includeVendorId = true,
    includeAdvertisingId = false // call setAdvertisingConsent(true) after user consent
  )
)

// Handle initial intent in your Activity
override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  LinkMe.shared.handleIntent(intent)
}

// Handle subsequent intents (while your Activity is alive)
override fun onNewIntent(intent: Intent?) {
  super.onNewIntent(intent)
  LinkMe.shared.onNewIntent(intent)
}

// Get initial link and listen for subsequent ones
LinkMe.shared.getInitialLink { payload -> /* route initial */ }
val unsubscribe = LinkMe.shared.addListener { payload -> /* subsequent links */ }

// Deferred deep linking (Install Referrer)
LinkMe.shared.claimDeferredIfAvailable(context) { payload -> /* first open */ }

// Optional analytics
LinkMe.shared.track("open")
// After consent, if you plan to use Advertising ID
LinkMe.shared.setAdvertisingConsent(true)
```

### Manifest configuration
```xml
<intent-filter android:autoVerify="true">
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="https" android:host="your-domain.tld" />
</intent-filter>
<!-- Optional custom scheme -->
<intent-filter>
  <action android:name="android.intent.action.VIEW" />
  <category android:name="android.intent.category.DEFAULT" />
  <category android:name="android.intent.category.BROWSABLE" />
  <data android:scheme="your.custom.scheme" />
</intent-filter>
```

### Dependencies
- `com.android.installreferrer:installreferrer:2.2` for deferred deep linking
- Optional: `com.google.android.gms:play-services-ads-identifier:18.0.1` (AD_ID permission required) if using Advertising ID

### Example app
- See `example-app/` in this repository for a runnable Compose example wired to the SDK

### Changelog
- See `CHANGELOG.md` (GitHub Releases also document changes)

### License
- Apache License 2.0
