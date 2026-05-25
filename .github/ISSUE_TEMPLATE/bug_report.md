---
name: Bug report
about: Report a reproducible bug in Home ADB Guard
title: ""
labels: bug
---

## Environment

- Device (model):
- Android version:
- OEM skin (e.g. One UI 7, MIUI 14, stock AOSP, GrapheneOS):
- App `versionName` (from `app/build.gradle.kts` or the in-app About):
- Install method (built locally / sideloaded APK / other):

## Steps to reproduce

1.
2.
3.

## Expected behavior

<!-- What did you expect to happen? -->

## Observed behavior

<!-- What actually happened? Include exact strings from the app's status
     card and the persistent notification if relevant. -->

## ADB output

Please paste the output of:

```sh
adb shell settings get global development_settings_enabled
adb shell settings get global adb_wifi_enabled
adb shell dumpsys package app.homeadbguard | grep -E 'INTERNET|grantedPermissions' -A40
```

```
<!-- paste here -->
```

## Logcat (optional)

```
<!-- adb logcat -d | grep -i homeadbguard -->
```

## Additional context

<!-- Anything else: VPNs, mesh setup, OEM battery policy, etc. -->
