# Changelog

All notable changes to the LinkMe Android SDK.

## Unreleased

- Upgrade Kotlin and the Compose compiler to 2.3.21 and migrate JVM targets to the compilerOptions DSL.
- Upgrade AndroidX annotations to 1.11.0, activity-compose to 1.13.0, Compose BOM to 2026.06.01, AndroidX JUnit to 1.3.0, and Espresso to 3.7.0.
- Keep AGP 8.13.2, compile SDK 36, and core-ktx 1.18.0; newer Compose and Core artifacts require AGP 9.1 and compile SDK 37.

## 0.2.14

- Replaces hand-written JSON parsing with structured JSON and adds parser regression tests.
- Bounds Install Referrer settlement with fallback, gates device collection, modernizes the Gradle toolchain, and supports clearing user identity.
- Preserves `cid`/`duplicate` attribution fields and delivers listeners on the main looper.
- Adds public API R8 keep rules, a minified release verification variant, and an emulator instrumentation smoke test for the example app.
- Fixes the example app's self-referential XML theme resource.

## 0.2.13

- Tightens deferred claim parsing to LinkMe hosts/token format only.
- Clears consumed Install Referrer tokens after successful deferred claim.

## 0.2.12

- Adds support for force-web redirect payloads (`forceRedirectWeb=true` + `webFallbackUrl`).
- Improved reliability of Install Referrer claim flow.

## 0.2.11

- Internal reliability improvements for link resolution.

## 0.2.9

- Improved handling of edge redirect scenarios.
- Better link lifecycle and maintenance behavior.

## 0.2.8

- General stability and bug fixes.

## 0.2.7

- Adds `isLinkMe` and `url` fields to payloads to distinguish LinkMe-managed links from basic App Links.

## 0.2.5

- Relaxes Install Referrer parsing to accept branded LinkMe domains and structured tokens.

## 0.2.4

- SDK alignment release across all platforms.

## 0.2.3

- Internal improvements to Install Referrer claim handling.

## 0.2.2

- Install Referrer claims now use the dedicated `/api/install-referrer` endpoint.

## 0.2.1

- Adds `debug` flag to `LinkMe.Config` for verbose instrumentation.
- Fingerprint-based deferred claim improvements.

## 0.2.0

- Deferred deep linking support via Play Install Referrer (deterministic) and fingerprint fallback (probabilistic).
- Analytics event tracking with `track()`.
- User ID association with `setUserId()`.
- Advertising consent toggle with `setAdvertisingConsent()`.

## 0.1.2

- Initial public release.
- Core deep linking: `configure`, `handleIntent`, `getInitialLink`, `addListener`.
