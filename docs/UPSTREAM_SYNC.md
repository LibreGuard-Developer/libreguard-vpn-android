# Upstream VPN synchronization — 2026-10-06

The OpenVPN and strongSwan forks were updated by merging their fork remotes and
original upstream `master` branches. Existing LibreGuard commits remain in the
history. Recovery branches named `codex/pre-upstream-sync-20261006` retain the
previous app and submodule revisions.

| Component | Previous app revision | Upstream revision merged |
| --- | --- | --- |
| OpenVPN for Android | `dacbac8843d8661c0ccf52726d3d2682d3e57c16` | `b13ce20f` (69 upstream commits) |
| strongSwan | `802cf04bb7ae4d0b518eb37791be062778bcd960` | `a718759e5` (503 upstream commits) |

## Integration retained

- OpenVPN remains an Android library. The app continues to select its
  `skeleton` / `ovpn23` variant, compile against SDK 36, and use NDK
  `28.0.13004108` with Java/Kotlin target 17.
- LibreGuard's OpenVPN profile naming and country extraction remain intact.
  Upstream's modern SWIG source registration replaces the deprecated variant
  API; the Windows AIDL header correction remains in place.
- OpenVPN's new global preferences default to unrestricted operation when an
  embedded host does not run `ICSOpenVPNApplication`. This prevents notification
  creation from throwing and keeps manual disconnect available. Explicit
  force-connection and initial-import restrictions can still override defaults.
- LibreGuard's strongSwan scheduler, queue, and scheduler tests are unchanged.
  Its library packaging, certificate support, and other fork changes remain.
- The updated Asio headers now live under `asio/include`; the native build uses
  that path rather than the previous `asio/asio/include` directory.
- Management socket paths are escaped with the existing OpenVPN escaping
  helper, fixing the configuration round-trip test on Windows.
- The host limits embedded OpenVPN compilation to its existing ARM ABIs.
  Standalone builds of the fork retain their full ABI support.
- The parent settings enable the dependency-update plugin required by the new
  OpenVPN build script. The shared catalog includes upstream's JSON test library.

## Native dependencies

OpenVPN's nested submodules were checked out at the exact revisions selected by
upstream, including the new `fmt` dependency. OpenVPN, OpenVPN 3, OpenSSL, and Asio
were updated; LZ4 and mbedTLS retained their upstream-selected revisions.

The strongSwan native rebuild uses NDK `28.0.13004108`, its existing OpenSSL
3.5.4 static libraries, and the merged source. The generated version metadata is
`6.1.0`. ASN.1 identifiers were regenerated with upstream's `oid.pl` using LF input.
The generated settings parsers and proposal table came from the official
`strongswan-6.1.0.tar.bz2` archive after verifying that all corresponding parser
and table inputs exactly match the merged source, allowing the Windows build
without installing Bison, Flex, or gperf.

Native outputs are built outside the source checkout in
`build/strongswan-native`, then copied into the fork's existing tracked
`src/frontends/android/app/src/main/jniLibs` directories after a successful build.
Both ARM architectures used by the app and both existing x86 architectures are
rebuilt.

## Verification

The final normal build passed:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :strongswan-android:testDebugUnitTest :ics-openvpn:main:testSkeletonOvpn23DebugUnitTest
```

| Suite | Passed | Failures / errors / skips |
| --- | ---: | --- |
| App | 82 | 0 / 0 / 0 |
| strongSwan | 99 | 0 / 0 / 0 |
| OpenVPN | 40 | 0 / 0 / 0 |
| Total | 221 | 0 / 0 / 0 |

The final APK at `app/build/outputs/apk/debug/app-debug.apk` contains both
`arm64-v8a` and `armeabi-v7a`. All 16 packaged strongSwan libraries match the
rebuilt fork libraries after normalizing symbol stripping. OpenVPN's native
version metadata identifies the newly selected OpenVPN (`e6bc0216`) and OpenVPN 3
(`c51d8d21`) revisions for both ARM architectures. Every packaged arm64 native
library passed the 16 KB ELF load-alignment check. All 32 refreshed strongSwan
libraries passed version, scheduler JNI entrypoint, and alignment checks.

Final fork revisions:

- OpenVPN: `28fdc264ce06bbb8ee7dddb4470c8b68af6cce56`
- strongSwan: `29f7a116f7fbd0d1d0d1095028e142df96b40f75`

The updated submodule references and integration changes are committed locally.
No commits were pushed. The recovery revisions and upstream revisions were
verified to be ancestors of their corresponding final fork commits.

## Remaining validation

Live VPN connections and device behavior were not tested during this sync.
The ARM32 OpenSSL compilation still reports previously documented constant
conversion and buffer-overflow warnings in `crypto/ec/ecp_sm2p256.c`. The merge
and build do not resolve or validate that upstream code path. Existing Gradle
and Android Gradle Plugin deprecation warnings also remain. Full lint and release
signing were not part of this verification.

The unrelated `design-reference` gitlink has no `.gitmodules` entry. Whole-tree
submodule commands therefore fail; this synchronization uses targeted commands
for `ics-openvpn` and `strongswan` and preserves the existing reference.
