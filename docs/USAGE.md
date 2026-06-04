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
3. Make sure system Location is on. Optionally set the app's Location to **"Allow
   all the time"** if you want ADB to come back automatically after a reboot
   (see below) — otherwise the foreground permission is enough.
4. Connect to your home Wi-Fi.
5. If Developer options is hidden: Settings → About phone → Software
   information → tap **Build number** 7 times.
6. Open Developer options → enable **Wireless debugging** once manually.
   Accept the trust prompt for this Wi-Fi.
7. In the app: **Save current Wi-Fi as home**, then turn on **Guard armed**.

Step 6 is unavoidable — Android only lets the user accept the Wireless
debugging trust prompt, and that prompt is tied to the specific Wi-Fi network.
The app can toggle ADB on/off after that, but it can't pair a new computer
for you.

## The Armed switch and the four states

The Monitoring card has one primary control — the **Guard armed** switch. While
armed, the guard resolves one of four states and keeps ADB in the matching
position, confirming it by *reading `adb_wifi_enabled` back*, not just writing it:

| State | When | ADB |
|-------|------|-----|
| **ON** | armed, on home Wi-Fi | Developer options + Wireless debugging on |
| **AWAY** | armed, off home Wi-Fi | forced off |
| **PAUSED** | armed, you paused at home | off (timed or until you resume) → auto-returns to ON |
| **SNOOZED** | armed, maintenance window | on, survives a brief trip out → auto-ends |

There is no "Enable / Apply / Disable" button any more — arming the guard makes
ADB ON automatic and self-correcting. If the read-back ever shows ADB off while
it should be on, the guard re-asserts it (a `0 → 1` toggle that makes `adbd`
re-bind).

## Pause and Snooze

Both live on the Monitoring card and are **opposites that cancel each other**:

- **Pause** — turn ADB off for a while *without leaving home*. Pick 15 / 30 /
  60 minutes, or **Pause until I resume**. It returns to ON automatically.
- **Snooze** — keep ADB *on* through a maintenance window even if you step out
  (15 / 30 / 60 minutes). Only **armable while on trusted Wi-Fi**, so it cannot
  turn ADB on while away.

## Auto-toggle on / off home Wi-Fi

When the guard is armed:

- Joining the saved network → ADB on, persistent notification appears.
- Leaving the saved network → after a 60-second grace window, ADB off.
- The guard re-checks on Wi-Fi changes, **when you unlock the device**, and
  every 30 seconds. The unlock re-check matters after a reboot: Android only
  lets the app read Wi-Fi identity once unlocked.

The monitor runs as a **location-type foreground service**. Reading Wi-Fi
identity in the background (e.g. right after a reboot, before you open the app)
requires **background location ("Allow all the time")**, which is **optional**
and entirely your choice:

- **Granted** → after a reboot the guard re-reads your Wi-Fi and turns ADB back
  on by itself.
- **Not granted** → Android redacts Wi-Fi identity for background reads, so the
  guard fails closed after a reboot until you **open the app once** (which gives
  it foreground location); from then on it works for the rest of the session.

The default posture is **fail-closed**: if Wi-Fi identity is unreadable, if
Location is off, or if the BSSID does not match, the guard disables ADB.

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

## Quick Settings tile (the main way to use this app)

Drag the **Home ADB** tile into your Quick Settings panel — long-press an
empty slot in the QS edit screen. The tile mirrors the monitoring state:

- Active + "Protected — ADB on" / "Paused — ADB off" / "Snoozed — ADB on" /
  "Away — ADB off" — the guard is armed; the subtitle mirrors the current state.
- Inactive + "Monitoring off" — the guard is disarmed.

Tapping the tile arms or disarms the guard. The device must be unlocked first
(`unlockAndRun`), so the tile cannot weaken protection from the lock screen.

For most use cases this replaces opening the app entirely.

## Notification actions

The persistent notification is state-driven:

- **ON** — *Pause* · *Stop guard*
- **PAUSED** — *Resume* · *Stop guard*
- **SNOOZED** — *End snooze* · *Stop guard*
- **AWAY** — *Stop guard*

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
| OEM blocks boot start | Open the app once, turn on **Guard armed**, and set battery to Unrestricted. |
| App is uninstalled | The app can't run anymore. Manually disable Developer options / Wireless debugging if you want. |

## Verifying behavior with ADB

The app holds `WRITE_SECURE_SETTINGS`, so it reads these values back and shows
the result in Diagnostics → **ADB state (verified)**, including whether the
wireless `adbd` daemon is actually listening (`service.adb.tls.port`). To
cross-check from a computer:

```sh
adb shell settings get global development_settings_enabled
adb shell settings get global adb_wifi_enabled
adb shell getprop service.adb.tls.port
```

Both settings read `1` on trusted Wi-Fi with monitoring active; the port is a
number (≈30000–49999) when wireless debugging is truly listening, or `-1` when
it is not.

## Uninstall

From the app: **Stop guard** (on the notification or the Monitoring card). Or from a host:

```sh
adb shell settings put global adb_wifi_enabled 0
adb shell settings put global development_settings_enabled 0
adb uninstall app.homeadbguard
```
