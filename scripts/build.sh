#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'HELP'
Usage: ./scripts/build.sh [-d | --help]
Build and sign an ordinary APK (no platform key). -d runs assembleDebug.
Release: app/build/outputs/apk/release/MiracastSink-v<VERSION>.apk
         app/build/outputs/apk/release/MiracastSink.apk
Debug:   app/build/outputs/apk/debug/MiracastSink-v<VERSION>-debug.apk
         app/build/outputs/apk/debug/MiracastSink-debug.apk
Signing environment (keep the keystore to preserve update compatibility):
  MIRACAST_KEYSTORE          default: $HOME/.local/share/miracast-sink/signing/release.p12
                            Must resolve outside this repository.
  MIRACAST_KEY_ALIAS         default: miracast-sink
  MIRACAST_KEYSTORE_PASSWORD Keystore password; required for a custom keystore.
  MIRACAST_KEY_PASSWORD      default: MIRACAST_KEYSTORE_PASSWORD
The default key/password are generated once with private file permissions. Its
password is stored beside the key in release.p12.password. Back up both files.
For a new custom keystore, provide MIRACAST_KEYSTORE_PASSWORD to generate it.
ANDROID_HOME (default $HOME/Android), APKSIGNER and AAPT2 override SDK paths.
Use JDK 17 (JAVA_HOME) with this project's Gradle wrapper.
Install side-by-side Android NDK 29.0.14206865 for the root identity JNI primitive.
HELP
}

DEBUG_BUILD=false
case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    -d) DEBUG_BUILD=true ;;
    '') ;;
    *) usage >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 1; }

ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android}"
APKSIGNER="${APKSIGNER:-$ANDROID_HOME/build-tools/34.0.0/apksigner}"
AAPT2="${AAPT2:-$ANDROID_HOME/build-tools/34.0.0/aapt2}"
for tool in "$APKSIGNER" "$AAPT2" keytool realpath; do
    command -v "$tool" >/dev/null || { echo "ERROR: required tool unavailable: $tool" >&2; exit 1; }
done

DEFAULT_KEYSTORE="$HOME/.local/share/miracast-sink/signing/release.p12"
KEYSTORE=$(realpath -m "${MIRACAST_KEYSTORE:-$DEFAULT_KEYSTORE}")
REPO=$(pwd -P)
case "$KEYSTORE" in
    "$REPO"|"$REPO"/*) echo 'ERROR: signing keystore must be outside the repository.' >&2; exit 1 ;;
esac
KEY_ALIAS="${MIRACAST_KEY_ALIAS:-miracast-sink}"
umask 077
if [[ -z "${MIRACAST_KEYSTORE_PASSWORD:-}" ]]; then
    if [[ "$KEYSTORE" != "$(realpath -m "$DEFAULT_KEYSTORE")" ]]; then
        echo 'ERROR: set MIRACAST_KEYSTORE_PASSWORD for a custom keystore.' >&2
        exit 1
    fi
    PASSWORD_FILE="$KEYSTORE.password"
    if [[ ! -f "$PASSWORD_FILE" ]]; then
        if [[ -e "$KEYSTORE" ]]; then
            echo 'ERROR: existing key has no password file; set MIRACAST_KEYSTORE_PASSWORD.' >&2
            exit 1
        fi
        mkdir -p -m 700 "$(dirname "$KEYSTORE")"
        # Noclobber also protects concurrent first-time builds.
        (set -o noclobber; od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$PASSWORD_FILE")
    fi
    chmod 600 "$PASSWORD_FILE"
    MIRACAST_KEYSTORE_PASSWORD=$(< "$PASSWORD_FILE")
fi
export MIRACAST_KEYSTORE_PASSWORD
export MIRACAST_KEY_PASSWORD="${MIRACAST_KEY_PASSWORD:-$MIRACAST_KEYSTORE_PASSWORD}"
if [[ ! -e "$KEYSTORE" ]]; then
    mkdir -p -m 700 "$(dirname "$KEYSTORE")"
    if [[ "$MIRACAST_KEY_PASSWORD" != "$MIRACAST_KEYSTORE_PASSWORD" ]]; then
        echo 'ERROR: a new PKCS12 key requires matching key and keystore passwords.' >&2
        exit 1
    fi
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 \
        -dname 'CN=Miracast Sink Local Application Signing' \
        -storepass:env MIRACAST_KEYSTORE_PASSWORD -keypass:env MIRACAST_KEY_PASSWORD
fi
chmod 600 "$KEYSTORE"

VARIANT=release
TASK=assembleRelease
INPUT="app/build/outputs/apk/release/app-release-unsigned.apk"
SUFFIX=''
if $DEBUG_BUILD; then
    VARIANT=debug
    TASK=assembleDebug
    INPUT="app/build/outputs/apk/debug/app-debug.apk"
    SUFFIX=-debug
fi
./gradlew ":app:$TASK" --console=plain
[[ -f "$INPUT" ]] || { echo "ERROR: APK not produced: $INPUT" >&2; exit 1; }
BADGING=$("$AAPT2" dump badging "$INPUT")
if [[ "$BADGING" =~ versionCode=\'([0-9]+)\' ]]; then VCODE="${BASH_REMATCH[1]}"; else echo 'ERROR: missing APK versionCode.' >&2; exit 1; fi
if [[ "$BADGING" =~ versionName=\'([A-Za-z0-9._+-]+)\' ]]; then VNAME="${BASH_REMATCH[1]}"; else echo 'ERROR: unsupported APK versionName.' >&2; exit 1; fi
SIGNED="app/build/outputs/apk/$VARIANT/MiracastSink-v${VNAME}${SUFFIX}.apk"
SIGNED_ALT="app/build/outputs/apk/$VARIANT/MiracastSink${SUFFIX}.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:MIRACAST_KEYSTORE_PASSWORD --key-pass env:MIRACAST_KEY_PASSWORD \
    --min-sdk-version 29 --out "$SIGNED" "$INPUT"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED"
cp "$SIGNED" "$SIGNED_ALT"
printf '\nBUILD SUCCESS: %s\nAlias: %s\nVersion: %s (%s)\nKeystore: %s\n' \
    "$SIGNED" "$SIGNED_ALT" "$VNAME" "$VCODE" "$KEYSTORE"
