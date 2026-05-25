# Home ADB Guard

![Build](https://github.com/0x45796164/HomeAdbGuard/actions/workflows/build.yml/badge.svg)

Minimal Android app that requests **Developer options** and **Wireless
debugging** to be enabled only when the phone is connected to a saved home
Wi-Fi network, and requests them disabled at any other time.

> **No warranty.** This is a hobbyist convenience tool. The author guarantees
> nothing about correctness, reliability, security, or compatibility with any
> specific device or Android version. Read [SECURITY.md](SECURITY.md) and
> [LICENSE](LICENSE) before installing.

## Screenshots

| At trusted home Wi-Fi | Off-network |
|---|---|
| ![Protected at home](docs/screenshots/screenshot1.jpg) | ![ADB disabled](docs/screenshots/screenshot2.jpg) |

Left: monitoring is active on the saved home network — ADB over Wi-Fi and
Developer options are allowed to be on. Right: Wi-Fi identity is unavailable
or the network is not trusted — the app fails closed and requests both off.

## Status

- License: [MIT](LICENSE)
- Min SDK: 34 (Android 14). Target + Compile SDK track current stable.
- Language: Java, Views + Material 3 (no Compose, no Kotlin).
- Build: current Android Gradle Plugin, current Gradle wrapper, JDK 21.
  Exact pinned versions live in `app/build.gradle.kts`,
  `gradle/wrapper/gradle-wrapper.properties`, and
  `gradle/gradle-daemon-jvm.properties`.

## What it does

- Watches the active Wi-Fi network via `ConnectivityManager.NetworkCallback`
  and a 30-second watchdog.
- When the current network matches the saved home SSID **and** a trusted home
  BSSID, sets `Settings.Global.adb_wifi_enabled = 1` and
  `Settings.Global.development_settings_enabled = 1`.
- In every other case (no Wi-Fi, wrong SSID, untrusted BSSID, missing
  permissions, Location off, Wi-Fi identity unreadable) it sets both back to
  `0`. The default behavior is to **fail closed**.
- Runs as a foreground service with a persistent notification while at
  home (or within a 60-second grace window after leaving). When off-home
  for longer, the service stops and a passive Wi-Fi callback re-arms it on
  the next home arrival. Restarts after boot via `BOOT_COMPLETED`.

## What it explicitly does not do

- No `INTERNET` permission. No cleartext traffic.
- No analytics, telemetry, crash reporting, or network client code.
- Only first-party Google/AndroidX libraries: AppCompat and Material
  Components for Android (Material Design 3). No analytics SDKs, no ad
  libraries, no crash reporters, no network clients.
- No accessibility service, no device admin / device owner, no root, no
  Shizuku.
- No location collection beyond what Android requires for Wi-Fi identity APIs.

The on-device persisted state is limited to one home SSID, one or more
trusted BSSIDs, a monitoring on/off flag, a "SSID-only fallback" flag
(default off), and recent decision strings for display only.

## Quick start

Build, install, and grant the protected permission:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant app.homeadbguard android.permission.WRITE_SECURE_SETTINGS
```

Then on the phone: open the app, grant the requested runtime permissions,
make sure system Location is on, enable **Wireless debugging** once
manually on your home Wi-Fi, then tap **Save current Wi-Fi as home** and
**Start monitoring**.

Full walkthrough in [docs/USAGE.md](docs/USAGE.md).

## Documentation

- [docs/USAGE.md](docs/USAGE.md) — end-user setup, features, troubleshooting,
  failure modes.
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — build, install, ADB
  verification, source layout, release signing.
- [SECURITY.md](SECURITY.md) — threat model, scope, and disclaimer.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the short list of rules (no third-party deps, no `INTERNET`, preserve
fail-closed, flag `WRITE_SECURE_SETTINGS` touchpoints).

## License

[MIT](LICENSE) — provided **as is**, without warranty of any kind. See
[SECURITY.md](SECURITY.md) for the threat model and disclaimer.
