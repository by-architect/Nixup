# What the module's scripts share: where things are, the log, and the status shown
# in the Magisk app. Sourced with MODDIR set to the module's folder.
DATA=/data/adb/seraph
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
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

# What a build reads, kept in $DATA/source as the app's Config picked it (or a script): a file
# on the phone, wherever the user keeps it (internal storage, Download, an SD card, ...), which
# is a flake when it is a flake.nix (its folder is the flake, as /etc/nixos is for
# nixos-rebuild --flake) and a configuration.nix built alone otherwise; or a flake's address,
# like github:owner/repo, which the build fetches. Either may end in #name, the configuration
# of the flake to build. Seraph keeps no config folder of its own.
# (SERAPH_SOURCE: another one, for one build.)
SOURCE=$DATA/source
config_source() {
  if [ -n "$SERAPH_SOURCE" ]; then echo "$SERAPH_SOURCE"; return 0; fi
  [ -s "$SOURCE" ] && { head -n 1 "$SOURCE"; return 0; }
  # Before the config could be picked it was in /sdcard/Seraph, with the app's Build from
  # choosing flake.nix or configuration.nix there: a phone set up then goes on with it.
  old=/storage/emulated/0/Seraph
  case "$(cat "$DATA/build-from" 2> /dev/null)" in
    flake) [ -f "$old/flake.nix" ] && echo "$old/flake.nix" ;;
    configuration) [ -f "$old/configuration.nix" ] && echo "$old/configuration.nix" ;;
    *) if [ -f "$old/flake.nix" ]; then echo "$old/flake.nix"; elif [ -f "$old/configuration.nix" ]; then echo "$old/configuration.nix"; fi ;;
  esac
  return 0
}

# A picked file as root reads it: through symlinks (/sdcard), and for an SD card
# (/storage/<card>/...) also under /mnt/media_rw/<card>/, when /storage has no view of it.
readable_path() {
  [ -e "$1" ] && { readlink -f "$1"; return 0; }
  case "$1" in
    /storage/emulated/*) ;;
    /storage/*) [ -e "/mnt/media_rw/${1#/storage/}" ] && { echo "/mnt/media_rw/${1#/storage/}"; return 0; } ;;
  esac
  return 1
}

# The engine, from the app's APK, run as root; what it says is shown and logged.
engine() {
  { CLASSPATH="$MODDIR/app.apk" /system/bin/app_process /system/bin dev.seraph.manager.engine.Main "$@" 2>&1
    echo $? > "$DATA/tmp/engine.status"; } | tee -a "$LOG"
  return "$(cat "$DATA/tmp/engine.status")"
}

# A new generation, as nixos-rebuild makes one when the system changed: the
# profile, the versions the config's apps are at, what it was built from
# (GEN_SOURCE), the PC it was built on (GEN_BUILT_ON; none: the phone) and a
# flake's lock (GEN_LOCK: the exact versions of its inputs, so going back to it
# builds them again). An update of the apps alone, or the last profile applied
# again, keeps the last generation's.
new_generation() {
  generations=$DATA/generations
  last=$(cat "$generations/last" 2> /dev/null || echo 0)
  rm -rf "$generations/new"
  mkdir -p "$generations/new"
  cp "$PROFILE" "$generations/new/profile.json"
  [ -f "$DATA/resolved.json" ] && cp "$DATA/resolved.json" "$generations/new/apps.json"
  if [ -n "$GEN_SOURCE" ]; then
    echo "$GEN_SOURCE" > "$generations/new/source"
    [ -n "$GEN_BUILT_ON" ] && echo "$GEN_BUILT_ON" > "$generations/new/built-on"
    [ -n "$GEN_LOCK" ] && [ -f "$GEN_LOCK" ] && cp "$GEN_LOCK" "$generations/new/flake.lock"
  elif [ "$last" -gt 0 ]; then
    for kept in source built-on flake.lock; do
      [ -f "$generations/$last/$kept" ] && cp "$generations/$last/$kept" "$generations/new/$kept"
    done
  fi
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
# in /etc ($DATA/etc), where Android has none. To download (a flake's inputs), it runs in a mount
# namespace of its own whose /etc is Seraph's: the DNS servers of the network in use,
# Android's trusted certificates and a root user. The namespace's mounts are private,
# so the rest of the phone never sees it (its / is shared: a mount without that would
# reach everything).
BUSYBOX=
for b in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  [ -x "$b" ] && BUSYBOX=$b && break
done

# The engine writes it (tailscaled runs with it too); it fails without a network.
nix_etc() {
  CLASSPATH="$MODDIR/app.apk" /system/bin/app_process /system/bin dev.seraph.manager.engine.Main etc < /dev/null
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

# The second half of every build, wherever the profile was built (build.sh on the
# phone, remote.sh for a PC): its plan and every check (all a dry run does), then its
# apps and settings, activated, and a new generation. When a check fails, nothing
# changes. Arguments: the profile, and what to do (switch, dry, update); SECRETS_IN
# is the folder of the secrets a PC sent with it.
switch_to() {
  log "== Apps"
  sent=
  [ -n "$SECRETS_IN" ] && sent="--secrets=$SECRETS_IN"
  case "$2" in
    dry) engine apply "$1" --dry-run $sent ;;
    update) engine apply "$1" --force $sent ;;
    *) engine apply "$1" $sent ;;
  esac
  case $? in
    0) ;;
    2) rm -f "$1"; fail "a check failed (see above); nothing was changed" ;;
    *) rm -f "$1"; fail "stopped partway (see above); build again to carry on" ;;
  esac
  if [ "$2" = dry ]; then
    rm -f "$1"
    log "Dry run: nothing was changed"
    return 0
  fi
  log "== Activating"
  mv -f "$1" "$PROFILE"
  sh "$MODDIR/apply.sh" "$BY" || exit 1
  new_generation
  # The app schedules its update check, now that the config may have changed.
  tell_app schedule &
}

# Tokens for private repositories (a flake's, or its inputs'), in Nix's own form, one
# per line (github.com=ghp_…), from a file only root can read; Nix gets them for each
# build, and they are written nowhere else.
nix_tokens() {
  tokens=$DATA/secrets/nix-access-tokens
  [ -s "$tokens" ] && export NIX_CONFIG="access-tokens = $(tr -s '\n' ' ' < "$tokens")"
  return 0
}

# Tells the app something through its receiver (as the engine does for its
# notifications): "schedule" makes sure its 6-hourly update check is scheduled.
tell_app() {
  am broadcast --user current -f 0x20 -n dev.seraph.manager/.UpdatesReceiver \
    -a dev.seraph.manager.action.UPDATES --es event "$1" < /dev/null > /dev/null 2>&1
}
