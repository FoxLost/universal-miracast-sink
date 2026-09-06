#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# ─── Miracast Sink Magisk Module Packer ────────────────────────────────
# Packs a signed APK into a flashable Magisk module zip.
#
# Usage:
#   ./scripts/pack-magisk.sh                           use default signed APK
#   ./scripts/pack-magisk.sh path/to/MiracastSink.apk  use specific APK
#   ./scripts/pack-magisk.sh -h                        this help
#
# Output:
#   magisk-miracast-sink-v<VERSION>-platform.zip
# ────────────────────────────────────────────────────────────────────────

# ─── config ────────────────────────────────────────────────────────────

ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android}"
AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/34.0.0/aapt2}"

MODULE_DIR="magisk-miracast-sink"
TARGET_DIR="$MODULE_DIR/system/priv-app/MiracastSink"
PROP_FILE="$MODULE_DIR/module.prop"

# ─── args ──────────────────────────────────────────────────────────────

case "${1:-}" in
    -h|--help) sed -n '3,14p' "$0"; exit 0 ;;
esac

DEFAULT_APK="app/build/outputs/apk/release/MiracastSink.apk"
APK_IN="${1:-$DEFAULT_APK}"

if [[ ! -f "$APK_IN" ]]; then
    echo "ERROR: APK not found: $APK_IN"
    echo "Run  ./scripts/build.sh  first, or pass a path:"
    echo "  $0 path/to/signed.apk"
    exit 1
fi

# ─── extract version from APK ──────────────────────────────────────────

VCODE=$("$AAPT2" dump badging "$APK_IN" 2>/dev/null | grep -oE "versionCode='[0-9]+'" | cut -d\' -f2)
VNAME=$("$AAPT2" dump badging "$APK_IN" 2>/dev/null | grep -oE "versionName='[^']+'" | cut -d\' -f2)
PKG=$("$AAPT2" dump badging "$APK_IN" 2>/dev/null | grep -oE "name='[^']+'" | head -1 | cut -d\' -f2)

echo "=== Packing Magisk Module ==="
echo "  APK    $APK_IN"
echo "  pkg    $PKG"
echo "  ver    $VNAME ($VCODE)"

# ─── update module.prop ────────────────────────────────────────────────

sed -i "s/^version=v.*/version=v$VNAME/" "$PROP_FILE"
sed -i "s/^versionCode=.*/versionCode=$VCODE/" "$PROP_FILE"

echo "  prop   $PROP_FILE → v$VNAME"

# ─── copy APK ──────────────────────────────────────────────────────────

mkdir -p "$TARGET_DIR"
cp "$APK_IN" "$TARGET_DIR/MiracastSink.apk"
echo "  copy   → $TARGET_DIR/MiracastSink.apk"

# ─── zip ───────────────────────────────────────────────────────────────

OUT="magisk-miracast-sink-v${VNAME}-platform.zip"

cd "$MODULE_DIR"
zip -r "../$OUT" . -x ".git/*" "*.codegraph*" 2>&1 | tail -1
cd ..

ZIP_SIZE=$(du -h "$OUT" | cut -f1)
echo ""
echo "PACK DONE  → $OUT  ($ZIP_SIZE)"
echo ""
echo "Flash via:  Magisk Manager → Modules → Install from storage"
echo "Or via:     adb push $OUT /sdcard/ && adb shell su -c 'magisk --install-module /sdcard/$OUT'"
