<div align="center">
  <img src="app/src/main/res/drawable-nodpi/logo_primary.png" alt="LibreGuard VPN logo" width="220" />

  <h1>LibreGuard VPN</h1>

  <p>
    <a href="https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html">
      <img src="https://img.shields.io/badge/License-GPL%20v2-blue.svg" alt="License: GPL v2" />
    </a>
    <!-- Google Play badge next to the license badge -->
    <a href="https://play.google.com/store/apps/details?id=net.libreguard.vpn" style="margin-left:8px;">
      <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="24" style="vertical-align:middle;" />
    </a>
  </p>

  <p>LibreGuard VPN is an Android VPN client that combines LibreGuard app code with bundled OpenVPN and strongSwan components.</p>
</div>

## Repository layout

- `app/` — Android application source
- `ics-openvpn/` — bundled OpenVPN for Android fork/submodule
- `strongswan/` — bundled strongSwan fork/submodule
- `design-reference/` — design assets/reference implementation

## Open-source release checklist

This repository is prepared for public source distribution with:

- a root `LICENSE` file containing the GPL v2 text
- a root `NOTICE` file summarizing attribution
- a root `THIRD_PARTY_LICENSES.md` file for bundled and key direct dependencies
- an in-app **Settings > Open Source Licenses** screen that links to the source repository and summarizes major third-party licenses
- `.gitignore` rules for local secrets such as Firebase config, ADI registration values, and signing artifacts

## Building locally

### Prerequisites

- Android Studio with a recent Android SDK
- JDK 17
- Git submodules initialized

### Clone and initialize submodules

```powershell
git clone https://github.com/LibreGuard-Developer/libreguard-vpn-android.git
Set-Location libreguard-vpn-android
git submodule update --init --recursive
```

### Environment variables

The app build reads sensitive values from environment variables instead of version-controlled files. The important variables are defined in `app/build.gradle.kts`, including:

- `GOOGLE_SERVICES_JSON_B64` or `GOOGLE_SERVICES_JSON`
- `ADI_REGISTRATION_PROPERTIES` or `ADI_REGISTRATION_FRAGMENT`
- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `APP_SIGNING_SHA256`
- `APP_SIGNING_SHA256_ALLOWLIST`

Local-only files such as `local.properties`, `app/google-services.json`, and `adi-registration.properties` should not be committed.

### Example build

```powershell
.\\gradlew.bat :app:assembleDebug
```

## Source code and compliance

- Source repository: https://github.com/LibreGuard-Developer/libreguard-vpn-android
- License notices: see `THIRD_PARTY_LICENSES.md`
- Bundled upstream code keeps its own copyright notices and license exceptions
- `app/src/main/res/raw/root_ye.pem` and `app/src/main/res/raw/root_yr.pem` are intentionally bundled public CA root certificates from [Let's Encrypt](https://letsencrypt.org/certificates/), used as compatibility trust anchors for Android devices whose system trust store does not yet include those roots; they are public certificate material, not private keys or app secrets

## License

This project is distributed under the GNU General Public License v2.0. See the [LICENSE](LICENSE) file for details.

This project uses the following GPL-licensed components:

- [ics-openvpn](https://github.com/schwabe/ics-openvpn) — GPLv2 with upstream linking exceptions documented in `ics-openvpn/doc/LICENSE.txt`
- [strongSwan](https://github.com/strongswan/strongswan) — GPLv2-or-later with upstream exceptions documented in `strongswan/LICENSE`

For a broader summary of bundled and direct third-party dependencies, see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).


