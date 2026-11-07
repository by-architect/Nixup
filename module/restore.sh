# Gives back what the module changed, from the values recorded in
# /data/adb/nixup/original before its first change. apply.sh sources it to give
# back one option that left the config. After the module is removed, uninstall.sh
# puts it in Magisk's service.d as nixup-restore.sh: then, once Android is up,
# it gives back everything, removes the app and the module's data, and itself.
# It uses nothing from the module's folder, which is gone by then.
ORIGINAL=/data/adb/nixup/original

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
  owned=/data/adb/nixup/owned
  [ -f "$owned" ] || owned=/data/adb/nixup/fdroid/owned
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
  told=/data/adb/nixup/repos/obtainium/told
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
  for pidfile in /data/adb/nixup/services/*/supervisor.pid /data/adb/nixup/units/*/supervisor.pid; do
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile" 2> /dev/null)
    # Only a supervisor of Nixup's: after a restart its number may be any other process's.
    [ -n "$pid" ] && tr '\0' ' ' < "/proc/$pid/cmdline" 2> /dev/null | grep -q nixup && kill "$pid" 2> /dev/null
  done
  pkill -f /data/adb/nixup/services/ 2> /dev/null
}

if [ "${0##*/}" = nixup-restore.sh ]; then
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
  # The apps' settings and the shared storage read right once the phone was unlocked (its first unlock after a start),
  # as a build waits for it too.
  until [ "$(getprop sys.user.0.ce_available)" = true ]; do sleep 10; done
  restore_dark_mode
  # The engine gives back every app setting and removes the apps it installed;
  # restore_apps is there for a module from before the engine.
  engine=/data/adb/nixup/engine.apk
  restored=0
  if [ -f "$engine" ]; then
    CLASSPATH="$engine" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main restore < /dev/null || restored=$?
  fi
  restore_apps
  restore_debloat
  restore_obtainium
  restore_services
  # What did not go back (the engine says so) is tried again at the next start, 3 times: until then its records of
  # what was there before stay, with the engine and this script.
  tries=$(cat /data/adb/nixup/restore-tries 2> /dev/null || echo 0)
  if [ "$restored" != 0 ] && [ "$tries" -lt 3 ]; then
    echo $((tries + 1)) > /data/adb/nixup/restore-tries
    exit 0
  fi
  pm uninstall dev.nixup.manager < /dev/null > /dev/null 2>&1
  rm -rf /data/adb/nixup
  # Where Seraph (Nixup's old name) kept its data, left as a link to it by the install.
  [ -L /data/adb/seraph ] && rm -f /data/adb/seraph
  rm -f "$0"
fi
