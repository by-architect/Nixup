# Scripts, as NixOS runs them: system.activationScripts, system.userActivationScripts and system.preSwitchChecks, with
# NixOS's names, types and meanings, so a module (one shared with NixOS, or one of nixpkgs') that has some builds here.
#
#   system.activationScripts       run as root with each build (after the users, /etc and the folders of
#                                  systemd.tmpfiles are in place, before the services start or start again) and at each
#                                  start of the phone (before the services start), in the order their deps say; with a
#                                  dry run only those with supportsDryActivation, with NIXOS_ACTION=dry-activate (a
#                                  build's is switch). One that fails is told and the others still run, as on NixOS, and
#                                  the build ends with an error.
#   system.userActivationScripts   run by each normal user (as that user) when they change and at each start of the
#                                  phone: a unit of users' own (systemd.user.services.nixos-activation), as on NixOS
#                                  (whose switch runs it again each time; a phone's build runs a done unit again only
#                                  when it changed).
#   system.preSwitchChecks         run before a build changes anything, a dry run's too, with the profile and the action
#                                  (switch, dry-activate) as $1 and $2: one that fails stops the build, and nothing
#                                  changes. NIXOS_NO_CHECK=1 leaves them out, as on NixOS.
#
# They run in Nixup's view of the phone (as its services do: nix/units.nix), with bash, and coreutils, findutils, grep
# and sed on PATH, then Android's commands (NixOS's have glibc's getent, shadow and util-linux too: give a script its
# own, ${pkgs.foo}/bin/foo, as on NixOS). $systemConfig (NixOS's system) is not there.
#
# Nixup's own (NixOS has no such thing): an activation script's undo, run once when the script leaves the config and
# when the module goes, so that what the script did is put back, as with every other option.
#
# NixOS's own scripts (users, groups, etc, specialfs, binsh, ...) are what Nixup does itself before the scripts run, or
# what a phone has not got: their names are there, so that a script can come after them (deps = [ "users" ];), and
# whatever a module gives them never runs.
{ config, lib, options, pkgs, _nixup, ... }:
let
  inherit (lib) mkOption types;

  builtinSnippets = [ "stdio" "specialfs" "var" "users" "groups" "etc" "hashes" "wrappers" "binsh" "usrbinenv" "modprobe"
    "no-nix-channel" ];

  # As NixOS takes each script: its text, or the text with what it comes after (and, for a system one, whether a dry
  # run runs it too, and Nixup's undo).
  scriptType = system: types.either types.str (types.submodule {
    options = {
      deps = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "List of dependencies. The script will run after these.";
      };
      text = mkOption {
        type = types.lines;
        description = "The content of the script.";
      };
    } // lib.optionalAttrs system {
      supportsDryActivation = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Whether this activation script supports being dry-activated: a dry run runs it too, with the environment
          variable NIXOS_ACTION set to dry-activate, when it must change nothing.
        '';
      };
      undo = mkOption {
        type = types.lines;
        default = "";
        example = "rm -f /data/local/tmp/greeting";
        description = ''
          Nixup's own (NixOS has no such thing): what puts back what the script did, run once when the script leaves
          the config, and when the module goes (as root, in Nixup's view, as the script runs).
        '';
      };
    };
  });

  normalize = lib.mapAttrs (_: script:
    if builtins.isString script then lib.noDepEntry script // { supportsDryActivation = false; undo = ""; }
    else { supportsDryActivation = false; undo = ""; } // script);

  addAttributeName = lib.mapAttrs (name: script: script // lib.optionalAttrs (script.text != "") {
    text = ''
      #### Activation script snippet ${name}:
      _localstatus=0
      ${script.text}
      if (( _localstatus > 0 )); then
        printf "Activation script snippet '%s' failed (%s)\n" "${name}" "$_localstatus"
      fi
    '';
  });

  # The commands a script has, as NixOS's have them (those the phone has of them), then Android's.
  path = with pkgs; map lib.getBin [ coreutils findutils gnugrep gnused ];
  preamble = ''
    warn() {
      printf "\033[1;35mwarning:\033[0m %s\n" "$*" >&2
    }
    export PATH=/empty
    for i in ${toString path}; do
      PATH=$PATH:$i/bin:$i/sbin
    done
    PATH=$PATH:/system/bin
    umask 0022
  '';

  # As NixOS's activation script: the snippets in their deps' order, each told when it fails; with onlyDry, those that
  # support a dry run (the others a comment). NixOS's own never run (see the top).
  activationScript = set: onlyDry:
    let
      withHeadlines = addAttributeName (lib.mapAttrs (name: script:
        if lib.elem name builtinSnippets then script // { text = ""; } else script) (normalize set));
      withDrySnippets = lib.mapAttrs (name: script:
        if onlyDry && !script.supportsDryActivation && script.text != "" then
          script // { text = "#### Activation script snippet ${name} does not support dry activation."; }
        else script) withHeadlines;
    in
    ''
      ${preamble}
      _status=0
      trap "_status=1 _localstatus=\$?" ERR

      ${lib.concatStringsSep "\n" (lib.filter (text: text != "")
        (lib.textClosureList withDrySnippets (lib.attrNames (lib.filterAttrs (_: script: script.text != "") withDrySnippets))))}

      exit $_status
    '';

  # The config's own scripts (and modules'), without NixOS's: their names alone (for a first look without nixpkgs,
  # which reads no text), and with their texts.
  system = removeAttrs config.system.activationScripts [ "script" ];
  ownNames = lib.filter (name: !lib.elem name builtinSnippets) (lib.attrNames system);
  own = lib.filterAttrs (name: script: lib.elem name ownNames && script.text != "") (normalize system);
  # The users' scripts, as their definitions name them (NixOS's apply keeps only the whole script).
  userNames = lib.attrNames (lib.foldl' (all: one: all // one) { } options.system.userActivationScripts.definitions);
  checks = config.system.preSwitchChecks;

  # Nixup's undo of a script, as a whole script.
  undoScript = name: text: ''
    set -e
    ${preamble}
    #### Undo of activation script ${name}:
    ${text}
  '';

  # NixOS's pre-switch checks (its pre-switch-check.nix), each in a subshell with errexit.
  checksScript = ''
    set -o errexit -o nounset -o pipefail
    ${preamble}
    ${lib.concatLines (lib.mapAttrsToList (name: text: ''
      # pre-switch check ${name}
      #
      # Run with errexit in a subshell that is not part of an `if`/`||`
      # condition, so that `set -e` is actually honoured inside the
      # check body.
      set +e
      (
        set -e
        ${text}
      ) >&2
      _rc=$?
      set -e
      if [ "$_rc" -ne 0 ]; then
        echo "Pre-switch check '${name}' failed" >&2
        exit 1
      fi
    '') checks)}
  '';
in
{
  options.system = {
    activationScripts = mkOption {
      type = types.attrsOf (scriptType true);
      default = { };
      example = lib.literalExpression ''
        {
          greeting = {
            text = "echo hello > /data/local/tmp/greeting";
            undo = "rm -f /data/local/tmp/greeting";
          };
        }
      '';
      description = ''
        Shell script fragments each build runs as root, and each start of the phone, as NixOS's
        system.activationScripts: after the users, /etc and systemd.tmpfiles' folders are in place, before the services
        start; in Nixup's view of the phone, with bash. Since they run every time, they must be idempotent and fast.
        Nixup's own undo puts back what one did when it leaves the config, and when the module goes.
      '';
      # As on NixOS: the whole script is there too, as script.
      apply = set: set // { script = activationScript set false; };
    };

    userActivationScripts = mkOption {
      type = types.attrsOf (scriptType false);
      default = { };
      example = lib.literalExpression ''{ hello = "mkdir -p ~/notes"; }'';
      description = ''
        Shell script fragments each normal user runs, as that user, when they change and at each start of the phone,
        as NixOS's system.userActivationScripts: a unit of the users' own (nixos-activation; NixOS runs it with every
        switch). They must be idempotent and fast.
      '';
      # As on NixOS: only the whole script.
      apply = set: {
        script = ''
          export PATH=
          for i in ${toString path}; do
            PATH=$PATH:$i/bin:$i/sbin
          done
          PATH=$PATH:/system/bin

          _status=0
          trap "_status=1 _localstatus=\$?" ERR

          ${lib.textClosureMap lib.id (addAttributeName (lib.mapAttrs (_: script:
            if builtins.isString script then lib.noDepEntry script else script) set)) (lib.attrNames set)}

          exit $_status
        '';
      };
    };

    preSwitchChecks = mkOption {
      type = types.attrsOf types.str;
      default = { };
      example = lib.literalExpression ''{ battery = "[ \"$(cat /sys/class/power_supply/battery/capacity)\" -ge 20 ]"; }'';
      description = ''
        Shell script fragments each build runs before it changes anything (a dry run's too), as NixOS's
        system.preSwitchChecks: one that fails stops the build, and nothing changes. They get the profile and the action
        (switch, dry-activate) as $1 and $2. NIXOS_NO_CHECK=1 leaves them out, as on NixOS.
      '';
    };
  };

  config = {
    # NixOS's own scripts' names, for other scripts' deps (see the top): a module's text for one never runs.
    system.activationScripts = lib.genAttrs builtinSnippets (_: lib.mkDefault "");

    # As on NixOS: the users' scripts in a unit of their own, which each normal user's systemd runs (NixOS's has
    # ConditionUser = "!@system": the phone runs users' units for its normal users only). Only with nixpkgs: a
    # configuration.nix's first look has none (its script needs coreutils), and asks for it (activationUsed).
    systemd.user.services.nixos-activation = lib.mkIf (userNames != [ ] && _nixup.nixpkgs != null) {
      description = "Run user-specific NixOS activation";
      script = config.system.userActivationScripts.script;
      serviceConfig.Type = "oneshot";
      wantedBy = [ "default.target" ];
    };

    system.build = {
      # For a configuration.nix's first look: whether it has scripts (so it needs nixpkgs, for bash). Names alone.
      activationUsed = ownNames != [ ] || userNames != [ ] || checks != { };

      # What the engine runs (Activation.kt): the activation script (and a dry run's, when a script supports one),
      # each script's undo, and the pre-switch checks, with the bash they run with; null without any.
      activation = if own == { } && checks == { } then null else {
        bash = "${pkgs.bash}/bin/bash";
        # The config's scripts, by name: one whose undo the phone keeps and that is not here left the config.
        names = lib.attrNames own;
        script = if own == { } then null else config.system.activationScripts.script;
        dryScript = if lib.any (script: script.supportsDryActivation) (lib.attrValues own) then activationScript system true else null;
        undo = lib.mapAttrs (name: script: undoScript name script.undo) (lib.filterAttrs (_: script: script.undo != "") own);
        preSwitchChecks = if checks == { } then null else checksScript;
      };
    };
  };
}
