# Runs once while Magisk installs the module (sourced by Magisk's installer):
# makes the profile packed in the module the one the phone follows.
[ "$API" -ge 29 ] || abort "! Needs Android 10 or newer (this phone has API $API)."

ui_print "- Profile: $(cat "$MODPATH/profile.json")"
mkdir -p /data/adb/andronix
cp -f "$MODPATH/profile.json" /data/adb/andronix/profile.json
ui_print "- Applied at every boot, and by the module's Action button."
