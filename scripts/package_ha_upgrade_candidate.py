#!/usr/bin/env python3
"""Build a source-only Home Assistant Vigilo in-place UPDATE CANDIDATE.

This is NOT an installer. In particular it does not run HA Supervisor commands,
change the existing addon repository or replace production /data. A live HA
backup and a verified rollback path are required before any upgrade.
"""
from __future__ import annotations
import argparse
import hashlib
import io
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "addon" / "vigilo"
TARGET = "vigilo"

BLOCKED_NAMES = {
    ".env", "state.json", "events.json", "options.json",
    "v2-vehicle-key", "ui_password", "tls.key"
}
BLOCKED_SUFFIXES = {".key", ".p12", ".pfx", ".pem", ".sqlite", ".db"}
BLOCKED_PARTS = {".git", "__pycache__", ".gradle", "node_modules", ".venv"}

def build_archive(output: Path) -> dict[str, str]:
    files: dict[str, bytes] = {}
    if not (SOURCE / "Dockerfile").is_file() or not (SOURCE / "config.yaml").is_file():
        raise RuntimeError("Incomplete add-on source tree")
    for path in SOURCE.rglob("*"):
        if path.is_symlink():
            raise RuntimeError(f"Symlinks not allowed: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(SOURCE)
        if any(part in BLOCKED_PARTS for part in relative.parts):
            continue
        if path.name in BLOCKED_NAMES or path.suffix.lower() in BLOCKED_SUFFIXES:
            raise RuntimeError(f"Sensitive-looking file, refusing bundle: {relative}")
        files[f"{TARGET}/{relative.as_posix()}"] = path.read_bytes()
    config = files[f"{TARGET}/config.yaml"].decode("utf-8")
    docker = files[f"{TARGET}/Dockerfile"].decode("utf-8")
    if ("slug: vigilo\n" not in config
        or 'version: "2.0.0-beta.1"' not in config
        or "homeassistant_api: true" not in config
        or "hassio_api: true" not in config
        or "ingress_port: 8099" not in config
        or 'ENV VIGILO_ISOLATED_PREVIEW=1' in docker
        or 'CMD ["/usr/bin/vigilo"' not in docker):
        raise RuntimeError("Unexpected HA production addon metadata / command")
    if f"{TARGET}/server/v2_upgrade_safety_test.go" not in files:
        raise RuntimeError("Missing Tesla-config compatibility test")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, "w:gz") as archive:
        for name, data in sorted(files.items()):
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o644
            item.mtime = 0
            archive.addfile(item, io.BytesIO(data))
    with tarfile.open(output, "r:gz") as archive:
        members = archive.getnames()
        if not members or any(not p.startswith("vigilo/") or ".." in Path(p).parts for p in members):
            raise RuntimeError("Archive members escaped intended add-on directory")
    return {
        "archive": str(output),
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "source_files": str(len(files)),
        "intended_slug": "vigilo",
        "deployment_performed": "false",
    }

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if not str(args.out).endswith(".tar.gz"):
        parser.error("Output must be .tar.gz")
    for key, value in build_archive(args.out).items():
        print(f"{key}={value}")
