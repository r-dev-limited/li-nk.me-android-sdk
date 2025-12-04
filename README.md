# LinkMe Android SDK

Android SDK for LinkMe — deep linking and attribution.

- Repo docs: ../../docs/help/docs/setup/android.md
- Hosted docs: https://li-nk.me/resources/developer/setup/android
- Troubleshooting: See [Android Troubleshooting](https://li-nk.me/resources/developer/setup/android#troubleshooting) section in docs

License: Apache-2.0

## Installation

Add JitPack to your repositories (usually in `settings.gradle`):

```kotlin
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}
```

Then declare the dependency:

```kotlin
dependencies {
  implementation("com.github.r-dev-limited:li-nk.me-android-sdk:0.1.2")
}
```

See builds at [jitpack.io/#r-dev-limited/li-nk.me-android-sdk](https://jitpack.io/#r-dev-limited/li-nk.me-android-sdk).
