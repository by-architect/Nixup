# Runs once while Magisk installs the module (sourced by Magisk's installer): makes
# Nix runnable, installs the app, and starts the phone off with the default profile
# (which manages nothing). The config is the user's, wherever they keep it: the app's
# Config picks it, and Seraph makes no folder for it.
[ "$API" -ge 30 ] || abort "! Needs Android 11 or newer (this phone has API $API)."
[ "$ARCH" = arm64 ] || abort "! Needs a 64-bit ARM phone (this one is $ARCH): Nix is built for it."

# Seraph was called Andronix. An install of it is taken over, so what it kept goes on:
# the values to give back (original/), the apps it installed, the generations, the
# indexes, and the config. Its module goes without its uninstall script running (that
# would give every setting back and remove its apps: Seraph keeps managing them), and so
# does its app. Before common.sh, which makes Seraph's own data folder.
if [ -d /data/adb/andronix ] && [ ! -e /data/adb/seraph ]; then
  mv /data/adb/andronix /data/adb/seraph && ui_print "- Took over Andronix's data"
fi
if [ -d /sdcard/Andronix ] && [ ! -e /sdcard/Seraph ]; then
  mv /sdcard/Andronix /sdcard/Seraph
  for name in phone.nix phone.nix.old; do
    [ -f "/sdcard/Seraph/$name" ] && mv "/sdcard/Seraph/$name" "/sdcard/Seraph/configuration${name#phone}"
  done
  ui_print "- Took over the config: /sdcard/Seraph/configuration.nix"
fi
for old in /data/adb/modules/andronix /data/adb/modules_update/andronix; do
  [ -d "$old" ] && rm -rf "$old" && ui_print "- Removed the Andronix module ($old)"
done
rm -f /data/adb/service.d/andronix-restore.sh
pm uninstall dev.andronix.manager < /dev/null > /dev/null 2>&1 && ui_print "- Removed the Andronix app"

set_perm "$MODPATH/bin/nix" 0 0 0755
# seraph-shell, in /system/bin once Magisk mounts the module (from the next start).
set_perm "$MODPATH/system/bin/seraph-shell" 0 0 0755
ln -sf nix "$MODPATH/bin/nix-instantiate"

# Streamed in, so the installer does not need to read the module's folder.
ui_print "- Installing the app"
pm install -r -S "$(stat -c %s "$MODPATH/app.apk")" < "$MODPATH/app.apk" > /dev/null \
  || ui_print "! The app did not install: install app.apk from the module's folder by hand."
# Its notifications say how installs and updates go.
pm grant dev.seraph.manager android.permission.POST_NOTIFICATIONS > /dev/null 2>&1

# A profile built on the phone stays; only the first time does the default one go in.
MODDIR=$MODPATH
BY=install
. "$MODPATH/common.sh"
[ -f "$PROFILE" ] || cp -f "$MODPATH/profile.json" "$PROFILE"
source=$(config_source)
if [ -n "$source" ]; then
  ui_print "- Config: $source (the app's Build applies it)"
else
  ui_print "- No config yet: in the Seraph app, Config picks your configuration.nix or flake.nix (New makes one)"
fi
# The app schedules its update check (a new app has none yet).
tell_app schedule
