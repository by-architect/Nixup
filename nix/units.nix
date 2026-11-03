# Services as NixOS writes them, systemd.services.<name>, which Seraph's engine runs on the phone the
# way systemd runs them on NixOS: each unit's commands (serviceConfig.ExecStart, or script) as root,
# with its environment and PATH; its state in /var/lib/<StateDirectory>, kept across restarts and
# builds; started again when it stops (Restart); in order (after, before, wants, requires); and with
# each build and at boot when it is wantedBy multi-user.target. Its programs are Nix packages
# (${pkgs.foo}/bin/foo), which the phone fetches from cache.nixos.org and runs in a view of the
# phone where /nix exists, as are Seraph's own services (services.tailscale, services.syncthing).
#
# In that view the phone is as it is (/system, /data, /storage, /dev, ...), with /nix (Seraph's
# store), Seraph's /etc (the network's DNS servers, Android's certificates), /var/lib and /run (the
# units' state, which stays) and a /tmp of the unit's own. Android's commands are on PATH too.
#
# The phone has no systemd: a unit can say what the engine does (the options below, and these of
# serviceConfig: ExecStart, ExecStartPre, ExecStartPost, ExecStop, Type, Restart, RestartSec,
# WorkingDirectory, StateDirectory, RuntimeDirectory, RemainAfterExit, Environment, Nice). Anything
# else stops the build and says so, as NixOS's options the phone has not got do.
#
# On a phone the app on the screen comes first: units run on Android's background cores (its
# system-background cpuset), at Nice 10 unless serviceConfig.Nice says otherwise, with a low share
# of the storage's time, so a busy service does not make the screen stutter.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;

  # What serviceConfig takes, as systemd's unit files do: a value or a list of them.
  unitValue = let one = types.oneOf [ types.str types.int types.bool types.float types.path types.package ]; in
    types.either one (types.listOf one);

  supported = [
    "ExecStart" "ExecStartPre" "ExecStartPost" "ExecStop" "Type" "Restart" "RestartSec"
    "WorkingDirectory" "StateDirectory" "RuntimeDirectory" "RemainAfterExit" "Environment" "Nice"
  ];
  types' = [ "simple" "exec" "oneshot" ];
  restarts = [ "no" "on-failure" "always" ];

  unitModule = { name, ... }: {
    options = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether the unit is there at all (false: as if it were not in the config).";
      };
      description = mkOption {
        type = types.str;
        default = "";
        description = "What the unit is, in a few words.";
      };
      script = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands the unit runs (bash, stopping at the first that fails), as its ExecStart.";
      };
      preStart = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands run before it starts (each time it does), as an ExecStartPre.";
      };
      postStart = mkOption {
        type = types.lines;
        default = "";
        description = "Shell commands run once it has started, as an ExecStartPost.";
      };
      environment = mkOption {
        type = types.attrsOf (types.nullOr (types.oneOf [ types.str types.path types.package ]));
        default = { };
        example = { HOME = "/var/lib/foo"; };
        description = "Its environment variables (null: left out).";
      };
      path = mkOption {
        type = types.listOf (types.oneOf [ types.package types.str ]);
        default = [ ];
        example = lib.literalExpression "[ pkgs.jq ]";
        description = ''
          Packages whose bin (and sbin) folders are on its PATH, before coreutils, findutils, gnugrep
          and gnused (as on NixOS) and Android's /system/bin.
        '';
      };
      wantedBy = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "multi-user.target" ];
        description = ''
          [ "multi-user.target" ]: started with each build and at boot. Without it a unit only starts
          when another one that starts wants or requires it, as on NixOS.
        '';
      };
      after = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "foo.service" ];
        description = "Units it starts after, when they start too.";
      };
      before = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units it starts before, when they start too.";
      };
      wants = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started with it.";
      };
      requires = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Units started with it, before it; when one of them cannot start, neither does it.";
      };
      restartTriggers = mkOption {
        type = types.listOf types.unspecified;
        default = [ ];
        description = "Values whose change restarts it with the next build, as on NixOS.";
      };
      notifications = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Seraph's own (NixOS has no such thing): whether the app tells, in a notification, when it
          stops by itself, fails, or runs again after a failure (the Logs tab has it either way).
          Off for what is about the phone's security (sshd), so its state shows on no screen.
        '';
      };
      serviceConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        example = { ExecStart = "\${pkgs.foo}/bin/foo --flag"; Restart = "on-failure"; StateDirectory = "foo"; };
        description = "The [Service] section of its unit (the keys the phone takes are listed at the top of nix/units.nix).";
      };
      # NixOS's that the phone has not got: there, so a unit that sets them is told so.
      unitConfig = mkOption {
        type = types.attrsOf unitValue;
        default = { };
        description = "The [Unit] section's other keys: not on the phone yet.";
      };
      startAt = mkOption {
        type = types.either types.str (types.listOf types.str);
        default = [ ];
        description = "When to start it, as a timer: not on the phone yet.";
      };
      preStop = mkOption {
        type = types.lines;
        default = "";
        description = "Commands run before it stops: not on the phone yet (serviceConfig.ExecStop is).";
      };
    };
  };

  units = lib.filterAttrs (_: unit: unit.enable) config.systemd.services;

  # A unit's name as the others give it: foo.service is foo.
  nameOf = lib.removeSuffix ".service";
  listOf = value: if value == null then [ ] else if builtins.isList value then value else [ value ];
  strings = value: map toString (listOf value);
  # StateDirectory = "a b" names two, as systemd takes it.
  words = value: lib.concatMap (lib.splitString " ") (strings value);

  defaultPath = with pkgs; [ coreutils findutils gnugrep gnused ];
  pathOf = unit: let all = unit.path ++ defaultPath; in
    "${lib.makeBinPath all}:${lib.makeSearchPathOutput "bin" "sbin" all}:/system/bin";

  commands = value: map (command: { inherit command; }) (strings value);
  script = text: lib.optional (text != "") { script = text; };

  # serviceConfig.Environment, "NAME=value" each, as systemd takes it.
  environmentLines = lines: lib.listToAttrs (map (line:
    let parts = builtins.match "([^=]+)=(.*)" line;
    in lib.nameValuePair (builtins.elemAt parts 0) (builtins.elemAt parts 1)
  ) (strings lines));

  # A unit as the engine runs it: its steps (each a command line, as systemd takes it, or a script),
  # with everything resolved to strings.
  toProfile = name: unit:
    let sc = unit.serviceConfig; in
    {
      inherit (unit) description;
      type = if (sc.Type or "simple") == "exec" then "simple" else sc.Type or "simple";
      restart = sc.Restart or "no";
      restartSec = toString (sc.RestartSec or "0.1");
      workingDirectory = if sc ? WorkingDirectory then toString sc.WorkingDirectory else null;
      stateDirectories = words (sc.StateDirectory or [ ]);
      runtimeDirectories = words (sc.RuntimeDirectory or [ ]);
      environment = environmentLines (sc.Environment or [ ])
        // lib.mapAttrs (_: toString) (lib.filterAttrs (_: value: value != null) unit.environment)
        // { PATH = pathOf unit; };
      after = map nameOf (unit.after ++ unit.requires);
      before = map nameOf unit.before;
      wants = map nameOf (unit.wants ++ unit.requires);
      requires = map nameOf unit.requires;
      wanted = lib.elem "multi-user.target" unit.wantedBy;
      # Below the apps, unless the unit says (systemd's Nice: -20, first, to 19, last).
      nice = sc.Nice or 10;
      inherit (unit) notifications;
      shell = "${pkgs.bash}/bin/bash";
      pre = commands (sc.ExecStartPre or [ ]) ++ script unit.preStart;
      start = if unit.script != "" then script unit.script else commands (sc.ExecStart or [ ]);
      post = commands (sc.ExecStartPost or [ ]) ++ script unit.postStart;
      stop = commands (sc.ExecStop or [ ]);
      # A change of these restarts it; the engine compares the units as they are.
      triggers = builtins.hashString "sha256" (builtins.toJSON (map toString unit.restartTriggers));
    };

  profile = lib.mapAttrs toProfile units;

  # The store paths the units use, as Nix finds a build's references: by their names in the text.
  storePathsIn = text: map builtins.head (lib.filter builtins.isList
    (builtins.split "(/nix/store/[a-z0-9]{32}-[A-Za-z0-9+._?=-]+)" (builtins.unsafeDiscardStringContext text)));

  problems = lib.concatLists (lib.mapAttrsToList (name: unit:
    let
      sc = unit.serviceConfig;
      type = sc.Type or "simple";
      starts = if unit.script != "" then 1 else builtins.length (listOf (sc.ExecStart or [ ]));
      other = lib.filter (key: !lib.elem key supported) (lib.attrNames sc);
      unit' = "systemd.services.${name}";
    in
    lib.optional (builtins.match "[A-Za-z0-9_.:-]+" name == null) "${unit'}: a unit's name is letters, digits and _ . : -."
    ++ lib.optional (other != [ ]) "${unit'}.serviceConfig: ${lib.concatStringsSep ", " other} ${if builtins.length other == 1 then "is" else "are"} not on the phone (units run as root, with what nix/units.nix lists)."
    ++ lib.optional (unit.script != "" && sc ? ExecStart) "${unit'}: give script or serviceConfig.ExecStart, not both."
    ++ lib.optional (!lib.elem type types') "${unit'}.serviceConfig.Type: \"${toString type}\" is not on the phone (simple, exec or oneshot)."
    ++ lib.optional (type != "oneshot" && starts != 1) "${unit'}: a ${toString type} unit has one ExecStart (or script)${lib.optionalString (starts > 1) "; several are a oneshot's"}."
    ++ lib.optional (type == "oneshot" && starts == 0) "${unit'}: give it something to run (serviceConfig.ExecStart or script)."
    ++ lib.optional (!lib.elem (sc.Restart or "no") restarts) "${unit'}.serviceConfig.Restart: \"${toString (sc.Restart or "")}\" is not on the phone (no, on-failure or always)."
    ++ lib.optional (sc ? Nice && !(builtins.isInt sc.Nice && sc.Nice >= -20 && sc.Nice <= 19)) "${unit'}.serviceConfig.Nice: a number from -20 to 19."
    ++ lib.optional (unit.unitConfig != { }) "${unit'}.unitConfig: not on the phone yet."
    ++ lib.optional (unit.startAt != [ ] && unit.startAt != "") "${unit'}.startAt: timers are not on the phone yet."
    ++ lib.optional (unit.preStop != "") "${unit'}.preStop: not on the phone yet; serviceConfig.ExecStop is."
    ++ lib.optional (lib.any (target: target != "multi-user.target") unit.wantedBy)
      "${unit'}.wantedBy: only multi-user.target is on the phone (started with each build and at boot)."
  ) units);
in
{
  options.systemd.services = mkOption {
    type = types.attrsOf (types.submodule unitModule);
    default = { };
    example = lib.literalExpression ''
      {
        hello = {
          wantedBy = [ "multi-user.target" ];
          serviceConfig = {
            ExecStart = "''${pkgs.python3}/bin/python3 -m http.server 8000";
            WorkingDirectory = "/storage/emulated/0/Download";
            Restart = "on-failure";
          };
        };
      }
    '';
    description = ''
      Services, as NixOS's systemd.services: programs root keeps running on the phone, from Nix
      packages (see nix/units.nix for what a unit can say there).
    '';
  };

  config = {
    system.build.units = profile;
    system.build.unitStorePaths = lib.unique (storePathsIn (builtins.toJSON profile));
    assertions = map (message: { assertion = false; inherit message; }) problems;
  };
}
