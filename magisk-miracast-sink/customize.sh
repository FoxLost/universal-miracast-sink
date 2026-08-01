#!/system/bin/sh
# Magisk module customization
ui_print "- Miracast Sink system priv-app module"
# APK must be signed with platform key before inserting here
if [ -f "$MODPATH/system/priv-app/MiracastSink/MiracastSink.apk" ]; then
  ui_print "- APK found, installing..."
  # Clear stale ART caches to prevent SIGILL/RemoteServiceException
  ui_print "- Clearing app caches..."
  for cache in /data/dalvik-cache/arm*/system@priv-app@MiracastSink@MiracastSink.apk@classes.* \
               /data/dalvik-cache/arm/system@priv-app@MiracastSink@MiracastSink.apk@classes.*; do
    rm -f "$cache" 2>/dev/null
  done
  rm -rf /data/system/package_cache/*/MiracastSink-* 2>/dev/null
  rm -f /data/user/0/com.android.launcher3/databases/app_icons.db* 2>/dev/null
  ui_print "- App caches cleared"
else
  ui_print "! APK not found. Place the signed APK at:"
  ui_print "!   $MODPATH/system/priv-app/MiracastSink/MiracastSink.apk"
  ui_print "! BEFORE flashing this module."
  abort "! Missing APK"
fi
