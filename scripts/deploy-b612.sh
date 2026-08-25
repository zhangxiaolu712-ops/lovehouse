#!/usr/bin/env bash
set -euo pipefail

LIVE_DIR="/root/lovehouse-dist"
STAGE_DIR="/root/lovehouse-dist.next"
BACKUP_DIR="/root/lovehouse-dist.prev"

npm run build

test -f dist/index.html
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
cp -a dist/. "$STAGE_DIR/"

nginx -t

rm -rf "$BACKUP_DIR"
if [ -d "$LIVE_DIR" ]; then
  mv "$LIVE_DIR" "$BACKUP_DIR"
fi
mv "$STAGE_DIR" "$LIVE_DIR"

if ! curl -fsS --resolve b612.fyi:443:127.0.0.1 https://b612.fyi/ >/dev/null; then
  rm -rf "$LIVE_DIR"
  if [ -d "$BACKUP_DIR" ]; then
    mv "$BACKUP_DIR" "$LIVE_DIR"
  fi
  echo "B612 smoke check failed; rolled back to previous static release." >&2
  exit 1
fi

echo "B612 static release deployed successfully."
