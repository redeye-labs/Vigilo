#!/usr/bin/env python3
"""Produce a *separate*, no-Tesla Home Assistant local add-on smoke-test archive.

Run from this repository: python3 scripts/package_ha_smoke.py --out /tmp/vigilo2-ha-smoke.tar.gz
Unpack in the Home Assistant OS /addons directory ONLY after backing up Vigilo stable.

No existing add-on slug, /data volume, Tesla app or Tailscale identity is reused.
"""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "addon" / "vigilo"
TARGET = "vigilo2_smoke"

CONFIG = """name: Vigilo 2 - Collaudo isolato
version: "2.0.0-beta.1-smoke"
slug: vigilo2_smoke
description: Verifica installazione senza accesso Tesla, MQTT, Tailscale o dati Vigilo stabili
url: https://github.com/redeye-labs/Vigilo
arch:
  - amd64
  - aarch64
init: false
startup: application
boot: manual_only
ingress: true
ingress_port: 8099
panel_icon: mdi:shield-check
panel_title: Vigilo 2 Smoke
options: {}
schema: {}
"""

BLOCKED_NAMES = {
    ".env", "state.json", "events.json", "options.json", "tls.key",
    "v2-vehicle-key", "ui_password",
}
BLOCKED_SUFFIXES = {".key", ".p12", ".pfx", ".pem", ".sqlite", ".db"}
BLOCKED_PARTS = {".git", "__pycache__", ".gradle", "node_modules"}


def build_archive(output: Path) -> dict[str, str]:
    if not (SOURCE / "Dockerfile").is_file():
        raise RuntimeError("Vigilo add-on source tree is missing")

    files: dict[str, bytes] = {}
    for path in SOURCE.rglob("*"):
        if path.is_symlink():
            raise RuntimeError(f"Symlink not allowed in smoke source: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(SOURCE)
        if any(p in BLOCKED_PARTS for p in relative.parts):
            continue
        if path.name in BLOCKED_NAMES or path.suffix.lower() in BLOCKED_SUFFIXES:
            raise RuntimeError(f"Unexpected sensitive-looking file: {relative}")
        data = path.read_bytes()
        if relative.as_posix() == "config.yaml":
            data = CONFIG.encode("utf-8")
        elif relative.as_posix() == "Dockerfile":
            old = b"FROM ${VIGILO_BASE}\n"
            if data.count(old) != 1:
                raise RuntimeError("Dockerfile base stage changed; refusing unsafe bundle")
            data = data.replace(old, old + b"ENV VIGILO_ISOLATED_PREVIEW=1\n", 1)
        files[f"{TARGET}/{relative.as_posix()}"] = data

    if f"{TARGET}/config.yaml" not in files or f"{TARGET}/server/isolated_preview.go" not in files:
        raise RuntimeError("Missing safety-critical local add-on files")
    if b"ENV VIGILO_ISOLATED_PREVIEW=1" not in files[f"{TARGET}/Dockerfile"]:
        raise RuntimeError("Missing mandatory isolated runtime switch")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, "w:gz") as archive:
        for name, data in sorted(files.items()):
            entry = tarfile.TarInfo(name)
            entry.size = len(data)
            entry.mode = 0o644
            entry.mtime = 0
            archive.addfile(entry, io.BytesIO(data))

    with tarfile.open(output, "r:gz") as archive:
        names = archive.getnames()
        if not names or any(
            not n.startswith(TARGET + "/") or ".." in Path(n).parts or n.startswith("/")
            for n in names
        ):
            raise RuntimeError("Invalid archive paths")
        cfg = archive.extractfile(f"{TARGET}/config.yaml")
        docker = archive.extractfile(f"{TARGET}/Dockerfile")
        if cfg is None or docker is None or (
            b"slug: vigilo2_smoke" not in cfg.read()
            or b"ENV VIGILO_ISOLATED_PREVIEW=1" not in docker.read()
        ):
            raise RuntimeError("Smoke archive failed isolation verification")

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    return {"archive": str(output), "sha256": digest, "files": str(len(files))}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="Output .tar.gz path")
    args = parser.parse_args()
    if not str(args.out).endswith(".tar.gz"):
        parser.error("Output must end in .tar.gz")
    for key, value in build_archive(args.out).items():
        print(f"{key}={value}")


if __name__ == "__main__":
    main()
