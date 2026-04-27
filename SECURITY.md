# Security Policy

## No Warranty

Home ADB Guard is provided **"AS IS"** without warranty of any kind, express or
implied. The authors and contributors make **no guarantees** that the software:

- works correctly on any specific device, Android version, or OEM skin,
- will keep ADB-over-Wi-Fi disabled outside your trusted network,
- will keep Developer options disabled outside your trusted network,
- is free of bugs, security vulnerabilities, or unintended side effects,
- will continue to function after Android version upgrades or OEM updates.

You assume **all risk** by installing, granting permissions to, and running this
application. Review the source code before granting `WRITE_SECURE_SETTINGS`.

See [LICENSE](LICENSE) for the complete disclaimer of liability.

## Threat model and scope

This app is a **convenience tool**, not a security boundary. It does not
prevent a determined local attacker who has physical or network access to your
device from enabling ADB-over-Wi-Fi by other means.

Specifically, the app does **not** defend against:

- An attacker who already has shell access to the device.
- An attacker who can spoof the BSSID and SSID of your home Wi-Fi.
- A malicious or compromised OEM ROM, kernel, or system app.
- Other apps that hold `WRITE_SECURE_SETTINGS` (rare on stock builds).
- Physical access (the user may toggle settings manually at any time).

It is intended to reduce the **attack surface** when the device leaves a
trusted Wi-Fi network by automatically requesting that ADB-over-Wi-Fi and
Developer options be turned off.

## Reporting a vulnerability

If you discover a security issue, please open a private security advisory
through the project's repository host (for example, GitHub "Report a
vulnerability"). Do **not** open a public issue describing exploitation
details before maintainers have had a chance to respond.

When reporting, please include:

- Affected version (`versionName` from `app/build.gradle.kts`).
- Android version, OEM, and device model where the issue reproduces.
- Steps to reproduce.
- Expected vs. observed behavior.
- Impact assessment (what the issue allows an attacker to do).

There is no SLA. This is a community-maintained project; responses are
best-effort.

## Verifying app behavior yourself

Because the app cannot reliably read back Developer options state on modern
Android, verify actual state with ADB:

```sh
adb shell settings get global development_settings_enabled
adb shell settings get global adb_wifi_enabled
adb shell dumpsys package app.homeadbguard | grep -E 'INTERNET|grantedPermissions' -A40
```

The package should never hold `android.permission.INTERNET`.

## Supply chain

This project intentionally has **no third-party Android dependencies**. The
only declared plugin is `com.android.application` from Google. If you fork or
build from source, audit `app/build.gradle.kts` and `settings.gradle.kts`
before adding any dependency.
