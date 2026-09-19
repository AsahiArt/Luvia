#!/usr/bin/env python3
"""List reachable physical iPhones/iPads (USB first, then wireless).

One line per device:

    device <udid> <coredevice-id> <usb|network>

Default `make ios` only emits devices CoreDevice can actually talk to:
USB, a live tunnel, or a paired phone whose tunnel is merely down
(`devicectl` can often bring that link up). Paired-but-unavailable
phones (no transport, tunnel unavailable) are skipped with a stderr
note — they are not on this Mac's CoreDevice network.

IOS_UDID selects a specific device or simulator, including unavailable
ones. IOS_FORCE_SIM=1 / IOS_FORCE_DEVICE=1 override the default;
`make ios-device` still tries every paired phone.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile


def load_devices() -> list[dict]:
    with tempfile.NamedTemporaryFile(suffix=".json") as tmp:
        result = subprocess.run(
            ["xcrun", "devicectl", "list", "devices", "--json-output", tmp.name],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode != 0:
            return []
        try:
            with open(tmp.name, encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, json.JSONDecodeError):
            return []
    return data.get("result", {}).get("devices") or []


def classify(dev: dict) -> tuple[int, str, str, str, str]:
    hp = dev.get("hardwareProperties") or {}
    cp = dev.get("connectionProperties") or {}
    dp = dev.get("deviceProperties") or {}
    reality = (hp.get("reality") or "").lower()
    if reality == "simulated":
        return (-1, "", "", "", "")
    device_type = hp.get("deviceType") or ""
    platform = hp.get("platform") or ""
    if device_type not in ("iPhone", "iPad") and platform not in ("iOS", "iPadOS"):
        return (-1, "", "", "", "")
    if reality and reality != "physical":
        return (-1, "", "", "", "")
    udid = hp.get("udid") or ""
    ident = dev.get("identifier") or ""
    name = dp.get("name") or ""
    pairing = (cp.get("pairingState") or "").lower()
    tunnel = (cp.get("tunnelState") or "").lower()
    transport = (cp.get("transportType") or "").lower()
    if transport in ("wired", "usb"):
        score, how = 3, "usb"
    elif tunnel == "connected":
        score, how = 2, "network"
    elif pairing == "paired" and tunnel != "unavailable" and transport:
        # Tunnel down, but CoreDevice still sees a local-network transport.
        score, how = 1, "network"
    elif pairing == "paired":
        score, how = 0, "unavailable"
    else:
        score, how = 0, "unavailable"
    return (score, udid, ident, name, how)


def emit(row: tuple[int, str, str, str, str]) -> None:
    _score, udid, ident, _name, how = row
    print(f"device {udid} {ident} {how}")


def main() -> int:
    forced = os.environ.get("IOS_UDID", "").strip()
    force_sim = os.environ.get("IOS_FORCE_SIM") == "1"
    force_device = os.environ.get("IOS_FORCE_DEVICE") == "1"
    if force_sim and not forced:
        print("simulator")
        return 0

    physical: list[tuple[int, str, str, str, str]] = []
    for dev in load_devices():
        row = classify(dev)
        score, udid, ident, name, _how = row
        if score < 0:
            continue
        physical.append(row)
        if forced and forced in (udid, ident, name):
            emit(row)
            return 0

    if forced:
        print(f"simulator {forced}")
        return 0
    min_score = 0 if force_device else 1
    eligible = [row for row in physical if row[0] >= min_score]
    skipped = [row for row in physical if 0 <= row[0] < min_score]
    for row in skipped:
        _score, udid, _ident, name, _how = row
        label = name or udid
        print(
            f"{label} ({udid}) is paired but CoreDevice cannot see it. "
            "Unlock the phone, use the same Wi-Fi with Connect via Network, "
            "or plug in USB.",
            file=sys.stderr,
        )
    eligible.sort(key=lambda row: -row[0])
    if eligible:
        for row in eligible:
            emit(row)
        return 0

    if force_device:
        print(
            "No paired physical iPhone. Connect one over USB or wireless debugging.",
            file=sys.stderr,
        )
        return 1

    print("simulator")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
