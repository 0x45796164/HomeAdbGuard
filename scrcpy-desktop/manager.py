#!/usr/bin/env python3
"""
KDE Plasma tray launcher for scrcpy Android sessions.

Location: ~/.config/scrcpy-desktop/manager.py
Dependencies: python-pyqt6, adb, scrcpy
"""

import argparse
import json
import logging
import signal
import stat
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass, replace
from pathlib import Path


APP_NAME = "Scrcpy Desktop"
CONFIG_DIR = Path.home() / ".config" / "scrcpy-desktop"
CONFIG_FILE = CONFIG_DIR / "config.json"
AUTOSTART_DIR = Path.home() / ".config" / "autostart"
AUTOSTART_FILE = AUTOSTART_DIR / "scrcpy-desktop.desktop"
LOCAL_DESKTOP_FILE = CONFIG_DIR / "scrcpy-desktop.desktop"

SCRCPY_ARGS = [
    "--new-display=1920x1080/220",
    "--video-codec=h264",
    "--video-bit-rate=12M",
    "--max-fps=60",
    "--audio-codec=opus",
    "--audio-bit-rate=96K",
    "--audio-buffer=50",
    "--display-ime-policy=local",
    "--keyboard=uhid",
    "--mouse=sdk",
    "--no-mouse-hover",
    "--keep-active",
]

READY_STATE = "device"


logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AdbDevice:
    serial: str
    state: str
    display_name: str
    properties: dict[str, str]

    @property
    def is_ready(self) -> bool:
        return self.state == READY_STATE

    @property
    def menu_label(self) -> str:
        if self.is_ready:
            return f"{self.display_name} ({self.serial})"
        return f"{self.display_name} ({self.serial}) - {self.state}"


def ensure_dirs() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)


def load_config() -> dict[str, str]:
    if not CONFIG_FILE.exists():
        return {}

    try:
        data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        logger.warning("Ignoring invalid config file: %s", exc)
        return {}

    if not isinstance(data, dict):
        return {}

    default_serial = data.get("default_serial")
    if isinstance(default_serial, str) and default_serial:
        return {"default_serial": default_serial}

    return {}


def save_config(config: dict[str, str]) -> None:
    ensure_dirs()
    CONFIG_FILE.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")


def prettify_adb_value(value: str) -> str:
    return value.replace("_", " ").strip()


def parse_adb_device_line(line: str) -> AdbDevice | None:
    parts = line.split()
    if len(parts) < 2:
        return None

    serial = parts[0]
    state = parts[1]
    properties: dict[str, str] = {}

    for token in parts[2:]:
        if ":" not in token:
            continue
        key, value = token.split(":", 1)
        properties[key] = value

    name_source = properties.get("model") or properties.get("device") or serial
    display_name = prettify_adb_value(name_source) or serial

    return AdbDevice(
        serial=serial,
        state=state,
        display_name=display_name,
        properties=properties,
    )


def with_unique_names(devices: list[AdbDevice]) -> list[AdbDevice]:
    counts = Counter(device.display_name for device in devices)
    unique_devices: list[AdbDevice] = []

    for device in devices:
        if counts[device.display_name] <= 1:
            unique_devices.append(device)
            continue

        short_serial = device.serial[-6:] if len(device.serial) > 6 else device.serial
        unique_devices.append(
            replace(device, display_name=f"{device.display_name} {short_serial}")
        )

    return unique_devices


