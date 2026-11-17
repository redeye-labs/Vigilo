#!/usr/bin/env bash
# Costruisce standalone/windows-setup/out/Vigilo-Setup.exe con NSIS (makensis) dentro un container Debian.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
DOCKER=(docker); docker info >/dev/null 2>&1 || DOCKER=(sudo docker)
if command -v makensis >/dev/null 2>&1; then
  makensis -V3 -INPUTCHARSET UTF8 vigilo-setup.nsi
else
  "${DOCKER[@]}" build -q -t vigilo-nsis - <<'DOCKERFILE' >/dev/null
FROM debian:bookworm-slim
RUN apt-get -o Acquire::Retries=3 update && apt-get -o Acquire::Retries=3 install -y --no-install-recommends nsis && rm -rf /var/lib/apt/lists/*
DOCKERFILE
  "${DOCKER[@]}" run --rm -u "$(id -u):$(id -g)" -v "$PWD/..:/w" -w /w/windows-setup vigilo-nsis makensis -V3 -INPUTCHARSET UTF8 vigilo-setup.nsi
fi
ls -l out/Vigilo-Setup.exe
