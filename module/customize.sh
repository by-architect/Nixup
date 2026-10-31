# Runs once while Magisk installs the module (sourced by Magisk's installer): makes
# Nix runnable, installs the app, and starts the phone off with the default config.
[ "$API" -ge 30 ] || abort "! Needs Android 11 or newer (this phone has API $API)."
[ "$ARCH" = arm64 ] || abort "! Needs a 64-bit ARM phone (this one is $ARCH): Nix is built for it."

set_perm "$MODPATH/bin/nix" 0 0 0755
ln -sf nix "$MODPATH/bin/nix-instantiate"

# Streamed in, so the installer does not need to read the module's folder.
ui_print "- Installing the app"
pm install -r -S "$(stat -c %s "$MODPATH/app.apk")" < "$MODPATH/app.apk" > /dev/null \
  || ui_print "! The app did not install: install app.apk from the module's folder by hand."

# A config built on the phone stays; only the first time does the default one go in.
MODDIR=$MODPATH
BY=install
. "$MODPATH/common.sh"
[ -f "$PROFILE" ] || cp -f "$MODPATH/profile.json" "$PROFILE"
seed_config || ui_print "! Could not create $CONFIG: the app's Build button makes it."
ui_print "- Config: $CONFIG (open it with the app; Build applies it)"