def query_adb_devices() -> list[AdbDevice]:
    try:
        result = subprocess.run(
            ["adb", "devices", "-l"],
            capture_output=True,
            text=True,
            check=False,
            timeout=8,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("adb was not found in PATH") from exc
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("adb devices timed out") from exc

    if result.returncode != 0:
        details = result.stderr.strip() or result.stdout.strip() or "unknown error"
        raise RuntimeError(f"adb devices failed: {details}")

    devices: list[AdbDevice] = []
    for raw_line in result.stdout.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        if line.startswith("* daemon"):
            continue

        device = parse_adb_device_line(line)
        if device:
            devices.append(device)

    return with_unique_names(devices)


def ready_devices(devices: list[AdbDevice]) -> list[AdbDevice]:
    return [device for device in devices if device.is_ready]


def build_scrcpy_command(device: AdbDevice) -> list[str]:
    title = f"{device.display_name} scrcpy"
    return [
        "scrcpy",
        "-s",
        device.serial,
        f"--window-title={title}",
        *SCRCPY_ARGS,
    ]


def launch_scrcpy(device: AdbDevice) -> subprocess.Popen:
    if not device.is_ready:
        raise RuntimeError(f"{device.serial} is {device.state}, not ready")

    cmd = build_scrcpy_command(device)

    try:
        return subprocess.Popen(
            cmd,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("scrcpy was not found in PATH") from exc


def desktop_entry() -> str:
    return f"""[Desktop Entry]
Comment=System tray launcher for scrcpy Android sessions
Exec={CONFIG_DIR / "manager.py"} tray
GenericName=
Icon=phone
MimeType=
Name[en_US]=scrcpy-desktop
Name=scrcpy-desktop
Path=
StartupNotify=true
Terminal=false
TerminalOptions=
Type=Application
X-KDE-AutostartScript=true
X-KDE-SubstituteUID=false
X-KDE-Username=
"""


def install_autostart() -> None:
    ensure_dirs()
    AUTOSTART_DIR.mkdir(parents=True, exist_ok=True)

    entry = desktop_entry()
    LOCAL_DESKTOP_FILE.write_text(entry, encoding="utf-8")
    AUTOSTART_FILE.write_text(entry, encoding="utf-8")

    manager_path = CONFIG_DIR / "manager.py"
    if manager_path.exists():
        current_mode = manager_path.stat().st_mode
        manager_path.chmod(current_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    logger.info("Installed autostart entry: %s", AUTOSTART_FILE)


def run_tray() -> None:
    try:
        from PyQt6.QtGui import QCursor, QIcon
        from PyQt6.QtWidgets import QApplication, QMenu, QSystemTrayIcon
    except ImportError:
        print(
            "CRITICAL: 'python-pyqt6' is missing. Install it with: "
            "sudo pacman -S python-pyqt6"
        )
        sys.exit(1)

    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)

    tray = QSystemTrayIcon()
    for icon_name in ("phone", "smartphone", "android", "video-display"):
        icon = QIcon.fromTheme(icon_name)
        if not icon.isNull():
            tray.setIcon(icon)
            break

    tray.setToolTip(APP_NAME)

    devices_cache: list[AdbDevice] = []
    last_error: str | None = None
    sessions: dict[str, subprocess.Popen] = {}
    default_serial: str | None = load_config().get("default_serial")

    def show_message(
        title: str,
        message: str,
        icon_type=QSystemTrayIcon.MessageIcon.Information,
    ) -> None:
        tray.showMessage(title, message, icon_type, 2500)

    def prune_sessions() -> None:
        dead_serials = [
            serial for serial, process in sessions.items() if process.poll() is not None
        ]
        for serial in dead_serials:
            sessions.pop(serial, None)

    def launch_device(device: AdbDevice) -> None:
        prune_sessions()

        if not device.is_ready:
            show_message(
                APP_NAME,
                f"{device.serial} is {device.state}, not ready for scrcpy.",
                QSystemTrayIcon.MessageIcon.Warning,
            )
            return

        running = sessions.get(device.serial)
        if running and running.poll() is None:
            show_message(APP_NAME, f"{device.display_name} scrcpy is already running.")
            return

        try:
            sessions[device.serial] = launch_scrcpy(device)
        except RuntimeError as exc:
            show_message(APP_NAME, str(exc), QSystemTrayIcon.MessageIcon.Critical)
            return

        show_message(APP_NAME, f"Started {device.display_name} scrcpy.")

    def find_device(serial: str | None) -> AdbDevice | None:
        if not serial:
            return None
        return next((device for device in devices_cache if device.serial == serial), None)

    def default_label() -> str:
        if not default_serial:
            return "Default: None"

        device = find_device(default_serial)
        if not device:
            return f"Default: {default_serial} (not connected)"
        if not device.is_ready:
            return f"Default: {device.display_name} ({device.state})"
        return f"Default: {device.display_name}"

    def default_suffix(device: AdbDevice) -> str:
        if device.serial == default_serial:
            return " [Default]"
        return ""

    def set_default_device(device: AdbDevice | None) -> None:
        nonlocal default_serial

        if device:
            default_serial = device.serial
            save_config({"default_serial": device.serial})
            show_message(APP_NAME, f"Default device set to {device.display_name}.")
        else:
            default_serial = None
            save_config({})
            show_message(APP_NAME, "Default device cleared.")

        tray.setContextMenu(build_menu())

    def build_menu() -> QMenu:
        menu = QMenu()

        header = menu.addAction("Android Devices")
        header.setEnabled(False)
        default_action = menu.addAction(default_label())
        default_action.setEnabled(False)
        menu.addSeparator()

        usable_devices = ready_devices(devices_cache)

        if last_error:
            error_action = menu.addAction(last_error)
            error_action.setEnabled(False)
        elif not devices_cache:
            empty_action = menu.addAction("No ADB devices found")
            empty_action.setEnabled(False)
        elif len(usable_devices) == 1:
            device = usable_devices[0]
            start_action = menu.addAction(
                f"Start {device.display_name}{default_suffix(device)}"
            )
            start_action.triggered.connect(
                lambda checked=False, selected=device: launch_device(selected)
            )

            unavailable = [device for device in devices_cache if not device.is_ready]
            for device in unavailable:
                action = menu.addAction(f"{device.menu_label}{default_suffix(device)}")
                action.setEnabled(False)
        else:
            for device in devices_cache:
                action = menu.addAction(f"{device.menu_label}{default_suffix(device)}")
                action.setEnabled(device.is_ready)
                if device.is_ready:
                    action.triggered.connect(
                        lambda checked=False, selected=device: launch_device(selected)
                    )

        menu.addSeparator()

        default_menu = menu.addMenu("Set Default")
        if devices_cache:
            for device in devices_cache:
                action = default_menu.addAction(device.menu_label)
                action.setCheckable(True)
                action.setChecked(device.serial == default_serial)
                action.triggered.connect(
                    lambda checked=False, selected=device: set_default_device(selected)
                )
        else:
            empty_default = default_menu.addAction("No devices found")
            empty_default.setEnabled(False)

        default_menu.addSeparator()
        clear_default = default_menu.addAction("Clear Default")
        clear_default.setEnabled(default_serial is not None)
        clear_default.triggered.connect(lambda checked=False: set_default_device(None))

        rescan_action = menu.addAction("Rescan Devices")
        rescan_action.triggered.connect(lambda: refresh_devices(show_status=True))

        quit_action = menu.addAction("Quit")
        quit_action.triggered.connect(app.quit)

        return menu

    def refresh_devices(show_status: bool = False) -> None:
        nonlocal devices_cache, last_error

        try:
            devices_cache = query_adb_devices()
            last_error = None
        except RuntimeError as exc:
            devices_cache = []
            last_error = str(exc)

        tray.setContextMenu(build_menu())

        if not show_status:
            return

        if last_error:
            show_message(APP_NAME, last_error, QSystemTrayIcon.MessageIcon.Critical)
        else:
            count = len(ready_devices(devices_cache))
            show_message(APP_NAME, f"Found {count} ready ADB device(s).")

    def select_or_show_menu() -> None:
        refresh_devices()

        usable_devices = ready_devices(devices_cache)
        default_device = find_device(default_serial)

        if default_serial and default_device and default_device.is_ready:
            launch_device(default_device)
        elif default_serial and tray.contextMenu():
            tray.contextMenu().popup(QCursor.pos())
        elif len(usable_devices) == 1:
            launch_device(usable_devices[0])
        elif len(usable_devices) > 1 and tray.contextMenu():
            tray.contextMenu().popup(QCursor.pos())
        elif last_error:
            show_message(APP_NAME, last_error, QSystemTrayIcon.MessageIcon.Critical)
        else:
            show_message(APP_NAME, "No ready ADB devices found.")

    def on_activated(reason) -> None:
        if reason == QSystemTrayIcon.ActivationReason.Trigger:
            select_or_show_menu()
        elif reason == QSystemTrayIcon.ActivationReason.Context:
            refresh_devices()

    refresh_devices()
    tray.setContextMenu(build_menu())
    tray.activated.connect(on_activated)
    tray.show()

    signal.signal(signal.SIGINT, signal.SIG_DFL)
    sys.exit(app.exec())


def list_devices() -> int:
    try:
        devices = query_adb_devices()
    except RuntimeError as exc:
        logger.error("%s", exc)
        return 1

    if not devices:
        print("No ADB devices found.")
        return 0

    for device in devices:
        print(device.menu_label)

    return 0


def start_from_cli(serial: str | None) -> int:
    try:
        devices = query_adb_devices()
    except RuntimeError as exc:
        logger.error("%s", exc)
        return 1

    usable_devices = ready_devices(devices)

    if serial:
        matches = [device for device in devices if device.serial == serial]
        if not matches:
            logger.error("Device %s was not found.", serial)
            return 1
        device = matches[0]
    elif len(usable_devices) == 1:
        device = usable_devices[0]
    elif not usable_devices:
        logger.error("No ready ADB devices found.")
        return 1
    else:
        logger.error("Multiple ready devices found. Pass a serial.")
        for device in usable_devices:
            logger.error("  %s", device.menu_label)
        return 1

    try:
        launch_scrcpy(device)
    except RuntimeError as exc:
        logger.error("%s", exc)
        return 1

    logger.info("Started %s scrcpy.", device.display_name)
    return 0


def main() -> int:
    ensure_dirs()

    parser = argparse.ArgumentParser(description="KDE tray launcher for scrcpy")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("tray", help="Start the system tray widget")
    subparsers.add_parser("list", help="List ADB devices")

    start_parser = subparsers.add_parser("start", help="Start a scrcpy session")
    start_parser.add_argument("serial", nargs="?", help="ADB device serial")

    subparsers.add_parser("install", help="Install KDE autostart entry")

    args = parser.parse_args()

    if args.command == "tray":
        run_tray()
        return 0
    if args.command == "list":
        return list_devices()
    if args.command == "start":
        return start_from_cli(args.serial)
    if args.command == "install":
        install_autostart()
        return 0

    parser.error(f"Unknown command: {args.command}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
