# Runs early in the first boot after the module was removed in the Magisk app,
# before Android is up: so the settings are given back after boot, by restore.sh
# from Magisk's service.d (with the engine for the app settings and the apps it
# installed), which then removes the app, the module's data and itself.
# The user's config stays where they keep it: Nixup only reads it (and a flake's
# build writes its flake.lock next to it).
mkdir -p /data/adb/service.d
cp -f "${0%/*}/restore.sh" /data/adb/service.d/nixup-restore.sh
# The engine gives back the app settings, from a copy, as the module's folder goes.
cp -f "${0%/*}/app.apk" /data/adb/nixup/engine.apk
chmod 755 /data/adb/service.d/nixup-restore.sh
