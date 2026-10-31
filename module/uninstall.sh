# Runs early in the first boot after the module was removed in the Magisk app,
# before Android is up: so the settings are given back after boot, by restore.sh
# from Magisk's service.d, which then removes the app, the module's data and
# itself. /sdcard/Andronix (the user's config) stays.
mkdir -p /data/adb/service.d
cp -f "${0%/*}/restore.sh" /data/adb/service.d/andronix-restore.sh
chmod 755 /data/adb/service.d/andronix-restore.sh
