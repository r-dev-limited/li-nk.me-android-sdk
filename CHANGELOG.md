# Changelog

All notable changes to the LinkMe Android SDK.

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
