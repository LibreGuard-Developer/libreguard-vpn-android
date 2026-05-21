# Third-Party Licenses

This project includes code from the following open-source projects and libraries.
This file is a practical summary for repository distribution and release review; bundled upstream license files remain authoritative.

## Bundled / vendored components

### ics-openvpn
- License: GPLv2 with upstream linking exceptions for OpenSSL and Apache-licensed libraries
- Source: https://github.com/schwabe/ics-openvpn
- Fork in this repository: `ics-openvpn/`
- Local license file: `ics-openvpn/doc/LICENSE.txt`
- Copyright: Arne Schwabe and contributors

### strongSwan Android/frontend code
- License: GPLv2-or-later with upstream special exceptions documented by strongSwan
- Source: https://github.com/strongswan/strongswan
- Fork in this repository: `strongswan/`
- Local license files: `strongswan/LICENSE`, `strongswan/COPYING`
- Copyright: strongSwan contributors

## Key direct application dependencies

### Kotlin Standard Library
- License: Apache License 2.0
- Source: https://github.com/JetBrains/kotlin

### AndroidX / Jetpack Compose / Material 3
- License: Apache License 2.0
- Source: https://android.googlesource.com/platform/frameworks/support

### Retrofit / OkHttp / MockWebServer
- License: Apache License 2.0
- Source: https://github.com/square/retrofit and https://github.com/square/okhttp

### Gson
- License: Apache License 2.0
- Source: https://github.com/google/gson

### ZXing Core
- License: Apache License 2.0
- Source: https://github.com/zxing/zxing

### Bouncy Castle Provider
- License: Bouncy Castle Licence
- Source: https://www.bouncycastle.org/

## Google-managed SDKs used by the app

The app also integrates SDKs published by Google, including Firebase Crashlytics, Google Sign-In, and Google Play Billing. Review the license/terms bundled with those SDKs and their upstream package metadata before each public release.

- Firebase Android SDK: https://github.com/firebase/firebase-android-sdk
- Google Play services auth: https://developers.google.com/android/guides/overview
- Google Play Billing: https://developer.android.com/google/play/billing

## Dependency inventory source

The current direct dependency declarations for the Android app are defined in:

- `app/build.gradle.kts`
- `gradle/libs.versions.toml`

Before each release, regenerate and review your resolved dependency graph so this file stays in sync with actual shipped artifacts.

