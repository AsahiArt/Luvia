#!/usr/bin/env python3
"""List paired physical iPhones/iPads (USB first, then wireless).

One line per device:

    device <udid> <coredevice-id> <usb|network>

If none are paired, prints `simulator [<udid>]`. Paired network devices
are eligible even when the CoreDevice tunnel is down; `devicectl install`
brings the link up. IOS_UDID selects a specific device or simulator.
IOS_FORCE_SIM=1 / IOS_FORCE_DEVICE=1 override the default preference.
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
    elif pairing == "paired":
        score, how = 1, "network"
    else:
        score, how = 0, "network"
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

    eligible = [row for row in physical if row[0] >= 1]
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
