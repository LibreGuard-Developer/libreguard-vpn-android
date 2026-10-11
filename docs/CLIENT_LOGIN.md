# Backend-tracked Android sign-in

This implementation uses ManagementPanel's `/api/login/client` endpoints for password and Google sign-in, linking, TOTP/recovery verification, and device-limit continuation. Deploy and enable those backend endpoints before releasing this Android version. There is no automatic fallback to legacy login.

The complete API, migration, security and activation contract is in ManagementPanel's `docs/ANDROID_CLIENT_LOGIN.md`. Its configuration flag `Authentication:ClientLogin:Android:Enabled` defaults to false. The existing Google web client ID is supplied by the backend along with a per-attempt nonce; configure the app's existing Android presenter IDs on the backend.

`ClientLoginViewModel` is shared at the activity level. Its Google provider is attached once by `AppNavigation`, outside the navigation destinations, and detached when the host Activity composition is disposed. Login screen transitions cannot clear that shared attachment. The provider checks for a live Activity before creating a backend attempt. Pending states and capabilities remain in memory across rotation; process death restarts authentication. Successful MFA followed by a device limit advances to device selection. Only `signedIn` persists tokens for the original device. Device removal posts the continuation capability and selected database IDs; it never creates probe devices or resends passwords/Google tokens.

Google uses Credential Manager's explicit Sign in with Google option. Logout awaits credential-state clearing and always clears local session data. The public authentication client has no session authenticator, automatic connection retries or redirects. Passwords, provider tokens and pending capabilities are excluded from saved UI state and diagnostics. In-app linking requires the existing account's password and any configured MFA, followed by a separate confirmation. Apple-only accounts use website linking.

Credentials is pinned to 1.6.0 and Google ID to 1.2.1. Google ID's Kotlin 2.4 metadata requires upgrading the Kotlin and Compose compiler plugins together; this checkout uses Kotlin 2.4.20 and the supported compiler-options JVM target setting.

## Verification

Verified with `./gradlew :app:assembleDebug :app:testDebugUnitTest`: the complete debug app build succeeded, including OpenVPN native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. All 54 app unit tests passed (zero failures, errors or skips), including 5 client-login contract tests and 6 client-login ViewModel tests. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

`git submodule status --recursive` fails separately because `.gitmodules` has no mapping for `design-reference`. Gradle settings do not include `design-reference`, so this mapping error did not cause the app build failure.

The reported fatal build error was OpenVPN's deprecated `kotlinOptions.jvmTarget` setting under Kotlin 2.4.20. Its local build script now uses typed `kotlin.compilerOptions` with JVM 17; the submodule revision is unchanged. Other legacy Gradle/AGP API diagnostics remain warnings. The parent build script directs StrongSwan output into the ignored root `build/strongswan-android` directory; StrongSwan source and Git status remain unchanged.

The native compiler also emitted warnings in vendored OpenSSL code on 32-bit ABIs, including buffer-overflow warnings in `crypto/ec/ecp_sm2p256.c` and atomic-alignment warnings in `crypto/threads_pthread.c`. These did not fail the build and need separate native-code review before release.

Real-device staging verification remains required: multiple Google accounts, repeated logout/login, explicit account switching, rotation during the Google prompt and subsequent steps, cancellation, process death, interrupted responses, password/Google with TOTP/recovery at Free/Pro limits, and linking confirmation. Check that no probe devices appear and no session credentials are stored before the signed-in outcome.

## Activity attachment regression

On 2026-10-11, all 16 focused JVM tests passed (`ClientLoginViewModelTest` and `ClientLoginContractTest`) after removing temporary Google sign-in diagnostics. The lifecycle instrumentation tests also compiled successfully.

A missing Activity is rejected before calling the begin endpoint.

On 2026-10-10, all 4 `GoogleCredentialActivityHostTest` instrumentation tests passed on a OnePlus LE2123 running Android 14: overlapping retiring/replacement login screens, three Google login/logout cycles through navigation, host replacement/disposal, and provider validation with a stale old-Activity detach. Device tests used an isolated temporary package and fake backend/Google responses; they did not exercise real OAuth or consume backend attempts. The test packages were removed after verification.
