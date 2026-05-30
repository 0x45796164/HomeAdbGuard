# Contributing

This is a small, deliberately scoped, AI-written app in maintenance mode.
Feature work is not planned (see
[README.md](README.md#disclosures)), but bug-fix pull requests are welcome.

## Hard rules

1. **No new third-party Android libraries.** Only first-party Google /
   AndroidX UI deps (currently `androidx.appcompat:appcompat` and
   `com.google.android.material:material`). No analytics, no ad SDKs, no
   crash reporters, no network clients.
2. **No `INTERNET` permission. No cleartext traffic.** The app must remain
   incapable of phoning home.
3. **Preserve fail-closed.** If Wi-Fi identity is unavailable or the current
   network is not the saved home, the app must request both
   `adb_wifi_enabled` and `development_settings_enabled` off.
4. **Flag `WRITE_SECURE_SETTINGS` touchpoints.** Any PR that adds, removes,
   or re-routes a call to `SecureSettings` (or otherwise changes when those
   global settings are written) must call this out in the PR description.

## Before opening a PR

Run what CI runs:

```sh
./gradlew assembleDebug testDebug lintDebug
```

All three must pass. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the
full dev setup.

## Reporting bugs

Use the bug-report issue template. Include device, Android version, OEM,
`versionName`, repro steps, and ADB output. Security issues:
[SECURITY.md](SECURITY.md) — don't post exploitable findings publicly.
