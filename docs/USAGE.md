# Usage

This is the longer setup-and-features doc. For the elevator pitch see
[README.md](../README.md); for build/dev see
[DEVELOPMENT.md](DEVELOPMENT.md).

## One-time setup from a computer

Install and grant the protected permission. A normal APK cannot do this — the
whole reason this app needs ADB to set itself up is the
`WRITE_SECURE_SETTINGS` grant:

```sh
adb install -r app-release.apk
adb shell pm grant app.homeadbguard android.permission.WRITE_SECURE_SETTINGS
```

Verify:

```sh
adb shell dumpsys package app.homeadbguard | grep WRITE_SECURE_SETTINGS
```

## First-time setup on the phone

1. Open **Home ADB Guard**.
2. Tap **Request runtime permissions** and allow Location, Nearby Wi-Fi
   devices, and Notifications.
3. Make sure system Location is on.
4. Connect to your home Wi-Fi.
5. If Developer options is hidden: Settings → About phone → Software
   information → tap **Build number** 7 times.
6. Open Developer options → enable **Wireless debugging** once manually.
   Accept the trust prompt for this Wi-Fi.
7. In the app: **Save current Wi-Fi as home**, then **Start monitoring**.

Step 6 is unavoidable — Android only lets the user accept the Wireless
debugging trust prompt, and that prompt is tied to the specific Wi-Fi network.
The app can toggle ADB on/off after that, but it can't pair a new computer
for you.

## The main button

**Enable ADB now** on the Monitoring card. It refuses unless the device is on
a saved home Wi-Fi (fail-closed), then writes `development_settings_enabled =
1` and toggles `adb_wifi_enabled` so AOSP `adbd` re-binds.

The other three buttons:

- **Re-check now** — re-evaluates the current network and applies the matching
  state. The 30-second watchdog already does this in the background.
- **Disable ADB now** — writes both settings to `0`.
- **Stop and disable** — stops monitoring entirely and writes both settings
  to `0`.

## Auto-toggle on / off home Wi-Fi

Optional. When **Start monitoring** is on:

- Joining the saved network → ADB turned on, persistent notification appears.
- Leaving the saved network → after a 60-second grace window, ADB is turned
  off and the foreground service stops.
- A passive Wi-Fi callback (no foreground service running) re-arms when you
  rejoin home — the OS wakes the app.

The default posture is **fail-closed**: if Wi-Fi identity is unreadable, if
Location is off, if BSSID does not match, the app disables ADB.

## Snooze auto-disable

For maintenance flows (flashing a ROM, long debugging session, scrcpy session
where you're leaving the house) the Monitoring card has 15 / 30 / 60-minute
snooze buttons. While snoozed, ADB stays on regardless of network. Snooze
can only be **armed while currently on trusted Wi-Fi**, so it cannot be used
to turn ADB on while away.

## Trusted networks

### Mesh Wi-Fi / multiple access points

The app saves one trusted BSSID when you tap **Save current Wi-Fi as home**.
For each additional AP:

1. Walk near the AP and wait for the phone to roam.
2. Open the app → **Add current AP BSSID to trusted home list**.

### Removing a single AP

Each chip under **Trusted access points** has an X icon. The app refuses to
remove the last remaining BSSID — use **Clear saved home** to start over.

### SSID-only fallback (off by default)

**Toggle SSID-only fallback** treats a matching SSID as home even if the
current BSSID isn't in the trusted list. This is **less safe** — an attacker
can stand up a Wi-Fi network with the same SSID. Leave it off unless your
network legitimately rotates BSSIDs you can't enumerate.

## Other entry points

- **Quick Settings tile** — add the **Home ADB** tile to your Quick Settings
  panel. Tap to toggle monitoring on/off. Requires the device to be unlocked.
- **Launcher shortcuts** — long-press the app icon for **Enable ADB**,
  **Disable ADB**, **Re-check**.
- **Home-screen widget** — add **Home ADB Guard** from the widget picker.
  Same green / red / amber states as the in-app card.
- **Notification actions** — while the foreground service is running:
  **Apply now**, **Disable now**, **Stop**.

## Pairing helper

The "Connect from your computer" card on the home screen shows the device's
local IPv4 and a copyable `adb connect <IP>:PORT` template. Use the port that
Android shows under Developer options → Wireless debugging → Pair device with
pairing code (it's dynamic on Android 11+).

## Decision history

The Diagnostics card keeps the last 10 enable/disable decisions with
timestamps. Tap to expand. Useful when you want to know *why* ADB toggled at
a given time.

## Battery / OEM background restrictions

Foreground services are more reliable than plain receivers, but OEM policies
vary. If monitoring stops on its own:

```
Settings → Apps → Home ADB Guard → Battery → Unrestricted
```

Avoid any "deep sleep" / "hibernate apps" list.

## Failure modes

| Case | Behavior |
|---|---|
| Screen turns off on a trusted network | Samsung (and some others) hide Wi-Fi SSID/BSSID from background apps when the screen is off. The app keeps the trusted state as long as the underlying `Network` handle is unchanged; a roam or disconnect produces a new handle and falls back to fail-closed. |
| Leaving home Wi-Fi | Foreground notification stays for 60 seconds, then the service stops. Monitoring is still on — a passive Wi-Fi callback brings the service back on the next home reconnect. |
| Missing `WRITE_SECURE_SETTINGS` | Monitoring runs, settings writes fail. Grant via ADB. |
| Location permission denied or system Location off | Wi-Fi identity unavailable. Fails closed and disables. |
| SSID matches but BSSID is new | Disables, unless SSID-only fallback is on or you add the new BSSID. |
| Phone reboots | If monitoring was on, `BootReceiver` re-applies the rule: home Wi-Fi → service starts; otherwise → writes both off and arms the passive watch. |
| OEM blocks boot start | Open the app once, tap **Start monitoring**, and set battery to Unrestricted. |
| App is uninstalled | The app can't run anymore. Manually disable Developer options / Wireless debugging if you want. |

## Verifying behavior with ADB

`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` always reads back `0` for
third-party apps — the app treats its own write as a *request* and can't
display the actual state. Check it yourself:

```sh
adb shell settings get global development_settings_enabled
adb shell settings get global adb_wifi_enabled
```

Both `1` on trusted Wi-Fi with monitoring active; both `0` after disable or
leaving the network.

## Uninstall

From the app: **Stop and disable**. Or from a host:

```sh
adb shell settings put global adb_wifi_enabled 0
adb shell settings put global development_settings_enabled 0
adb uninstall app.homeadbguard
```
