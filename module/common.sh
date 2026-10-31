# What the module's scripts share: where things are, the log, and the status shown
# in the Magisk app. Sourced with MODDIR set to the module's folder.
DATA=/data/adb/andronix
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
# Where the user edits the phone config (the app's Open config folder opens it).
CONFIG_DIR=/sdcard/Andronix
CONFIG=$CONFIG_DIR/phone.nix
BY=${BY:-manual}

mkdir -p "$DATA"
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
