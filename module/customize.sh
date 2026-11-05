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
# What tells the app about events, for the services too ($SERAPH_NOTIFY).
set_perm "$MODPATH/notify.sh" 0 0 0755
# seraph-shell and seraph-backup, in /system/bin once Magisk mounts the module (from the next start).
set_perm "$MODPATH/system/bin/seraph-shell" 0 0 0755
set_perm "$MODPATH/system/bin/seraph-backup" 0 0 0755
ln -sf nix "$MODPATH/bin/nix-instantiate"
ln -sf nix "$MODPATH/bin/nix-store"

# Streamed in, so the installer does not need to read the module's folder.
ui_print "- Installing the app"
# For the phone's owner only: Magisk gives root to the owner's apps (its default), and an app clone,
# work profile or other user would only be refused it, without asking.
install_app() { pm install -r --user 0 -S "$(stat -c %s "$MODPATH/app.apk")" < "$MODPATH/app.apk" 2>&1; }
said=$(install_app)
case "$said" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *INSTALL_FAILED_VERSION_DOWNGRADE* | *"signatures do not match"*)
    # The app there is signed with another key (a build of one's own, or a release after one), or
    # is newer: it goes first. It keeps nothing of its own (Seraph's data is in /data/adb/seraph);
    # the root solution asks once more to let it use root, as for any app installed anew.
    ui_print "- The app there is signed with another key, or is newer: replacing it"
    pm uninstall dev.seraph.manager < /dev/null > /dev/null 2>&1
    said=$(install_app)
    ui_print "- Seraph's app is a new app to the root solution now: allow it root again when it asks" ;;
esac
case "$said" in
  *Success*) ;;
  *) ui_print "! The app did not install ($said): install app.apk from the module's folder by hand." ;;
esac
# Out of the phone's other users (an app clone's, a work profile's), where an earlier install put it.
for user in $(pm list users 2> /dev/null | sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p'); do
  [ "$user" = 0 ] && continue
  pm list packages --user "$user" dev.seraph.manager 2> /dev/null | grep -qx 'package:dev.seraph.manager' || continue
  pm uninstall --user "$user" dev.seraph.manager < /dev/null > /dev/null 2>&1 \
    && ui_print "- Removed the app from user $user (an app clone or work profile): root is the owner's"
done
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
