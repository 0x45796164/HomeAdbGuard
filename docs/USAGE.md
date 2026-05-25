# Home ADB Guard — Usage

End-user guide. For build, install, and contributor docs see
[DEVELOPMENT.md](DEVELOPMENT.md).

## Android constraints

These constraints are not bugs — they are the reason the app exists in this
shape. Read them once before granting permissions.

### `WRITE_SECURE_SETTINGS` must be granted via ADB

A normal APK cannot write `Settings.Global` values. Granting this permission
is a one-time step done from a connected computer:

```sh
adb shell pm grant app.homeadbguard android.permission.WRITE_SECURE_SETTINGS
```

See [DEVELOPMENT.md → Install](DEVELOPMENT.md#install) for the full grant +
verification flow.

References:

- [`Settings.Global`](https://developer.android.com/reference/android/provider/Settings.Global)
- [Android permissions overview](https://developer.android.com/guide/topics/permissions/overview)

### Developer-options state cannot be read back reliably

`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` is documented to always read
back `0` for third-party apps. The app therefore treats its own write call as
a *request* and asks you to verify the actual state with ADB (see
[DEVELOPMENT.md → Verify behavior with ADB](DEVELOPMENT.md#verify-behavior-with-adb)).

Reference:
[`DEVELOPMENT_SETTINGS_ENABLED`](https://developer.android.com/reference/kotlin/android/provider/Settings.Global#DEVELOPMENT_SETTINGS_ENABLED)

### Wireless debugging may need a one-time trust prompt

AOSP `adbd` ties Wireless debugging to the current Wi-Fi network and disables
it on network changes. Enable Wireless debugging once manually on your home
Wi-Fi and accept the trust prompt; the app can then toggle it on/off, but it
cannot pair a new computer for you.

### Wi-Fi SSID/BSSID needs runtime permissions and Location enabled

Modern Android treats Wi-Fi identity as privacy-sensitive. The app requests
Location and Nearby Wi-Fi Devices, and expects system Location to be enabled.
If either is missing, the app fails closed and disables.

References:

- [`WifiInfo.getSSID()`](https://developer.android.com/reference/android/net/wifi/WifiInfo#getSSID())
- [Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)

---

## First-time setup on the phone

1. Open **Home ADB Guard**.
2. Tap **Request runtime permissions** and allow:
   - Location
   - Nearby Wi-Fi devices
   - Notifications
3. Make sure system Location is enabled.
4. Connect to your home Wi-Fi.
5. If Developer options is hidden:
   *Settings → About phone → Software information → tap **Build number** 7 times.*
6. Open **Developer options** and enable **Wireless debugging** manually
   once. Accept the trust prompt for this Wi-Fi network.
7. In Home ADB Guard, tap **Save current Wi-Fi as home**.
8. Tap **Start monitoring**.

---

## Trusted networks

### Mesh Wi-Fi / multiple access points

The app saves one trusted BSSID when you tap **Save current Wi-Fi as home**.
For each additional AP you want to trust:

1. Walk near the AP and wait for the phone to roam.
2. Open the app and tap **Add current AP BSSID to trusted home list**.

### Removing a single trusted access point

Each chip in **Saved home Wi-Fi → Trusted access points** has an X icon.
Tap it to remove that BSSID. The app refuses to remove the last remaining
BSSID — use **Clear saved home** instead if you want to start over.

### SSID-only fallback (off by default)

**Toggle SSID-only fallback** lets the app treat a matching SSID as home even
if the current BSSID is not in the trusted list. This is **less safe**: an
attacker can stand up a Wi-Fi network with the same SSID. Leave this off
unless your network legitimately rotates BSSIDs that you cannot enumerate.

---

## In-app actions

The Monitoring card has four buttons:

- **Enable ADB now** — refuses unless the current network matches your saved
  home Wi-Fi; on a match, writes `development_settings_enabled = 1` and
  toggles `adb_wifi_enabled` 0 → 1 to nudge AOSP `adbd` into re-binding.
  Useful when you tapped Disable on a trusted network and want ADB back
  without cycling Wi-Fi.
- **Re-check now** — re-evaluates the current network and applies the
  matching state. The 30-second watchdog already does this in the background.
- **Disable ADB now** — writes both settings to `0`. The app will re-enable
  on the next watchdog tick if monitoring is on and you are at home.
- **Stop and disable** — stops monitoring and writes both settings to `0`.

### Snooze auto-disable

For maintenance flows (flashing a ROM, long debug sessions) you can pause
auto-disable for **15 / 30 / 60 minutes** from the Monitoring card. While
snoozed, ADB stays on regardless of network — but the snooze can only be
**armed when currently on trusted Wi-Fi**, so it cannot be used to enable
ADB while away. Snooze is cancellable at any time and the persistent
notification + decision log show the remaining time.

---

## Quick Settings tile

Edit your Quick Settings panel and add the **Home ADB** tile. Tapping it
toggles monitoring on/off. The tile state mirrors the app:

- Active + "Protected at home" — monitoring on, current Wi-Fi is trusted.
- Active + "Off-network — ADB off" — monitoring on, ADB is held disabled.
- Inactive + "Monitoring off" — monitoring is stopped.

Acting on the tile requires the device be unlocked first (`unlockAndRun`),
so it cannot weaken protection from the lock screen.

## Launcher shortcuts

Long-press the app icon on a home screen or the launcher to access:

- **Enable ADB** — same as Enable ADB now in the app; refuses if you are
  not on a trusted network.
- **Disable ADB** — same as Disable ADB now.
- **Re-check** — re-evaluates the current Wi-Fi and applies the matching
  state.

## Home-screen widget

Add the **Home ADB Guard** widget from the launcher's widget picker. It
shows a glanceable status with the same color states as the in-app hero
card (green / red / amber). Tap the widget to open the app. The widget
refreshes whenever the monitor service re-evaluates.

## Notification actions

While monitoring, the persistent notification shows the current network and
the live decision reason. Its three quick actions:

- **Apply now** — re-evaluate the current Wi-Fi and apply.
- **Disable now** — request Wireless debugging and Developer options off.
- **Stop** — stop monitoring and request both off.

---

## Pairing helper

The "Connect from your computer" card shows the device's local IPv4 plus a
copyable `adb connect <IP>:PORT` template. Use the port that Android shows
under Settings → Developer options → Wireless debugging → Pair device with
pairing code (it is dynamic on Android 11+).

## Decision history

The Diagnostics card keeps the last 10 enable/disable decisions with
timestamps and reasons. Tap to expand. Useful when you want to know
*why* ADB toggled at a certain time.

---

## Battery / OEM background restrictions

Foreground services are more reliable than plain receivers, but OEM policies
vary. If monitoring stops unexpectedly:

```
Settings → Apps → Home ADB Guard → Battery → Unrestricted
```

Avoid putting the app in any "deep sleep" / "hibernate apps" list.

References:

- [Android 14+ foreground service type requirement](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

## Failure modes

| Case | Behavior |
|---|---|
| Screen turns off on a trusted network | Some OEMs (notably Samsung) hide Wi-Fi SSID/BSSID from background apps when the screen is off. The app keeps the trusted state if the underlying `Network` handle is unchanged (same Wi-Fi association); a roam or disconnect produces a new handle and falls back to fail-closed. |
| Leaving home Wi-Fi | The foreground notification stays for a 60-second grace window, then disappears when the service stops. Monitoring is still on — a passive Wi-Fi callback brings the service (and the notification) back the next time you reconnect to your home network. |
| Missing `WRITE_SECURE_SETTINGS` | Monitoring runs, settings writes fail. Grant via ADB. |
| Location permission denied | Wi-Fi identity unavailable; fails closed and disables. |
| Location toggle off | Wi-Fi identity unavailable; fails closed and disables. |
| SSID matches but BSSID is new | Disables, unless SSID-only fallback is enabled or you add the new BSSID. |
| Phone reboots | If monitoring was enabled, `BootReceiver` re-applies the rule: on home Wi-Fi the foreground service starts; otherwise it writes both settings off and arms the passive Wi-Fi watch. |
| OEM blocks boot start | Open the app once and tap **Start monitoring**; set battery to Unrestricted. |
| Wireless debugging never trusted on this network | Android may disable or prompt. Enable/allow once manually in Developer options. |
| App is uninstalled | The app cannot run anymore. Manually disable Developer options / Wireless debugging if desired. |

---

## Uninstall cleanup

In the app, tap **Stop and disable**. Or, from a host:

```sh
adb shell settings put global adb_wifi_enabled 0
adb shell settings put global development_settings_enabled 0
adb uninstall app.homeadbguard
```
