# Contributing

Thanks for considering a contribution. This is a small, deliberately scoped
app and the rules below exist to keep it that way.

## Ground rules

1. **No new third-party Android libraries.** Only first-party Google /
   AndroidX UI dependencies (currently `androidx.appcompat:appcompat` and
   `com.google.android.material:material`) are allowed. No analytics SDKs,
   no ad libraries, no crash reporters, no network clients.
2. **No `INTERNET` permission. No cleartext traffic.** The app must remain
   incapable of phoning home.
3. **Preserve the fail-closed posture.** If Wi-Fi identity is unavailable
   or the current network is not the saved home, the app must request both
   `adb_wifi_enabled` and `development_settings_enabled` off.
4. **Flag `WRITE_SECURE_SETTINGS` changes.** Any PR that adds, removes, or
   re-routes a call to `SecureSettings` (or otherwise changes when those
   global settings are written) must call this out explicitly in the PR
   description.

## Before you open a PR

Run the same checks CI runs:

```sh
./gradlew assembleDebug testDebug lintDebug
```

All three must pass. The CI workflow at `.github/workflows/build.yml` runs
this command on every push and pull request against `main`.

## Where to look first

- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — build / install / ADB
  verification, source layout, release signing.
- [docs/USAGE.md](docs/USAGE.md) — user-visible behavior. Keep this file
  in sync with any UI or feature change.
- [SECURITY.md](SECURITY.md) — threat model and scope. New features that
  expand the trust surface need a line in here.

## Reporting bugs

Use the bug-report issue template. Include device, Android version, OEM
skin, `versionName`, exact steps, and the ADB output snippets the template
asks for. Security issues: see [SECURITY.md](SECURITY.md) — do **not** open
a public issue for exploitable findings.
