#!/system/bin/sh
# Runs only in the module installer, never at boot. The module mounts nothing.
SKIPUNZIP=0
ui_print "- Miracast Sink ordinary-app install bundle"

for old_module in /data/adb/modules/miracast-sink-system /data/adb/modules_update/miracast-sink-system; do
  if [ -d "$old_module" ]; then
    ui_print "! The old miracast-sink-system overlay is still present."
    ui_print "! Remove that module in your root manager, reboot, then install this bundle."
    ui_print "! Do not uninstall the app or erase its data to migrate."
    abort "! Old system module must be removed first."
  fi
done

APK="$MODPATH/payload/MiracastSink.apk"
[ -f "$APK" ] || abort "! Missing payload/MiracastSink.apk; rebuild this bundle."
# Recovery/offline installs cannot perform a normal PackageManager install.
command -v pm >/dev/null 2>&1 || abort "! pm is unavailable. Install from a running Android system, not recovery."
PM_READY=$(pm path android 2>&1)
PM_READY_STATUS=$?
if [ "$PM_READY_STATUS" -ne 0 ]; then
  ui_print "$PM_READY"
  abort "! PackageManager is unavailable. Boot Android fully and retry."
fi
case "$PM_READY" in
  *package:*) ;;
  *) ui_print "$PM_READY"; abort "! PackageManager is not ready. Boot Android fully and retry." ;;
esac

ui_print "- Installing/updating foxlost.miracast.sink as a normal app for user 0"
# -r preserves existing application data. Never uninstall or clear caches/data.
# The installer shell opens the private payload; PackageManager receives only stdin.
APK_SIZE=$(wc -c < "$APK" | tr -d '[:space:]')
case "$APK_SIZE" in
  ''|*[!0-9]*|0) abort "! Cannot determine a positive payload APK size; rebuild this bundle." ;;
esac
# No positional path means stdin with -S. Android 16 can parse a bare "-" as an unknown option.
PM_RESULT=$(pm install -r --user 0 -S "$APK_SIZE" 2>&1 < "$APK")
PM_STATUS=$?
PM_SUCCESS=false
while IFS= read -r line; do
  [ "$line" = "Success" ] && PM_SUCCESS=true
done <<RESULT
$PM_RESULT
RESULT
if [ "$PM_STATUS" -ne 0 ] || [ "$PM_SUCCESS" != true ]; then
  ui_print "$PM_RESULT"
  ui_print "! APK installation failed; existing app and data have not been removed."
  ui_print "! Resolve the PackageManager error above before retrying; do not uninstall or clear app data."
  ui_print "! If it reports a signature/shared-UID conflict, follow the README migration instructions."
  abort "! Normal APK installation did not succeed."
fi
# No uninstall.sh: removing the optional module must leave the app and data alone.
touch "$MODPATH/skip_mount"
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$APK" 0 0 0644
ui_print "- Normal app installed. Grant root only when starting a sink session."
ui_print "- No system overlay, boot service, or reboot is required by this bundle."
ui_print "- Removing this module will not remove the installed app or its data."
