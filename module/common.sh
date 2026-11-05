# What the module's scripts share: where things are, the log, and the status shown
# in the Magisk app. Sourced with MODDIR set to the module's folder.
DATA=/data/adb/seraph
PROFILE=$DATA/profile.json
LOG=$DATA/apply.log
BY=${BY:-manual}
# Who started it, for the engine's events too.
export SERAPH_BY=$BY

mkdir -p "$DATA/tmp"
# Keep the log small: past 64 KB the old one is set aside.
[ -f "$LOG" ] && [ "$(stat -c %s "$LOG")" -gt 65536 ] && mv -f "$LOG" "$LOG.old"

# printf, not echo: Android's echo turns \\ and \n in what it says into other characters.
log() {
  printf '%s\n' "$*"
  printf '%s\n' "$(date '+%F %T') [$BY] $*" >> "$LOG"
}

# Shown under the module's name in the Magisk app.
set_status() {
  sed -i "s|^description=.*|description=$1|" "$MODDIR/module.prop"
}

fail() {
  log "FAILED: $*"
  set_status "✘ $* (log: $LOG)"
  FAILED=$*
  exit 1
}

# What happened, for the app: the journal of events (its Logs tab) and its notifications (see
# notify.sh): event <kind> <level> <title> [text] [key].
event() { SERAPH_BY=$BY SERAPH_RUN=$RUN sh "$MODDIR/notify.sh" "$@"; }

