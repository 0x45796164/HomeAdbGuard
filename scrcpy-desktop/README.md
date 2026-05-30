# scrcpy-desktop

KDE Plasma system-tray launcher for [scrcpy](https://github.com/Genymobile/scrcpy)
Android sessions. Supplementary to [Home ADB Guard](../README.md) — once that
app has ADB-over-Wi-Fi up on your trusted network, this tray icon makes
launching scrcpy a single click.

Hardcoded scrcpy flags match the project's recommended set (see the scrcpy
snippet in the [main README](../README.md#use-cases)).

Requires: `python-pyqt6`, `adb`, `scrcpy`. Tested on KDE Plasma; may work on
other DEs with a system-tray host.

## Install

Copy `manager.py` and run `install` to register the KDE autostart entry:

```sh
mkdir -p ~/.config/scrcpy-desktop
cp manager.py ~/.config/scrcpy-desktop/manager.py
chmod +x ~/.config/scrcpy-desktop/manager.py
~/.config/scrcpy-desktop/manager.py install
```

`install` writes `~/.config/autostart/scrcpy-desktop.desktop` so the tray
starts at login.

## Usage

```sh
manager.py tray            # start the tray icon
manager.py list            # print ADB devices
manager.py start [serial]  # one-shot launch (no tray)
```

Starting the tray never starts scrcpy on its own. Click the tray icon to start
the default device, or the only ready device when no default is set.
Right-click for the full menu (per-device launch, **Set Default**, rescan,
quit).

Sessions launch as `scrcpy -s <serial>` with the project's flags and a window
title of `<device name> scrcpy`.

## Configuration

`~/.config/scrcpy-desktop/config.json` stores the optional default serial.
Set or clear it from the tray menu's **Set Default** submenu.
