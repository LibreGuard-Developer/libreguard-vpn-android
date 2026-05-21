# Contributing to LibreGuard VPN

Thanks for helping improve LibreGuard VPN.

## Before you start

- Read `README.md`, `LICENSE`, and `THIRD_PARTY_LICENSES.md`.
- Make sure you have initialized submodules.
- Do not commit secrets, signing material, or local environment files.

## Development setup

```powershell
git submodule update --init --recursive
.\\gradlew.bat :app:testDebugUnitTest
```

If you need Firebase or release-signing values, provide them through environment variables referenced by `app/build.gradle.kts`.

## Pull requests

- Keep changes focused and easy to review.
- Preserve existing upstream notices and license headers.
- Document any new dependency you add in `THIRD_PARTY_LICENSES.md`.
- Update `README.md` if build or setup steps change.
- Include tests or validation notes when you change app behavior.

## Reporting issues

When filing bugs, include:

- device model
- Android version
- LibreGuard app version
- steps to reproduce
- logs or screenshots when safe to share

Do not include passwords, tokens, certificate private keys, keystores, or personal VPN credentials in issues or pull requests.


