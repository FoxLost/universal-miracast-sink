#!/usr/bin/env bash
set -euo pipefail

# ─── Miracast Sink Logcat Filter ──────────────────────────────────────
# Captures every log line produced by foxlost.miracast.sink + the Android
# WiFi P2P / WFD framework layers, colour-coded by subsystem.
#
# Usage:
#   ./scripts/logcat.sh              all tags, live tail
#   ./scripts/logcat.sh -c            clear buffer then tail
#   ./scripts/logcat.sh -s            snapshot – print current buffer, exit
#   ./scripts/logcat.sh app           app-only tags (no framework noise)
#   ./scripts/logcat.sh raw           one grep-friendly regex (for scripting)
#   ./scripts/logcat.sh -h            this help
# ────────────────────────────────────────────────────────────────────────

# Tags our app emits (see source: grep -rn 'TAG = "' app/src/)
APP_TAGS=(
    MiracastApp
    MiracastP2P
    MiracastRTSP
    MiracastUIBC
    MiracastRoot
    MiracastSettings
    TsDemuxer
    RtpReceiver
    MediaDecoderPipeline
)

# Android WiFi P2P + WFD framework tags (system_server / wifi HAL)
FRAMEWORK_TAGS=(
    WifiP2pService
    WifiP2pManager
    WifiP2pNative
    SupplicantP2pIfaceHal
    wpa_supplicant
    HalDevMgr
    WifiDisplaySink
    WifiDisplaySource
    wifi_display
    WifiDisplayController
    WfdSession
    UIBC
    RemoteDisplay
    WifiDiplay
)

# ─── helpers ───

join_pipe() { local IFS='|'; echo "$*"; }

adblog() {
    # adb logcat with timestamps, collapse everything under the PID if we can find it
    local pid
    pid=$(adb shell pidof -s foxlost.miracast.sink 2>/dev/null || true)
    if [[ -n "$pid" ]]; then
        adb logcat -v threadtime --pid="$pid" "$@" 2>&1
    else
        adb logcat -v threadtime "$@" 2>&1
    fi
}

# ─── mode dispatch ───

MODE="${1:-all}"

case "$MODE" in
    -h|--help)
        sed -n '3,17p' "$0"
        exit 0
        ;;
    -c)
        adb logcat -c 2>/dev/null || true
        MODE="all"
        ;;
    -s)
        adblog -d 2>&1 | grep --color=always -E "$(join_pipe "${APP_TAGS[@]}" "${FRAMEWORK_TAGS[@]}")" || true
        exit 0
        ;;
    app)
        adblog 2>&1 | grep --color=always -E "$(join_pipe "${APP_TAGS[@]}")" || true
        exit 0
        ;;
    raw)
        join_pipe "${APP_TAGS[@]}" "${FRAMEWORK_TAGS[@]}"
        exit 0
        ;;
    all|*)
        adblog 2>&1 | grep --color=always -E "$(join_pipe "${APP_TAGS[@]}" "${FRAMEWORK_TAGS[@]}")" || true
        exit 0
        ;;
esac
