# What the module's scripts share: where things are, the log, and the status shown
# in the Magisk app. Sourced with MODDIR set to the module's folder.
DATA=/data/adb/seraph
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
# Where the user edits the phone config (the app's Open config folder opens it).
# (SERAPH_CONFIG_DIR: another folder, for a test.)
CONFIG_DIR=${SERAPH_CONFIG_DIR:-/sdcard/Seraph}
CONFIG=$CONFIG_DIR/configuration.nix
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

# The config folder with the default config in it, unless the user has one already
# (a configuration.nix, or a flake.nix).
seed_config() {
  [ -f "$CONFIG" ] || [ -f "$CONFIG_DIR/flake.nix" ] && return 0
  mkdir -p "$CONFIG_DIR" && cp "$MODDIR/config/configuration.nix" "$CONFIG" || return 1
  log "Created $CONFIG with the default config"
}

# The engine, from the app's APK, run as root; what it says is shown and logged.
engine() {
  { CLASSPATH="$MODDIR/app.apk" /system/bin/app_process /system/bin dev.seraph.manager.engine.Main "$@" 2>&1
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
  # A flake's lock, the exact versions of its inputs: going back to it builds them again.
  [ -f "$CONFIG_DIR/flake.nix" ] && [ -f "$CONFIG_DIR/flake.lock" ] && cp "$CONFIG_DIR/flake.lock" "$generations/new/flake.lock"
  if [ "$last" -gt 0 ] && diff -r "$generations/new" "$generations/$last" > /dev/null 2>&1; then
    rm -rf "$generations/new"
    log "Generation $last (nothing changed)"
  else
    mv "$generations/new" "$generations/$((last + 1))"
    echo "$((last + 1))" > "$generations/last"
    log "Generation $((last + 1))"
  fi
}

# Nix is a Linux program: it looks for DNS servers, trusted certificates and users
# in /etc, where Android has none. To download (a flake's inputs), it runs in a mount
# namespace of its own whose /etc is Seraph's: the DNS servers of the network in use,
# Android's trusted certificates and a root user. The namespace's mounts are private,
# so the rest of the phone never sees it (its / is shared: a mount without that would
# reach everything).
BUSYBOX=
for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  [ -x "$b" ] && BUSYBOX=$b && break
done

nix_etc() {
  etc=$DATA/etc
  mkdir -p "$etc/ssl/certs" || return 1
  cat /apex/com.android.conscrypt/cacerts/* /system/etc/security/cacerts/* > "$etc/ssl/certs/ca-certificates.crt" 2> /dev/null
  echo "root:x:0:0:root:$DATA/home:/system/bin/sh" > "$etc/passwd"
  echo "root:x:0:" > "$etc/group"
  CLASSPATH="$MODDIR/app.apk" /system/bin/app_process /system/bin dev.seraph.manager.engine.Main dns < /dev/null > "$etc/resolv.conf.new" \
    && mv -f "$etc/resolv.conf.new" "$etc/resolv.conf"
}

# Runs a command with Seraph's /etc (nix_etc makes it), in that namespace.
nix_run() {
  [ -n "$BUSYBOX" ] || { log "There is no busybox (Magisk's or KernelSU's) to give Nix its own /etc"; return 1; }
  "$BUSYBOX" unshare -m --propagation private sh -c \
    'b=$0 etc=$1; shift; "$b" mount --make-rprivate / && "$b" mount --bind "$etc" "$(readlink -f /etc)" && exec "$@"' \
    "$BUSYBOX" "$DATA/etc" "$@"
}

# Nix's settings: flakes on, no build users or sandbox (it only evaluates and
# downloads), and no global registry (inputs are named by their addresses).
nix_conf() {
  mkdir -p "$DATA/home"
  printf '%s\n' 'experimental-features = nix-command flakes' 'build-users-group =' 'sandbox = false' \
    'flake-registry =' 'warn-dirty = false' > "$DATA/home/nix.conf"
}

# Tells the app something through its receiver (as the engine does for its
# notifications): "schedule" makes sure its 6-hourly update check is scheduled.
tell_app() {
  am broadcast --user current -f 0x20 -n dev.seraph.manager/.UpdatesReceiver \
    -a dev.seraph.manager.action.UPDATES --es event "$1" < /dev/null > /dev/null 2>&1
}
