#!/usr/bin/env bash
# Run from a terminal that can launch ADB and write the already-installed AVD.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
AVD="${VARCO_AVD:-Pleos_Connect_v2.1.5_S}"
ADB="$SDK/platform-tools/adb"
case "${1:-start}" in
  start)
    "$ADB" start-server
    if "$ADB" devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
      echo 'An Android device is already connected; no existing emulator was restarted.'
    else
      mkdir -p "$ROOT/.build"
      nohup "$SDK/emulator/emulator" -avd "$AVD" -dns-server 8.8.8.8,8.8.4.4 \
        -no-snapshot-load -no-boot-anim >"$ROOT/.build/emulator.log" 2>&1 &
      echo 'Emulator launching. After boot, run: scripts/emu.sh install'
    fi
    ;;
  install)
    APK="${2:-$ROOT/dist/park-lounge-0.2.0-debug.apk}"
    "$ADB" install -r "$APK"
    "$ADB" shell am start -n ai.nc.varcospaces/.MainActivity
    ;;
  status) "$ADB" devices -l ;;
  *) echo 'Usage: scripts/emu.sh [start|install [APK]|status]' >&2; exit 2 ;;
esac
