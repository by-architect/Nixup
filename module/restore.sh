# Gives back what the module changed, from the values recorded in
# /data/adb/andronix/original before its first change. apply.sh sources it to give
# back one option that left the config. After the module is removed, uninstall.sh
# puts it in Magisk's service.d as andronix-restore.sh: then, once Android is up,
# it gives back everything, removes the app and the module's data, and itself.
# It uses nothing from the module's folder, which is gone by then.
ORIGINAL=/data/adb/andronix/original

restore_dark_mode() {
  was=$(cat "$ORIGINAL/darkMode" 2>/dev/null) || return 0
  cmd uimode night "$was" < /dev/null > /dev/null
  rm -f "$ORIGINAL/darkMode"
  echo "Dark mode: back to $was"
}

# The apps the module installed (not those that were on the phone before it) go
# again; their data stays, so installing one again brings it back. pm gets no
# input: it would hand the list file to Android, which may not read /data/adb.
restore_apps() {
  owned=/data/adb/andronix/owned
  [ -f "$owned" ] || owned=/data/adb/andronix/fdroid/owned
  [ -f "$owned" ] || return 0
  while read -r app; do
    [ -n "$app" ] && pm uninstall -k --user current "$app" < /dev/null > /dev/null 2>&1 && echo "Removed $app (its data is kept)"
  done < "$owned"
  rm -f "$owned"
}

if [ "${0##*/}" = andronix-restore.sh ]; then
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
  restore_dark_mode
  # The engine gives back every app setting and removes the apps it installed;
  # restore_apps is there for a module from before the engine.
  engine=/data/adb/andronix/engine.apk
  [ -f "$engine" ] && CLASSPATH="$engine" /system/bin/app_process /system/bin dev.andronix.manager.engine.Main restore < /dev/null
  restore_apps
  pm uninstall dev.andronix.manager < /dev/null > /dev/null 2>&1
  rm -rf /data/adb/andronix
  rm -f "$0"
fi
