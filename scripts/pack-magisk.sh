#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'HELP'
Usage: ./scripts/pack-magisk.sh [path/to/signed.apk]
Default APK: app/build/outputs/apk/release/MiracastSink.apk
Output: magisk-miracast-sink-v<VERSION>-root.zip
Optional install bundle: installs the ordinary app immediately through pm.
No system overlay, boot script, platform key, or module is needed to run the APK.
ANDROID_HOME (default $HOME/Android), AAPT2 and APKSIGNER override SDK paths.
HELP
}
case "${1:-}" in -h|--help) usage; exit 0 ;; esac
[[ $# -le 1 ]] || { usage >&2; exit 1; }
ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android}"
AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/34.0.0/aapt2}"
APKSIGNER="${APKSIGNER:-$ANDROID_HOME/build-tools/34.0.0/apksigner}"
APK_IN="${1:-app/build/outputs/apk/release/MiracastSink.apk}"
[[ -f "$APK_IN" ]] || { echo "ERROR: APK not found: $APK_IN; run ./scripts/build.sh first." >&2; exit 1; }
for tool in "$AAPT2" "$APKSIGNER" zip; do
    command -v "$tool" >/dev/null || { echo "ERROR: required tool unavailable: $tool" >&2; exit 1; }
done
# Verification must succeed, including a cryptographically valid signing certificate.
"$APKSIGNER" verify --verbose --print-certs "$APK_IN"
BADGING=$("$AAPT2" dump badging "$APK_IN")
if [[ "$BADGING" =~ package:\ name=\'([^\']+)\' ]]; then PKG="${BASH_REMATCH[1]}"; else echo 'ERROR: missing APK package.' >&2; exit 1; fi
[[ "$PKG" == foxlost.miracast.sink ]] || { echo "ERROR: wrong APK package: $PKG" >&2; exit 1; }
if [[ "$BADGING" =~ versionCode=\'([0-9]+)\' ]]; then VCODE="${BASH_REMATCH[1]}"; else echo 'ERROR: missing APK versionCode.' >&2; exit 1; fi
if [[ "$BADGING" =~ versionName=\'([A-Za-z0-9._+-]+)\' ]]; then VNAME="${BASH_REMATCH[1]}"; else echo 'ERROR: unsupported APK versionName.' >&2; exit 1; fi
MANIFEST=$("$AAPT2" dump xmltree "$APK_IN" --file AndroidManifest.xml)
if [[ "$MANIFEST" == *sharedUserId* || "${MANIFEST,,}" == *xposed* ]]; then
    echo 'ERROR: APK contains sharedUserId or Xposed metadata; build the ordinary application first.' >&2
    exit 1
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/module/payload"
# Explicit allowlist: never copy stale system APKs, boot hooks, or binary outputs.
cp magisk-miracast-sink/customize.sh "$STAGE/module/customize.sh"
cp "$APK_IN" "$STAGE/module/payload/MiracastSink.apk"
cat > "$STAGE/module/module.prop" <<PROPS
id=miracast-sink-root
name=Miracast Sink (On-Demand Root)
version=v$VNAME
versionCode=$VCODE
author=foxlost
description=Optional normal-app installer; Miracast Sink requests narrow root access only during sink sessions. No system overlay or boot service.
PROPS
: > "$STAGE/module/skip_mount"
chmod 755 "$STAGE/module/customize.sh"
chmod 644 "$STAGE/module/module.prop" "$STAGE/module/skip_mount" "$STAGE/module/payload/MiracastSink.apk"
OUT="$(pwd -P)/magisk-miracast-sink-v${VNAME}-root.zip"
(cd "$STAGE/module" && zip -r "$STAGE/bundle.zip" module.prop customize.sh skip_mount payload)
mv "$STAGE/bundle.zip" "$OUT"
printf '\nPACK DONE: %s\nPackage: %s, version %s (%s)\nInstall from the running Android system using Magisk/KernelSU module manager.\nThe APK is independently installable; module removal leaves the app/data intact.\n' "$OUT" "$PKG" "$VNAME" "$VCODE"
