#!/usr/bin/env bash
set -euo pipefail

# ─── Miracast Sink Host Debug Capture ──────────────────────────────────
# Captures root dmesg, all Android logcat buffers, and read-only device state
# without starting/stopping the app or changing Wi-Fi state.
#
# Usage:
#   ./scripts/capture-debug.sh
#   ANDROID_SERIAL=<serial> ./scripts/capture-debug.sh
#   ./scripts/capture-debug.sh -s <serial>
#
# Press Ctrl-C to stop capture, collect final snapshots, archive, and hash.
# ────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

SERIAL="${ANDROID_SERIAL:-}"

usage() {
    sed -n '3,15p' "$0"
}

while (($#)); do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        -s|--serial)
            (($# >= 2)) || { echo "ERROR: $1 requires a serial" >&2; exit 2; }
            SERIAL="$2"
            shift 2
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

command -v adb >/dev/null 2>&1 || {
    echo "ERROR: adb is not on PATH" >&2
    exit 1
}
command -v setsid >/dev/null 2>&1 || {
    echo "ERROR: setsid is required to stop capture process groups cleanly" >&2
    exit 1
}

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=( -s "$SERIAL" )
fi

STATE="$("${ADB[@]}" get-state 2>/dev/null || true)"
if [[ "$STATE" != "device" ]]; then
    echo "ERROR: adb device is not ready (state: ${STATE:-none})" >&2
    echo "Connected devices:" >&2
    adb devices -l >&2 || true
    exit 1
fi

ROOT_UID="$("${ADB[@]}" shell su -c 'id -u' 2>/dev/null | tr -d '\r[:space:]')"
if [[ "$ROOT_UID" != "0" ]]; then
    echo "ERROR: root is required for dmesg (su uid=${ROOT_UID:-unknown})" >&2
    exit 1
fi

STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="${CAPTURE_OUT:-$REPO_DIR/capture-debug-$STAMP}"
mkdir -p "$OUT"

FILTER_RE='miracast|wifi|wfd|p2p|supplicant|nl80211|cfg80211|wlan|rtsp|rtp|audio|video|media|codec|surface|buffer|drm|omx|stagefright|avc|hevc|h264|mpeg|socket|tcp|udp|exception|fatal|crash|anr|error|warn|fail|timeout|reset'

run_save() {
    local output="$1"
    shift
    "$@" > "$output" 2>&1 || true
}

collect_state() {
    local phase="$1"
    local prefix="$OUT/${phase}-${STAMP}"

    run_save "$prefix.getprop.txt" "${ADB[@]}" shell getprop
    run_save "$prefix.wifi.txt" "${ADB[@]}" shell dumpsys wifi
    run_save "$prefix.service.txt" "${ADB[@]}" shell dumpsys activity services foxlost.miracast.sink
    run_save "$prefix.window.txt" "${ADB[@]}" shell dumpsys window
    run_save "$prefix.tcp.txt" "${ADB[@]}" shell 'cat /proc/net/tcp; cat /proc/net/tcp6'
    run_save "$prefix.listener-7236.txt" "${ADB[@]}" shell 'netstat -ltnp 2>/dev/null | sed -n "/:7236/p"'
}

# Preserve old buffers and read-only state before clearing volatile buffers.
run_save "$OUT/preclear-${STAMP}.logcat.raw.txt" "${ADB[@]}" logcat -b all -d -v threadtime
run_save "$OUT/preclear-${STAMP}.dmesg.raw.txt" "${ADB[@]}" shell su -c 'dmesg -T'
collect_state preclear

# Explicitly clear only volatile diagnostic buffers. No app, Wi-Fi, or files on
# the device are changed by this script.
if ! "${ADB[@]}" logcat -b all -c > "$OUT/clear-${STAMP}.logcat.txt" 2>&1; then
    echo "ERROR: unable to clear Android logcat buffers" >&2
    exit 1
fi
if ! "${ADB[@]}" shell su -c 'dmesg -C' > "$OUT/clear-${STAMP}.dmesg.txt" 2>&1; then
    echo "ERROR: unable to clear kernel dmesg buffer" >&2
    exit 1
fi

LOGCAT_RAW="$OUT/live-${STAMP}.logcat.raw.txt"
LOGCAT_FILTERED="$OUT/live-${STAMP}.logcat.filtered.txt"
DMESG_RAW="$OUT/live-${STAMP}.dmesg.raw.txt"
DMESG_FILTERED="$OUT/live-${STAMP}.dmesg.filtered.txt"

# Each stream runs in its own session/process group. tee retains raw output;
# awk writes a line-buffered, labelled filtered view to both the terminal and
# its timestamped filtered file.
setsid bash -c '
    set -o pipefail
    if [[ -n "$1" ]]; then
        ADB=(adb -s "$1")
    else
        ADB=(adb)
    fi
    "${ADB[@]}" logcat -b all -v threadtime 2>>"$2.stderr" |
        tee "$2" |
        awk -v re="$3" -v label="$4" "tolower(\$0) ~ re { print \"[\" label \"] \" \$0; fflush() }" |
        tee "$5"
' _ "${SERIAL:-}" "$LOGCAT_RAW" "$FILTER_RE" logcat "$LOGCAT_FILTERED" &
LOGCAT_PID=$!

setsid bash -c '
    set -o pipefail
    if [[ -n "$1" ]]; then
        ADB=(adb -s "$1")
    else
        ADB=(adb)
    fi
    "${ADB[@]}" exec-out shell su -c "if dmesg -wT >/dev/null 2>&1 & p=\$!; then sleep 1; if kill -0 \$p 2>/dev/null; then kill \$p; wait \$p 2>/dev/null || true; exec dmesg -wT; fi; fi; while :; do dmesg -T; sleep 1; done" 2>>"$2.stderr" |
        tee "$2" |
        awk -v re="$3" -v label="$4" "tolower(\$0) ~ re { print \"[\" label \"] \" \$0; fflush() }" |
        tee "$5"
' _ "${SERIAL:-}" "$DMESG_RAW" "$FILTER_RE" dmesg "$DMESG_FILTERED" &
DMESG_PID=$!


FINISHED=0
stop_group() {
    local pid="$1"
    if kill -0 "$pid" 2>/dev/null; then
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
        for _ in {1..50}; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.1
        done
        if kill -0 "$pid" 2>/dev/null; then
            kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
        fi
        wait "$pid" 2>/dev/null || true
    fi
}
finish() {
    ((FINISHED)) && return
    FINISHED=1
    trap - INT TERM

    echo
    echo "Stopping capture..."
    stop_group "$LOGCAT_PID"
    stop_group "$DMESG_PID"

    collect_state final
    run_save "$OUT/final-${STAMP}.logcat.raw.txt" "${ADB[@]}" logcat -b all -d -v threadtime
    run_save "$OUT/final-${STAMP}.dmesg.raw.txt" "${ADB[@]}" shell su -c 'dmesg -T'

    awk -v re="$FILTER_RE" 'tolower($0) ~ re { print }' \
        "$OUT/final-${STAMP}.logcat.raw.txt" \
        > "$OUT/final-${STAMP}.logcat.filtered.txt" || true
    awk -v re="$FILTER_RE" 'tolower($0) ~ re { print }' \
        "$OUT/final-${STAMP}.dmesg.raw.txt" \
        > "$OUT/final-${STAMP}.dmesg.filtered.txt" || true

    local archive="${OUT}.tar.gz"
    if tar -C "$(dirname "$OUT")" -czf "$archive" "$(basename "$OUT")"; then
        sha256sum "$archive" | tee "${archive}.sha256"
        echo "Capture archive: $archive"
    else
        echo "WARNING: unable to create capture archive" >&2
    fi
    echo "Capture directory: $OUT"
}

trap 'finish; exit 130' INT
trap 'finish; exit 143' TERM
trap finish EXIT
cat > "$OUT/capture-${STAMP}.metadata.txt" <<EOF
serial=${SERIAL:-adb-default}
started=${STAMP}
output=$OUT
logcat_pid=$LOGCAT_PID
dmesg_pid=$DMESG_PID
filter_regex=$FILTER_RE
EOF

cat <<EOF
Capture started before any app or Wi-Fi action.
Device: ${SERIAL:-adb default}
Output: $OUT
Raw logcat: $LOGCAT_RAW
Filtered logcat: $LOGCAT_FILTERED
Raw dmesg: $DMESG_RAW
Filtered dmesg: $DMESG_FILTERED

The script performs no app launch, sink start, source connection, Wi-Fi change,
or interface reconfiguration. Press Ctrl-C to stop, snapshot, archive, and hash.

EOF

while kill -0 "$LOGCAT_PID" 2>/dev/null && kill -0 "$DMESG_PID" 2>/dev/null; do
    sleep 1
done

echo "A capture stream exited; finalizing." >&2
finish
