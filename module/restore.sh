# Gives back what the module changed, from the values recorded in
# /data/adb/seraph/original before its first change. apply.sh sources it to give
# back one option that left the config. After the module is removed, uninstall.sh
# puts it in Magisk's service.d as seraph-restore.sh: then, once Android is up,
# it gives back everything, removes the app and the module's data, and itself.
# It uses nothing from the module's folder, which is gone by then.
ORIGINAL=/data/adb/seraph/original

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
  owned=/data/adb/seraph/owned
  [ -f "$owned" ] || owned=/data/adb/seraph/fdroid/owned
  [ -f "$owned" ] || return 0
  while read -r app; do
    [ -n "$app" ] && pm uninstall -k --user current "$app" < /dev/null > /dev/null 2>&1 && echo "Removed $app (its data is kept)"
  done < "$owned"
  rm -f "$owned"
}

# The apps that came with the phone and were removed (android.debloat) come back
# (the engine does this, with their enabled state; this is for when it could not
# run). The phone kept them and their data: install-existing brings them back.
restore_debloat() {
  journal=$ORIGINAL/debloat.json
  [ -f "$journal" ] || return 0
  grep -o '"[A-Za-z0-9_.]*": *{' "$journal" | cut -d'"' -f2 | while read -r app; do
    cmd package install-existing --user 0 "$app" < /dev/null > /dev/null 2>&1 && echo "Brought $app back"
  done
  rm -f "$journal"
}

# The apps the module put in Obtainium's list go out of it again (the engine does
# this; this is for when it could not run). Only the files the module wrote.
restore_obtainium() {
  told=/data/adb/seraph/repos/obtainium/told
  [ -f "$told" ] || return 0
  while read -r file; do
    case "$file" in
      /data/media/0/Android/data/*/files/app_data/*.json) rm -f "$file" && echo "Obtainium: ${file##*/} taken out of its list" ;;
    esac
  done < "$told"
  rm -f "$told"
}

# The services stop (the engine does this, logging out of Tailscale first; this is
# for when it could not run): their loops, then the programs they ran.
restore_services() {
  for pidfile in /data/adb/seraph/services/*/supervisor.pid; do
    [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2> /dev/null
  done
  pkill -f /data/adb/seraph/services/ 2> /dev/null
}

if [ "${0##*/}" = seraph-restore.sh ]; then
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
  restore_dark_mode
  # The engine gives back every app setting and removes the apps it installed;
  # restore_apps is there for a module from before the engine.
  engine=/data/adb/seraph/engine.apk
  [ -f "$engine" ] && CLASSPATH="$engine" /system/bin/app_process /system/bin dev.seraph.manager.engine.Main restore < /dev/null
  restore_apps
  restore_debloat
  restore_obtainium
  restore_services
  pm uninstall dev.seraph.manager < /dev/null > /dev/null 2>&1
  rm -rf /data/adb/seraph
  rm -f "$0"
fi
