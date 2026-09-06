#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# ─── Miracast Sink APK Builder ────────────────────────────────────────
# Builds the release APK and signs it with the AOSP platform key from the
# neighbouring platform_build-main repo.
#
# Usage:
#   ./scripts/build.sh              build, sign, output to app/build/outputs
#   ./scripts/build.sh -d           debug build (skip signing)
#   ./scripts/build.sh -h           this help
#
# Output:
#   app/build/outputs/apk/release/MiracastSink-v<VERSION>.apk
# ────────────────────────────────────────────────────────────────────────

# ─── config ────────────────────────────────────────────────────────────

# AOSP platform key from the platform_build-main repo (must be sibling)
PLATFORM_KEY_DIR="${PLATFORM_KEY_DIR:-../platform_build-main/target/product/security}"
PLATFORM_PK8="$PLATFORM_KEY_DIR/platform.pk8"
PLATFORM_X509="$PLATFORM_KEY_DIR/platform.x509.pem"
MIN_SDK=29

ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"

APKSIGNER="${APKSIGNER:-$BUILD_TOOLS/apksigner}"
AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2}"

# ─── args ──────────────────────────────────────────────────────────────

DEBUG_BUILD=false

case "${1:-}" in
    -h|--help) sed -n '3,17p' "$0"; exit 0 ;;
    -d) DEBUG_BUILD=true ;;
esac

if ! $DEBUG_BUILD; then
    for f in "$PLATFORM_PK8" "$PLATFORM_X509"; do
        if [[ ! -f "$f" ]]; then
            echo "ERROR: missing signing key: $f"
            echo "Expected at: $PLATFORM_KEY_DIR"
            echo "Set PLATFORM_KEY_DIR to point to an AOSP build/target/product/security/"
            exit 1
        fi
    done
fi

# ─── build ─────────────────────────────────────────────────────────────

echo "=== Building Miracast Sink APK ==="

./gradlew :app:assembleRelease -x lintVitalRelease -x lint --console=plain 2>&1 | tail -3

UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ ! -f "$UNSIGNED" ]]; then
    echo "ERROR: unsigned APK not produced"
    exit 1
fi

VCODE=$("$AAPT2" dump badging "$UNSIGNED" 2>/dev/null | grep -oE "versionCode='[0-9]+'" | cut -d\' -f2)
VNAME=$("$AAPT2" dump badging "$UNSIGNED" 2>/dev/null | grep -oE "versionName='[^']+'" | cut -d\' -f2)
SIGNED="app/build/outputs/apk/release/MiracastSink-v${VNAME}.apk"
SIGNED_ALT="app/build/outputs/apk/release/MiracastSink.apk"

if $DEBUG_BUILD; then
    cp "$UNSIGNED" "$SIGNED"
    echo "DEBUG build done → $SIGNED"
    echo "  version $VNAME (code $VCODE)"
    echo "  WARNING: unsigned — not suitable for flashing"
else
    # ─── sign ──────────────────────────────────────────────────────────
    echo "=== Signing with platform key ==="

    "$APKSIGNER" sign \
        --key "$PLATFORM_PK8" --cert "$PLATFORM_X509" \
        --min-sdk-version "$MIN_SDK" \
        --out "$SIGNED" "$UNSIGNED" 2>&1

    echo "=== Verifying signature ==="
    "$APKSIGNER" verify --verbose "$SIGNED" 2>&1 | grep "Verified"

    cp "$SIGNED" "$SIGNED_ALT"

    echo ""
    echo "BUILD SUCCESS  → $SIGNED"
    echo "  version       $VNAME (code $VCODE)"
    echo "  min SDK       $MIN_SDK"
    echo "  key           $PLATFORM_X509"
    echo ""
fi