# A run: one build, update, backup, ... with a log of its own ($DATA/logs/<run>.log: all it says),
# which its events point to. One that fails (fail, or a step that stops it) is told as "<title>
# failed", unless it told how it ended itself; one that told nothing (nothing was due) leaves no log.
# Arguments: its kind (as an event's) and title.
LOGS=$DATA/logs
RUN= RUN_LOG= RUN_KIND= RUN_TITLE= FAILED= AT_EXIT= RESUME_NAME=
run_open() {
  RUN_KIND=$1 RUN_TITLE=$2
  run_priority
  mkdir -p "$LOGS"
  RUN=$(date +%Y%m%d-%H%M%S)-$1-$$
  RUN_LOG=$LOGS/$RUN.log
  # The engine's events belong to it too.
  export SERAPH_RUN=$RUN SERAPH_KIND=$1 SERAPH_BY=$BY
  # The newest 60 runs' logs stay.
  ls -t "$LOGS"/*.log 2> /dev/null | tail -n +60 | while read -r old; do rm -f "$old"; done
  : > "$RUN_LOG"
  # Everything said from here on goes to its log too, through tee.
  pipe=$DATA/tmp/run-$$.pipe
  rm -f "$pipe"
  if mkfifo "$pipe" 2> /dev/null; then
    tee -a "$RUN_LOG" < "$pipe" &
    exec > "$pipe" 2>&1
    rm -f "$pipe"
  fi
  trap finish EXIT
}

# How the run ended, told: run_end <level> <title> [text].
run_end() { event "$RUN_KIND" "$@"; }

# Below the app on the screen, so the phone does not stutter while it runs: what the phone runs by
# itself (job, boot, resume) on Android's background cores, at the lowest priority and the idle share
# of the storage; what somebody waits for (the app, adb, ssh) on all cores but the biggest, at nice
# 10. What the run starts (the engine, Nix, restic, git) keeps it.
run_priority() {
  case "$BY" in
    job | boot | resume) set=background nice=19 io="-c 3" ;;
    *) set=foreground nice=10 io="-c 2 -n 7" ;;
  esac
  for c in "/dev/cpuset/$set/cgroup.procs" "/dev/cpuset/$set/tasks"; do
    [ -w "$c" ] && echo $$ > "$c" 2> /dev/null && break
  done
  [ -n "$BUSYBOX" ] && "$BUSYBOX" renice "$nice" -p $$ > /dev/null 2>&1
  /system/bin/ionice $io -p $$ > /dev/null 2>&1
  return 0
}

# A run that a restart (or a kill) breaks off starts again, from resume.sh: $PENDING/<name> says
# how (when it began, how often it was picked up, the test profile it had (SERAPH_PROFILE, so a
# test never goes on with the phone's own), then the script and its arguments, one a line),
# until the run ends, however it ends. One that cannot run now (status 75: its storage is not
# there, ...) stays, to run once it can. Arguments: its name, the script, and its arguments without
# who started it (when it starts again, that is "resume"). $PENDING/<name>.pid is the run's
# process while it runs: one of the same name is not started next to it (they would share files),
# and resume.sh leaves a record whose run still runs.
PENDING=$DATA/pending
resumable() {
  mkdir -p "$PENDING"
  if running=$(running_of "$1"); then
    fail "another one is running (process $running, started by $(sed -n 1p "$PENDING/$1.by" 2> /dev/null || echo '?')): wait for it to end"
  fi
  RESUME_NAME=$1
  shift
  echo $$ > "$PENDING/$RESUME_NAME.pid"
  echo "$BY" > "$PENDING/$RESUME_NAME.by"
  { date +%s; echo "${SERAPH_RESUMED:-0}"; echo "$SERAPH_PROFILE"; printf '%s\n' "$@"; } > "$PENDING/$RESUME_NAME"
}

# The process of the run of that name still running (a module script's sh), if there is one.
running_of() {
  pid=$(cat "$PENDING/$1.pid" 2> /dev/null)
  [ -n "$pid" ] && [ "$pid" != $$ ] && [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -qE '(build|remote|update|backup)\.sh' || return 1
  echo "$pid"
}

# A command for the end, whatever happens (a folder of secrets to remove).
at_exit() {
  AT_EXIT="$AT_EXIT
$1"
  trap finish EXIT
}

finish() {
  status=$?
  eval "$AT_EXIT"
  if [ -n "$RESUME_NAME" ]; then
    if [ "$status" = 75 ]; then
      # Waiting is no try: its count goes back.
      [ -n "$SERAPH_RESUMED" ] && sed -i "2s/.*/$((SERAPH_RESUMED - 1))/" "$PENDING/$RESUME_NAME"
    else
      rm -f "$PENDING/$RESUME_NAME"
    fi
    rm -f "$PENDING/$RESUME_NAME.pid" "$PENDING/$RESUME_NAME.by"
  fi
  [ -n "$RUN" ] || return 0
  told=$(grep -F "\"run\":\"$RUN\"" "$DATA/events" 2> /dev/null | grep -v '"level":"start"')
  # Why it stopped: fail's reason, else the last one its log has (the engine's ✘, a step's FAILED).
  why=${FAILED:-$(grep -E '^(✘|FAILED: )' "$RUN_LOG" 2> /dev/null | tail -n 1 | sed 's/^✘ *//; s/^FAILED: //')}
  if [ "$status" = 75 ] && [ -z "$told" ]; then
    run_end warn "$RUN_TITLE waits" "${why:-it cannot run now}; it starts again once it can"
  elif [ "$status" != 0 ] && [ -z "$told" ]; then
    run_end error "$RUN_TITLE failed" "${why:-it stopped (status $status)}"
  elif [ -z "$told" ] && ! grep -qF "\"run\":\"$RUN\"" "$DATA/events" 2> /dev/null; then
    rm -f "$RUN_LOG"
  fi
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

# The engine, from the app's APK, run as root; what it says is shown and logged (and goes to the
# run's log with the rest of what the script says).
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
    printf '%s\n' "$GEN_SOURCE" > "$generations/new/source"
    [ -n "$GEN_BUILT_ON" ] && printf '%s\n' "$GEN_BUILT_ON" > "$generations/new/built-on"
    [ -n "$GEN_LOCK" ] && [ -f "$GEN_LOCK" ] && cp "$GEN_LOCK" "$generations/new/flake.lock"
  elif [ "$last" -gt 0 ]; then
    for kept in source built-on flake.lock; do
      [ -f "$generations/$last/$kept" ] && cp "$generations/$last/$kept" "$generations/new/$kept"
    done
  fi
  # Android's diff (toybox's) says 0 for folders whose files differ: what it prints tells.
  if [ "$last" -gt 0 ] && [ -z "$(diff -r "$generations/new" "$generations/$last" 2>&1)" ]; then
    rm -rf "$generations/new"
    log "Generation $last (nothing changed)"
    GENERATION_TOLD="Built: generation $last, nothing changed"
  else
    mv "$generations/new" "$generations/$((last + 1))"
    echo "$((last + 1))" > "$generations/last"
    log "Generation $((last + 1))"
    GENERATION_TOLD="Built generation $((last + 1))"
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

# Nix's environment: its caches and settings in the module's data, and the tokens.
nix_env() {
  export HOME=$DATA/home TMPDIR=$DATA/tmp USER=root NIX_CONF_DIR=$DATA/home XDG_CACHE_HOME=$DATA/home/.cache
  nix_conf
  nix_tokens
}

# Nix with Seraph's store ($DATA/nix: the paths are /nix/store/... to Nix, and the
# services see them there), in the namespace with Seraph's /etc (nix_run).
nix_store() { nix_run "$MODDIR/bin/nix" --store "$DATA/nix" "$@"; }

# Nix in Seraph's view, where its store is /nix itself, as on NixOS: what it builds runs
# there (the small files a config makes need their store at /nix/store). Arguments: Nix's
# command (nix, nix-store), then its arguments, each a word (they go into a script).
nix_view() {
  [ -f "$DATA/units/view.sh" ] || engine etc > /dev/null || return 1
  tool=$1
  shift
  script=$DATA/tmp/nix-view.sh
  { printf 'exec %s' "'$MODDIR/bin/$tool'"; for word; do printf " '%s'" "$word"; done; printf '\n'; } > "$script"
  sh "$DATA/units/view.sh" "$script"
}

# The Nix packages a profile needs (its derivations and storePaths: the services'
# programs, the files the config makes for them, environment.systemPackages), into
# Seraph's store: fetched from cache.nixos.org, and the small files a config makes (a
# service's settings file, a script; their derivations are here, written by the phone's
# evaluation or sent by the PC that built the config) built here, from what the cache has;
# with dry, only said. A package the cache has not got (built from source: unfree, or
# changed by an overlay) is never built on the phone: it stops the build here, before
# anything changes.
fetch_packages() {
  paths=$(engine paths "$1" | grep '^/nix/store/') || paths=
  drvs=$(engine paths "$1" --derivations | grep '^/nix/store/') || drvs=
  [ -n "$paths$drvs" ] || return 0
  log "== Packages"
  nix_env
  nix_etc > /dev/null || { log "Nix could not get its /etc (the network's DNS servers): is the phone online?"; return 1; }
  # The derivations here; the others' outputs come from the cache by their paths.
  here=
  for drv in $drvs; do [ -f "$DATA/nix${drv%%^*}" ] && here="$here $drv"; done
  if [ -n "$here" ]; then
    nix_view nix build --dry-run --no-link $here 2> "$DATA/tmp/packages.plan" || { cat "$DATA/tmp/packages.plan" >&2; return 1; }
    cat "$DATA/tmp/packages.plan" >&2
    # What it would build: files made from the config, or a package (from its source) it must not.
    refused=
    for drv in $(sed -n '/will be built:$/,/^[^ ]/s/^ *\(\/nix\/store\/[^ ]*\.drv\)$/\1/p' "$DATA/tmp/packages.plan"); do
      file=$DATA/nix$drv
      if grep -qE '\("srcs?","' "$file" && ! grep -qF '("preferLocalBuild","1")' "$file"; then
        refused="$refused ${drv#/nix/store/*-}"
      fi
    done
    if [ -n "$refused" ]; then
      log "Not in cache.nixos.org, and packages the phone does not build (from their source):$refused"
      log "(An overlay or nixpkgs.config makes them so, or they are unfree: give one the cache has.)"
      return 1
    fi
    [ "$2" = dry ] || nix_view nix build --no-link $here || return 1
  fi
  [ -n "$paths" ] || return 0
  if [ "$2" = dry ]; then
    nix_store build --dry-run --no-link $paths
  else
    nix_store build --no-link $paths
  fi
}

# What a PC that built the config sent of Nix's store (incoming/paths.nar: the derivations
# of the files the config makes, with all they need, and the files it added): into Seraph's
# store, for fetch_packages to build.
import_paths() {
  [ -s "$1" ] || return 0
  log "== Files made from the config, from the PC"
  nix_env
  nix_view nix-store --import < "$1" > /dev/null
}

# The packages of the profile in use, kept from Nix's garbage collection (each a root
# in Seraph's store), as a NixOS system's are.
keep_packages() {
  roots=$DATA/nix/nix/var/nix/gcroots/seraph
  rm -rf "$roots" && mkdir -p "$roots"
  i=0
  for path in $(engine paths "$PROFILE" | grep '^/nix/store/'); do
    ln -s "$path" "$roots/$i" && i=$((i + 1))
  done
}

# The second half of every build, wherever the profile was built (build.sh on the
# phone, remote.sh for a PC): its plan and every check (all a dry run does), then its
# apps and settings, activated, and a new generation. When a check fails, nothing
# changes. Arguments: the profile, and what to do (switch, dry, update); SECRETS_IN
# is the folder of the secrets a PC sent with it.
switch_to() {
  fetch_packages "$1" "$2" || { rm -f "$1"; fail "the config's packages could not be fetched from cache.nixos.org (Nix says why above); nothing was changed"; }
  log "== Apps"
  sent=
  [ -n "$SECRETS_IN" ] && sent="--secrets=$SECRETS_IN"
  rm -f "$DATA/tmp/summary"
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
  # What the engine changed (or would change), in one line, for the run's end (see Apply.kt).
  summary=$(cat "$DATA/tmp/summary" 2> /dev/null)
  if [ "$2" = dry ]; then
    rm -f "$1"
    log "Dry run: nothing was changed"
    run_end info "Dry run: nothing was changed" "${summary:-It would change nothing}"
    return 0
  fi
  log "== Activating"
  mv -f "$1" "$PROFILE"
  keep_packages
  sh "$MODDIR/apply.sh" "$BY" || exit 1
  new_generation
  run_end ok "$GENERATION_TOLD" "${summary:-Nothing changed}"
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

# Tells the app something through its receiver (as notify.sh does for events): "schedule" makes
# sure its update check and backup check are scheduled.
tell_app() {
  am broadcast --user current -f 0x20 -n dev.seraph.manager/.EventsReceiver \
    -a dev.seraph.manager.action.EVENT --es event "$1" < /dev/null > /dev/null 2>&1
}

# The phone's copy of nixpkgs, as a NixOS channel: nixos-unstable (or the one $DATA/channel names),
# fetched the first time something needs it (a configuration.nix's Nix packages, git), and again
# with update. It is kept in Seraph's store, as a root of it: NIXPKGS is its path there.
NIXPKGS=
channel() {
  # (Names of its own: the scripts that call it have their own url and path.)
  channel_url=$(cat "$DATA/channel" 2> /dev/null)
  [ -n "$channel_url" ] || channel_url=https://channels.nixos.org/nixos-unstable/nixexprs.tar.xz
  channel_link=$DATA/channels/nixpkgs
  if [ ! -d "$channel_link/" ] || [ "$1" = update ]; then
    log "== nixpkgs ($channel_url)"
    nix_etc > /dev/null || { log "Nix could not get its /etc (the network's DNS servers): is the phone online?"; return 1; }
    channel_path=$(nix_run "$MODDIR/bin/nix" --store "$DATA/nix" eval --raw --impure --option tarball-ttl 0 \
      --expr "builtins.fetchTarball \"$channel_url\"") || return 1
    mkdir -p "$DATA/channels" "$DATA/nix/nix/var/nix/gcroots"
    ln -sfn "$channel_path" "$DATA/nix/nix/var/nix/gcroots/seraph-channel"
    ln -sfn "$DATA/nix$channel_path" "$channel_link"
  fi
  NIXPKGS=$(readlink -f "$channel_link")
}
