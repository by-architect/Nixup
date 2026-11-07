# What nixup-rebuild asks of the phone when a PC builds its config, as nixos-rebuild
# --target-host does for NixOS: first the indexes the config's package names come from,
# then the profile built there, applied as a build on the phone applies its own, with the
# secrets sent along. What the PC sends is in $DATA/incoming, which root alone can read,
# and which goes once the profile is applied (or not).
# Runs as root. Arguments: who started it (adb, ssh), then what to do:
#   index [update]          brings the indexes of the repositories in incoming/repos.json up
#                           to date (update: fetched again), so the PC can name their apps
#                           (it reads repos/<repo>/tree.json then)
#   switch | dry | update   applies incoming/profile.json with the secrets in
#                           incoming/secrets (dry: plans and checks only, and changes
#                           nothing; update: the releases asked for again too);
#                           incoming/source says what it was built from, incoming/built-on
#                           on which machine, and incoming/flake.lock is the flake's lock,
#                           all three for the generation; incoming/paths.nar.gz (or
#                           paths.nar) is what it sent of Nix's store; with --specialisation
#                           <name>, the build's specialisation of that name instead of the
#                           config itself (see build.sh)
MODDIR=${0%/*}
SPECIALISATION= rest=
while [ $# -gt 0 ]; do
  case "$1" in
    --specialisation) SPECIALISATION=$2; shift; [ $# -gt 0 ] && shift ;;
    --specialisation=*) SPECIALISATION=${1#*=}; shift ;;
    *) rest="$rest $1"; shift ;;
  esac
done
set -- $rest
BY=${1:-manual}
MODE=${2:-}
. "$MODDIR/common.sh"
case "$SPECIALISATION" in
  *[!A-Za-z0-9_.-]* | [!A-Za-z0-9]*) fail "--specialisation: \"$SPECIALISATION\" is not a specialisation's name (letters, digits and _ . -)" ;;
esac
IN=$DATA/incoming

case "$MODE" in
  index)
    [ -f "$IN/repos.json" ] || fail "remote.sh index: nothing was sent ($IN/repos.json)"
    log "== Repositories (for a build on a PC)"
    if [ "$3" = update ]; then engine index --force "$IN/repos.json"; else engine index "$IN/repos.json"; fi \
      || log "Building with the indexes there are (a repository without one: its apps cannot be named)"
    exit 0 ;;
  switch | dry | update) ;;
  *) fail "remote.sh: no such thing to do: $MODE (index, switch, dry, update)" ;;
esac

# What was sent goes, whatever happens: the secrets in it too.
at_exit 'rm -rf "$IN"'
[ -f "$IN/profile.json" ] || fail "remote.sh: no profile was sent ($IN/profile.json)"
GEN_SOURCE=$(head -n 1 "$IN/source" 2> /dev/null)
GEN_BUILT_ON=$(head -n 1 "$IN/built-on" 2> /dev/null)
[ -f "$IN/flake.lock" ] && GEN_LOCK=$IN/flake.lock
# The configuration.nix it was built from (system.copySystemConfiguration), for the generation.
[ -f "$IN/configuration.nix" ] && GEN_CONFIG=$IN/configuration.nix
[ -n "$GEN_SOURCE" ] || GEN_SOURCE="a build on ${GEN_BUILT_ON:-a PC}"
run_open build "Build from ${GEN_BUILT_ON:-a PC}"
# What was sent stays until it is applied: a restart in the middle applies it after (resume.sh).
[ "$MODE" = dry ] || resumable build remote.sh "$MODE" ${SPECIALISATION:+--specialisation "$SPECIALISATION"}
if [ "$MODE" = dry ]; then event build start "Dry run of a build from ${GEN_BUILT_ON:-a PC}" "$GEN_SOURCE"
else event build start "Applying a build from ${GEN_BUILT_ON:-a PC}" "$GEN_SOURCE"; fi
log "== Built on ${GEN_BUILT_ON:-a PC}: $GEN_SOURCE"
log_file "Profile: " "$IN/profile.json"
SECRETS_IN=$IN/secrets
# The derivations of the files the config makes, which this phone builds, and the config's
# nixpkgs when the phone had not got it (common.sh); compressed by a newer nixup-rebuild.
for nar in "$IN/paths.nar.gz" "$IN/paths.nar"; do
  import_paths "$nar" || fail "what the PC sent of Nix's store could not be taken in (Nix says why above); nothing was changed"
done
# Its own (see build.sh), until switch_to makes it the phone's.
NEW=$PROFILE.new-$$
at_exit 'rm -f "$NEW"'
cp "$IN/profile.json" "$NEW"
switch_to "$NEW" "$MODE"
