# App updates

LibreGuard uses Google Play flexible in-app updates. The app checks Play directly; this feature does not use LibreGuard API endpoints or require backend changes.

## User flow

- Settings contains an Updates card between Preferences and Support.
- Home shows an available-update banner. Dismissing it hides that version's reminder for 24 hours; Settings remains available.
- Tapping Update opens Google's consent UI. The download continues while the app and VPN remain usable.
- Download progress and readiness appear in both places. A ready-to-install snackbar offers an explicit restart action.
- Restarting while connected or connecting requires confirmation that the VPN may be interrupted. Connection restoration follows existing app settings.
- A canceled consent prompt is harmless and can be retried. Unavailable in-app updates fall back to the Play listing or its browser page.

## Implementation

The activity owns one AppUpdateViewModel shared by Home and both Settings entry points. GooglePlayUpdateClient owns an application-context AppUpdateManager and its install listener. The root AppUpdateHost owns the activity result launcher, update snackbar, and restart confirmation.

Foreground entry and Settings entry refresh status. Concurrent checks are coalesced; listener events and user operations invalidate older results. Every consent attempt uses fresh, single-use AppUpdateInfo. Only the per-version banner dismissal is persisted; Play recovers download and installation status after recreation.

The update client's application context never retains the activity or its launcher. Missing Play Store and account/device eligibility failures remain distinct from an explicit no-update response.

## Local verification

Run the update state regression tests with:

    .\gradlew.bat :app:testDebugUnitTest --tests net.libreguard.vpn.viewmodel.AppUpdateViewModelTest

Device checks cover GooglePlayUpdateClientTest, AppUpdateUiTest, and DashboardAdaptiveLayoutTest. GooglePlayUpdateClientTest uses Google's FakeAppUpdateManager and performs no real download or installation. UI checks cover dark and light themes, enlarged text, confirmation, dismissal, and simultaneous Home notices.

Run device tests on a disposable test installation: Gradle's connected test cleanup can remove the target app and its local data. To preserve an existing development installation, install the debug and test APKs yourself and invoke the instrumentation runner directly; remove only the test package afterward.

## Verify before release

A real Google Play update needs an eligible account, the same application ID and signing identity, and a higher version code. A local debug installation may offer the Play listing fallback.

Use [Google's internal app sharing procedure](https://developer.android.com/guide/playcore/in-app-updates/test):

1. Upload and install a build containing this integration through internal app sharing.
2. Upload a second build with a higher version code.
3. Open the newer sharing link on the device without installing it from that listing.
4. Reopen the older app and verify availability, consent cancellation, background progress, and returning after a download.
5. Verify Later leaves the VPN session intact, then explicitly confirm Restart and update.
6. Confirm the installed version changes and check normal connection restoration for the configured VPN protocol and auto-connect/kill switch preferences.

Users first acquire this integration through a normal app update; subsequent Play releases can then be offered in the app. Play determines availability per account, device, track, and rollout.

Dependency versions follow the [official integration guide](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java). Google Play In-App Updates is included in the SDK notices.
