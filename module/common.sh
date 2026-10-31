# What the module's scripts share: where things are, the log, and the status shown
# in the Magisk app. Sourced with MODDIR set to the module's folder.
DATA=/data/adb/andronix
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
# Where the user edits the phone config (the app's Open config folder opens it).
CONFIG_DIR=/sdcard/Andronix
CONFIG=$CONFIG_DIR/phone.nix
BY=${BY:-manual}

mkdir -p "$DATA/tmp"
# Keep the log small: past 64 KB the old one is set aside.
[ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 65536 ] && mv -f "$LOG" "$LOG.old"

log() {
  echo "$*"
  echo "$(date '+%F %T') [$BY] $*" >> "$LOG"
}

# Shown under the module's name in the Magisk app.
set_status() {
  sed -i "s|^description=.*|description=$1|" "$MODDIR/module.prop"
}

fail() {
  log "FAILED: $*"
  set_status "✘ $* (log: $LOG)"
  exit 1
}

# Each setting's value from before the module first changed it, given back by
# restore.sh when its option leaves the config or the module is removed.
ORIGINAL=$DATA/original
remember() {
  [ -f "$ORIGINAL/$1" ] && return 0
  mkdir -p "$ORIGINAL" && echo "$2" > "$ORIGINAL/$1"
}

# The config folder with the default config in it, unless the user has one already.
seed_config() {
  [ -f "$CONFIG" ] && return 0
  mkdir -p "$CONFIG_DIR" && cp "$MODDIR/config/phone.nix" "$CONFIG" || return 1
  log "Created $CONFIG with the default config"
}

# The engine, from the app's APK, run as root; what it says is shown and logged.
engine() {
  { CLASSPATH="$MODDIR/app.apk" /system/bin/app_process /system/bin dev.andronix.manager.engine.Main "$@" 2>&1
    echo $? > "$DATA/tmp/engine.status"; } | tee -a "$LOG"
  return "$(cat "$DATA/tmp/engine.status")"
}

# A new generation, as nixos-rebuild makes one when the system changed: the
# profile, and the versions the config's apps are at.
new_generation() {
  generations=$DATA/generations
  last=$(cat "$generations/last" 2> /dev/null || echo 0)
  mkdir -p "$generations/new"
  cp "$PROFILE" "$generations/new/profile.json"
  [ -f "$DATA/resolved.json" ] && cp "$DATA/resolved.json" "$generations/new/apps.json"
  if [ "$last" -gt 0 ] && diff -r "$generations/new" "$generations/$last" > /dev/null 2>&1; then
    rm -rf "$generations/new"
    log "Generation $last (nothing changed)"
  else
    mv "$generations/new" "$generations/$((last + 1))"
    echo "$((last + 1))" > "$generations/last"
    log "Generation $((last + 1))"
  fi
}

# Tells the app something through its receiver (as the engine does for its
# notifications): "schedule" makes sure its 6-hourly update check is scheduled.
tell_app() {
  am broadcast --user current -f 0x20 -n dev.andronix.manager/.UpdatesReceiver \
    -a dev.andronix.manager.action.UPDATES --es event "$1" < /dev/null > /dev/null 2>&1
}
