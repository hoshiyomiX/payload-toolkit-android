#!/bin/bash
# stellar-frameworks dev server — persistent popup preview
# Auto-restarts if killed (unkillable). Port :3000.
# Created by boot.sh — do not edit manually.

if command -v ss >/dev/null 2>&1 && ss -tlnp 2>/dev/null | grep -q ':3000 '; then
  echo "[dev.sh] Port :3000 already in use — not starting" >&2
  exit 0
fi

if [ -f /home/z/my-project/package.json ] \
   && grep -q '"next"' /home/z/my-project/package.json 2>/dev/null; then
  while true; do
    cd /home/z/my-project && bun run dev
    sleep 2
  done
else
  mkdir -p /home/z/my-project/download
  while true; do
    cd /home/z/my-project/download && python3 -m http.server 3000
    sleep 1
  done
fi
