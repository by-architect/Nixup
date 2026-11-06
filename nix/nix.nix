# Nix itself on the phone, as NixOS's nix.* options have it: every shell of Nixup's view (the app's terminal, an ssh
# session, su -c nixup-shell) has Nix's commands, with Nixup's store as theirs (/nix there), as on any Linux with Nix:
#   nix shell nixpkgs#htop            a shell with htop on PATH (nix run nixpkgs#cowsay -- hi runs one program)
#   nix profile add nixpkgs#htop      installed for the user, on PATH from then on (or: nix-env -iA nixpkgs.htop)
#   nix-shell -p htop                 a shell of nixpkgs' with htop (it fetches nixpkgs' build tools first: big)
#   nix-collect-garbage -d            frees up space (Nixup's own packages stay)
# nixpkgs there is the config's own (nixpkgs.flake.source), as on a NixOS built from a flake: the registry's nixpkgs
# and NIX_PATH's <nixpkgs> are it, so nothing fetches nixpkgs again and the packages are those the config has. The phone
# keeps it (a root of Nixup's store), and a build on a PC sends it along when the phone has not got it yet.
#
# The commands are the module's own Nix (the one Nixup builds with: nothing to download), linked into /etc/nixup/bin
# by the engine; with nix.package set, that Nix (from nixpkgs) comes first, as on NixOS, and the daemon, nix.gc and
# nix.optimise run it. Builds run as the nixbld users (nix.nrBuildUsers, with a uid from 400 to 999 each, which Android
# lets use the network), and without Nix's sandbox (sandbox = false): Android's kernels lack the namespaces it needs.
# Root uses the store itself; for the other users that log in (isNormalUser) Nix's daemon runs, as on NixOS.
#
# Build machines (nix.buildMachines, with nix.distributedBuilds), as on NixOS: a package cache.nixos.org has not got (an
# overlay's, an unfree one) is built on those machines over ssh and copied to the phone, where a build otherwise stops at
# it (the phone builds no package from its source), and the shells' builds go to them too (/etc/nix/machines). Nix's ssh
# is nixpkgs' openssh, fetched with the packages; a machine's sshKey is a file on the phone, readable by root (in
# /data/adb/nixup/secrets, say), and its publicHostKey (or root's known_hosts, /data/adb/nixup/home/.ssh) is how the
# phone knows it. The files a config makes are still built on the phone.
{ config, options, lib, pkgs, _nixup, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.nix;
  flake = config.nixpkgs.flake;

  # The store path a path is in, as Nixup's view has it (/nix/store/<hash>-<name>), also from where that is outside of
  # the view (/data/adb/nixup/nix/nix/store/…, where the phone reads a configuration.nix's nixpkgs); null for a path in
  # no store (a nixpkgs on NIX_PATH as a channel's link).
  storePathOf = path:
    let found = builtins.match ".*(/nix/store/[a-z0-9]{32}-[^/]+).*" (builtins.unsafeDiscardStringContext (toString path));
    in if found == null then null else builtins.head found;
  configNixpkgs = if _nixup.nixpkgs == null then null else storePathOf (_nixup.nixpkgs.outPath or _nixup.nixpkgs);
  # (The phone keeps it as a kept path, which a PC sends once; not as a store path a file names.)
  noContext = path: builtins.unsafeDiscardStringContext (toString path);

  # nix.package set: that Nix everywhere; else the module's own, in /etc/nixup/bin.
  packageSet = options.nix.package.highestPrio < (lib.mkOptionDefault null).priority;
  nixBin = if packageSet then "${cfg.package.out}/bin" else "/etc/nixup/bin";

  # NixOS's types for nix.conf's values (nixos/modules/config/nix.nix).
  confAtom = types.nullOr (types.oneOf [ types.bool types.int types.float types.str types.path types.package ]) // {
    description = "Nix config atom (null, bool, int, float, str, path or package)";
  };
  semanticConfType = types.attrsOf (types.either confAtom (types.listOf confAtom));

  # nix.conf as NixOS writes it (pkgs.formats.nixConf): a list's values with spaces between, and each extra-* after
  # the setting it adds to.
  valueText = value:
    if value == null then ""
    else if builtins.isBool value then lib.boolToString value
    else if builtins.isFloat value then lib.strings.floatToString value
    else if builtins.isList value then lib.concatMapStringsSep " " valueText value
    else toString value;
  settingLines = settings: lib.concatStrings (lib.mapAttrsToList (key: value: "${lib.escape [ "=" ] key} = ${valueText value}\n") settings);
  isExtra = lib.hasPrefix "extra-";
  nixConf = "# Nix's settings in Nixup's view of the phone: the config's nix.settings and nix.extraOptions (nix/nix.nix).\n"
    + settingLines (lib.filterAttrs (key: _: !isExtra key) cfg.settings)
    + settingLines (lib.filterAttrs (key: _: isExtra key) cfg.settings)
    + cfg.extraOptions;

  # The system's flake registry, as NixOS writes it. Its flakes in the store go to the phone as kept paths (below),
  # not as the store paths a file names, which every build from a PC would send again.
  registryJson = builtins.unsafeDiscardStringContext (builtins.toJSON {
    version = 2;
    flakes = lib.mapAttrsToList (_: entry: { inherit (entry) from to exact; }) cfg.registry;
  });
  kept = lib.unique (lib.filter (path: path != null) (lib.mapAttrsToList (_: entry:
    if (entry.to.type or "") == "path" && entry.to ? path then storePathOf entry.to.path else null) cfg.registry));

  commands = [ "nix" "nix-build" "nix-collect-garbage" "nix-copy-closure" "nix-daemon" "nix-env" "nix-hash"
    "nix-instantiate" "nix-prefetch-url" "nix-shell" "nix-store" ] ++ lib.optional cfg.channel.enable "nix-channel";

  # The build users, as NixOS makes them, with a uid each from 400 to 999 (users.nix gives them: NixOS's own, 30001 and
  # up, Android keeps off the network). inet: Android lets a process open a network socket only with it (a build that
  # downloads its source).
  nixbldUsers = lib.listToAttrs (map (n: lib.nameValuePair "nixbld${toString n}" {
    description = "Nix build user ${toString n}";
    isSystemUser = true;
    group = "nixbld";
    extraGroups = [ "nixbld" "inet" ];
  }) (lib.range 1 cfg.nrBuildUsers));

  # The users besides root that log in, for whom Nix's daemon runs (root uses the store itself, as on NixOS).
  loginUsers = lib.filter (user: user.name != "root" && (user.isNormalUser || user.useDefaultShell)) (lib.attrValues config.users.users);

  # nix-env's own folder, which nix-env makes the first time it runs: XDG's with use-xdg-base-directories.
  defexpr = if cfg.settings.use-xdg-base-directories or false then "\${XDG_STATE_HOME:-$HOME/.local/state}/nix/defexpr" else "$HOME/.nix-defexpr";
  nixpkgsOnPath = lib.any (entry: lib.hasPrefix "nixpkgs=" entry) cfg.nixPath;

  # A line of Nix's machines file, as NixOS writes /etc/nix/machines (nixos/modules/config/nix-remote-build.nix).
  machineLine = machine: lib.concatStringsSep " " [
    "${lib.optionalString (machine.protocol != null) "${machine.protocol}://"}${lib.optionalString (machine.sshUser != null) "${machine.sshUser}@"}${machine.hostName}"
    (if machine.system != null then machine.system else if machine.systems != [ ] then lib.concatStringsSep "," machine.systems else "-")
    (if machine.sshKey != null then machine.sshKey else "-")
    (toString machine.maxJobs)
    (toString machine.speedFactor)
    (let all = machine.supportedFeatures ++ machine.mandatoryFeatures; in if all == [ ] then "-" else lib.concatStringsSep "," all)
    (if machine.mandatoryFeatures == [ ] then "-" else lib.concatStringsSep "," machine.mandatoryFeatures)
    (if machine.publicHostKey != null then machine.publicHostKey else "-")
  ];
  machinesText = lib.concatMapStrings (machine: machineLine machine + "\n") cfg.buildMachines;
  remoteBuilds = cfg.enable && cfg.distributedBuilds && cfg.buildMachines != [ ];
  badMachines = map (machine: machine.hostName) (lib.filter (machine: machine.system == null && machine.systems == [ ]) cfg.buildMachines);
  badKeys = map (machine: machine.hostName) (lib.filter (machine: machine.sshKey != null
    && (!lib.hasPrefix "/" machine.sshKey || lib.hasPrefix builtins.storeDir machine.sshKey)) cfg.buildMachines);

  # Taken from NixOS: an option the phone does nothing with.
  taken = type: default: what: mkOption {
    inherit type default;
    description = "As on NixOS: ${what}. Taken; on the phone it does nothing.";
  };
in
{
  options = {
    nix = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Whether Nix is on the phone's shells, as on NixOS: its commands (nix, nix-shell, nix-env, nix-build, ...) in
          the app's terminal, ssh sessions and nixup-shell, its settings (/etc/nix/nix.conf), the registry and
          NIX_PATH, its daemon for the users that log in, and its build users. Nixup's own builds run Nix either way.
        '';
      };

      package = mkOption {
        type = types.package;
        default = pkgs.nix;
        defaultText = lib.literalExpression "pkgs.nix";
        description = ''
          The Nix of the shells, the daemon, nix.gc and nix.optimise, as on NixOS. Not set, they run the module's own
          Nix (the one Nixup builds with), so nothing is downloaded for it; a module that reads it gets pkgs.nix.
        '';
      };

      settings = mkOption {
        type = types.submodule {
          freeformType = semanticConfType;
          options = {
            max-jobs = mkOption {
              type = types.either types.int (types.enum [ "auto" ]);
              default = "auto";
              example = 2;
              description = "How many builds run at once, as on NixOS (auto: one per processor core).";
            };
            auto-optimise-store = mkOption {
              type = types.bool;
              default = false;
              description = "Whether files the store has twice become one (hard links), as each comes, as on NixOS.";
            };
            cores = mkOption {
              type = types.int;
              default = 0;
              description = "How many cores one build may use (make -j), as on NixOS (0: all).";
            };
            sandbox = mkOption {
              type = types.either types.bool (types.enum [ "relaxed" ]);
              default = false;
              description = ''
                Whether builds run in Nix's sandbox, as on NixOS. Off on the phone (NixOS's is on): it needs PID and
                user namespaces, which Android's kernels lack (the phone's builds run as the nixbld users).
              '';
            };
            extra-sandbox-paths = mkOption {
              type = types.listOf types.str;
              default = [ ];
              description = "More of the phone in the sandbox, as on NixOS.";
            };
            substituters = mkOption {
              type = types.listOf types.str;
              description = "The binary caches packages come from, as on NixOS (cache.nixos.org is always one).";
            };
            trusted-substituters = mkOption {
              type = types.listOf types.str;
              default = [ ];
              description = "Binary caches the users who are not trusted may also ask for, as on NixOS.";
            };
            require-sigs = mkOption {
              type = types.bool;
              default = true;
              description = "Whether a binary cache's packages must be signed by trusted-public-keys, as on NixOS.";
            };
            trusted-public-keys = mkOption {
              type = types.listOf types.str;
              description = "The keys binary caches sign with, as on NixOS (cache.nixos.org's is always one).";
            };
            trusted-users = mkOption {
              type = types.listOf types.str;
              example = [ "root" "niobe" ];
              description = "Users the daemon lets choose binary caches and import packages, as on NixOS (root).";
            };
            allowed-users = mkOption {
              type = types.listOf types.str;
              default = [ "*" ];
              description = "Users the daemon serves, as on NixOS (everyone).";
            };
          };
        };
        default = { };
        example = lib.literalExpression ''
          {
            substituters = [ "https://nix-community.cachix.org" ];
            trusted-public-keys = [ "nix-community.cachix.org-1:mB9FSh9qf2dCimDSUo8Zy7bkq5CX+/rkCWyvRCYg3Fs=" ];
            keep-outputs = true;
          }
        '';
        description = ''
          Nix's settings (/etc/nix/nix.conf), as NixOS's nix.settings, with the phone's own defaults: flakes on
          (experimental-features), the sandbox off, builds as the nixbld users.
        '';
      };

      extraOptions = mkOption {
        type = types.lines;
        default = "";
        example = "keep-outputs = true";
        description = "Lines added to /etc/nix/nix.conf, as on NixOS.";
      };

      checkConfig = taken types.bool true "whether the build checks that Nix reads nix.conf (Nix says it when it reads it)";
      checkAllErrors = taken types.bool true "whether that check takes every error";

      nixPath = mkOption {
        type = types.listOf types.str;
        default = [ ];
        defaultText = lib.literalExpression ''[ "nixpkgs=''${config.nixpkgs.flake.source}" ]'';
        example = [ "nixpkgs=flake:nixpkgs" "/nix/var/nix/profiles/per-user/root/channels" ];
        description = ''
          NIX_PATH, the places <name> looks, as on NixOS: <nixpkgs> is the config's nixpkgs. A user's channels
          (nix-channel, root's too) come before it once they are there; NixOS's default also names root's channels'
          folder, which on a phone without channels only makes every lookup warn.
        '';
      };

      channel.enable = mkOption {
        type = types.bool;
        default = true;
        description = ''
          Whether nix-channel is there, as on NixOS: channels of one's own, first on that user's NIX_PATH (root's are
          in every user's nix-env too). The config's nixpkgs is not one: it is the registry's nixpkgs.
        '';
      };

      registry = mkOption {
        type = types.attrsOf (types.submodule ({ config, name, ... }:
          let
            referenceAttrs = types.attrsOf (types.oneOf [ types.str types.int types.bool types.path types.package ]);
          in
          {
            options = {
              from = mkOption {
                type = referenceAttrs;
                example = { type = "indirect"; id = "nixpkgs"; };
                description = "The flake reference to be rewritten, as on NixOS.";
              };
              to = mkOption {
                type = referenceAttrs;
                example = { type = "github"; owner = "my-org"; repo = "my-nixpkgs"; };
                description = "The flake reference it is rewritten to, as on NixOS.";
              };
              flake = mkOption {
                type = types.nullOr types.attrs;
                default = null;
                example = lib.literalExpression "nixpkgs";
                description = "The flake input it is rewritten to, as on NixOS (the phone keeps it too).";
              };
              exact = mkOption {
                type = types.bool;
                default = true;
                description = "Whether only that reference itself is rewritten (nixpkgs, not nixpkgs/nixos-25.11), as on NixOS.";
              };
            };
            config = {
              from = lib.mkDefault { type = "indirect"; id = name; };
              to = lib.mkIf (config.flake != null) (lib.mkDefault ({ type = "path"; path = config.flake.outPath; }
                // lib.filterAttrs (n: _: n == "lastModified" || n == "rev" || n == "narHash") config.flake));
            };
          }));
        default = { };
        description = ''
          The system's flake registry (/etc/nix/registry.json), as on NixOS: nixpkgs is the config's
          (nixpkgs.flake.setFlakeRegistry). The flakes it names that are in the store are kept on the phone.
        '';
      };

      nrBuildUsers = mkOption {
        type = types.int;
        description = ''
          How many nixbld users builds run as, as on NixOS (32, or max-jobs when that is more): each has a uid from
          400 to 999 on the phone.
        '';
      };

      envVars = mkOption {
        type = types.attrs;
        internal = true;
        default = { };
        description = "Environment variables for Nix: the sessions' and the daemon's.";
      };

      daemonCPUSchedPolicy = mkOption {
        type = types.enum [ "other" "batch" "idle" ];
        default = "other";
        description = "As on NixOS. Taken: the daemon runs below the apps on the screen, as Nixup's services do.";
      };
      daemonIOSchedClass = mkOption {
        type = types.enum [ "best-effort" "idle" ];
        default = "best-effort";
        description = "As on NixOS. Taken: the daemon runs below the apps on the screen, as Nixup's services do.";
      };
      daemonIOSchedPriority = mkOption {
        type = types.int;
        default = 4;
        description = "As on NixOS. Taken: the daemon runs below the apps on the screen, as Nixup's services do.";
      };
      daemonUser = mkOption {
        type = types.str;
        default = "root";
        description = "The daemon's user, as on NixOS: root on the phone.";
      };
      daemonGroup = mkOption {
        type = types.str;
        default = "root";
        description = "The daemon's group, as on NixOS: root on the phone.";
      };

      distributedBuilds = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Whether nix.buildMachines build what cache.nixos.org has not got, as on NixOS (and the shells' builds); else
          such a package stops the build.
        '';
      };
      buildMachines = mkOption {
        type = types.listOf (types.submodule {
          options = {
            hostName = mkOption { type = types.str; example = "nixbuilder.example.org"; description = "The build machine's host name, as on NixOS."; };
            protocol = mkOption {
              type = types.enum [ null "ssh" "ssh-ng" ];
              default = "ssh";
              example = "ssh-ng";
              description = "How Nix talks to it, as on NixOS.";
            };
            system = mkOption { type = types.nullOr types.str; default = null; example = "aarch64-linux"; description = "The system it builds for, as on NixOS (the phone's packages are aarch64-linux)."; };
            systems = mkOption { type = types.listOf types.str; default = [ ]; example = [ "aarch64-linux" ]; description = "The systems it builds for, as on NixOS."; };
            sshUser = mkOption { type = types.nullOr types.str; default = null; example = "builder"; description = "The user to log in as, as on NixOS (one of its nix.settings.trusted-users)."; };
            sshKey = mkOption {
              type = types.nullOr types.str;
              default = null;
              example = "/data/adb/nixup/secrets/builder_ed25519";
              description = "The private key to log in with, as on NixOS: a file on the phone, readable by root, not in Nix's store.";
            };
            maxJobs = mkOption { type = types.int; default = 1; description = "How many builds it takes at once, as on NixOS."; };
            speedFactor = mkOption { type = types.int; default = 1; description = "How fast it is next to the others, as on NixOS."; };
            mandatoryFeatures = mkOption { type = types.listOf types.str; default = [ ]; example = [ "big-parallel" ]; description = "As on NixOS."; };
            supportedFeatures = mkOption { type = types.listOf types.str; default = [ ]; example = [ "kvm" "big-parallel" ]; description = "As on NixOS."; };
            publicHostKey = mkOption {
              type = types.nullOr types.str;
              default = null;
              description = "Its ssh host key, base64 (base64 -w0 /etc/ssh/ssh_host_ed25519_key.pub), as on NixOS; null: root's known_hosts (/data/adb/nixup/home/.ssh).";
            };
          };
        });
        default = [ ];
        description = ''
          Machines that build what cache.nixos.org has not got, as NixOS's nix.buildMachines (with nix.distributedBuilds):
          the phone sends them the build over ssh and gets the package back.
        '';
      };

      sshServe = mkOption {
        type = types.submodule {
          freeformType = types.lazyAttrsOf types.anything;
          options.enable = mkOption {
            type = types.bool;
            default = false;
            description = "As on NixOS, the store served over SSH: not on the phone yet.";
          };
        };
        default = { };
        description = "As on NixOS, the store served over SSH (nix-ssh): not on the phone yet.";
      };

      gc = {
        automatic = mkOption {
          type = types.bool;
          default = false;
          description = "Whether Nix's garbage collector runs at nix.gc.dates, as on NixOS (Nixup's own packages stay).";
        };
        dates = mkOption {
          type = types.either types.singleLineStr (types.listOf types.str);
          apply = lib.toList;
          default = [ "03:15" ];
          example = "weekly";
          description = "When, as systemd's calendar events (systemd.time(7)), as on NixOS.";
        };
        randomizedDelaySec = mkOption {
          type = types.singleLineStr;
          default = "0";
          example = "45min";
          description = "A random delay before each run, up to this, as on NixOS.";
        };
        persistent = mkOption {
          type = types.bool;
          default = true;
          description = "Whether a run missed while the phone was off comes at its start, as on NixOS.";
        };
        options = mkOption {
          type = types.singleLineStr;
          default = "";
          example = "--delete-older-than 30d";
          description = "What nix-collect-garbage is given, as on NixOS.";
        };
      };

      optimise = {
        automatic = mkOption {
          type = types.bool;
          default = false;
          description = "Whether files the store has twice become one (nix-store --optimise) at nix.optimise.dates, as on NixOS.";
        };
        dates = mkOption {
          type = types.either types.singleLineStr (types.listOf types.str);
          apply = lib.toList;
          default = [ "03:45" ];
          description = "When, as systemd's calendar events, as on NixOS.";
        };
        randomizedDelaySec = mkOption {
          type = types.singleLineStr;
          default = "1800";
          description = "A random delay before each run, up to this, as on NixOS.";
        };
        persistent = mkOption {
          type = types.bool;
          default = true;
          description = "Whether a run missed while the phone was off comes at its start, as on NixOS.";
        };
      };
    };

    nixpkgs.flake = {
      source = mkOption {
        type = types.nullOr (types.either types.str types.path);
        default = configNixpkgs;
        defaultText = lib.literalExpression "the config's nixpkgs (its store path)";
        description = ''
          The nixpkgs the config is built with, as NixOS's: a flake's nixpkgs input, or for a configuration.nix the
          phone's copy of nixpkgs (or the PC's <nixpkgs>, when it is in the store).
        '';
      };
      setNixPath = mkOption {
        type = types.bool;
        default = flake.source != null;
        defaultText = lib.literalExpression "config.nixpkgs.flake.source != null";
        description = "Whether NIX_PATH's <nixpkgs> is the config's nixpkgs, as on NixOS.";
      };
      setFlakeRegistry = mkOption {
        type = types.bool;
        default = flake.source != null;
        defaultText = lib.literalExpression "config.nixpkgs.flake.source != null";
        description = ''
          Whether the registry's nixpkgs is the config's, as on NixOS: nix shell nixpkgs#htop takes htop from it,
          without fetching nixpkgs. The phone keeps that nixpkgs.
        '';
      };
    };
  };

  config = lib.mkMerge [
    {
      assertions = [
        {
          assertion = flake.setNixPath -> flake.setFlakeRegistry;
          message = "nixpkgs.flake.setNixPath needs nixpkgs.flake.setFlakeRegistry: <nixpkgs> is found through the registry.";
        }
        {
          assertion = cfg.daemonUser == "root" && cfg.daemonGroup == "root";
          message = "nix.daemonUser, nix.daemonGroup: Nix's daemon runs as root on the phone.";
        }
        {
          assertion = badMachines == [ ];
          message = "nix.buildMachines: give each a system (or systems), like \"aarch64-linux\": ${lib.concatStringsSep ", " badMachines}.";
        }
        {
          assertion = badKeys == [ ];
          message = "nix.buildMachines: sshKey is the full path of a file on the phone, not in Nix's store: ${lib.concatStringsSep ", " badKeys}.";
        }
        {
          assertion = !cfg.sshServe.enable;
          message = "nix.sshServe.enable: the store served over SSH is not on the phone yet.";
        }
        {
          assertion = (cfg.gc.automatic || cfg.optimise.automatic) -> cfg.enable;
          message = "nix.gc.automatic and nix.optimise.automatic need nix.enable.";
        }
      ];

      # What the engine does with it: Nix's commands, linked into /etc/nixup/bin, and the paths the phone keeps
      # (the registry's flakes: the config's nixpkgs). None: Nix is off.
      system.build.nix = if !cfg.enable then null else { inherit commands kept; };

      # For the phone's own builds (fetch_packages in common.sh): the machines, and the ssh Nix reaches them with.
      system.build.nixBuilders = if !remoteBuilds then null else {
        machines = machinesText;
        ssh = builtins.unsafeDiscardStringContext "${pkgs.openssh}/bin";
        sshPath = "${pkgs.openssh}";
      };

      warnings = lib.optional (cfg.distributedBuilds && cfg.buildMachines == [ ])
        "nix.distributedBuilds: there are no nix.buildMachines, so a package cache.nixos.org has not got still stops the build."
        ++ lib.optional (remoteBuilds && !lib.any (machine:
          lib.elem config.nixpkgs.hostPlatform (lib.optional (machine.system != null) machine.system ++ machine.systems)) cfg.buildMachines)
        "nix.buildMachines: none builds for ${config.nixpkgs.hostPlatform}, the phone's packages' system.";
    }

    (lib.mkIf (flake.source != null && flake.setFlakeRegistry) {
      nix.registry.nixpkgs.to = lib.mkDefault { type = "path"; path = flake.source; };
    })
    # <nixpkgs> is the config's nixpkgs itself, where NixOS has nixpkgs=flake:nixpkgs: the same nixpkgs, without
    # Nix fetching its global registry first, which a user of an Android app (no network while the app is in the
    # background) cannot.
    (lib.mkIf (flake.source != null && flake.setNixPath) {
      nix.nixPath = lib.mkDefault [ "nixpkgs=${noContext flake.source}" ];
    })

    (lib.mkIf cfg.enable {
      nix.settings = {
        trusted-public-keys = [ "cache.nixos.org-1:6NCHdD59X431o0gWypbMrAURkbJ16ZPMQFGspcDShjY=" ];
        trusted-users = [ "root" ];
        substituters = lib.mkAfter [ "https://cache.nixos.org/" ];
        # Nixup's own builds have them on: flakes, and the nix command.
        experimental-features = lib.mkDefault [ "nix-command" "flakes" ];
        # Nix's own default for root, written out (with nix.nrBuildUsers = 0, set it to "").
        build-users-group = lib.mkDefault "nixbld";
      };
      nix.nrBuildUsers = lib.mkDefault (lib.max 32 (if cfg.settings.max-jobs == "auto" then 0 else cfg.settings.max-jobs));

      environment.etc."nix/nix.conf".text = nixConf;
      environment.etc."nix/registry.json".text = registryJson;
      # The build machines, as NixOS writes them; without distributedBuilds Nix leaves them, as on NixOS.
      environment.etc."nix/machines" = lib.mkIf (cfg.buildMachines != [ ]) { text = machinesText; };
      nix.settings.builders = lib.mkIf (!cfg.distributedBuilds) null;

      # That Nix first on the shells' PATH, as NixOS has it.
      environment.systemPackages = lib.mkIf packageSet [ cfg.package ];

      environment.sessionVariables = cfg.envVars // { NIX_PATH = cfg.nixPath; };
      environment.extraInit = (if config.system.build.nixosShells.enabled then ''
        # Nix's commands (nix/nix.nix), after the profiles NixOS's shells' environment put on PATH (nix/shells.nix): the
        # user's own (nix profile, nix-env), then root's, as on NixOS.
        export PATH="$PATH:/etc/nixup/bin"
      '' else ''
        # Nix (nix/nix.nix): the user's own profiles first (nix profile, nix-env), then root's, as on NixOS; Nix's
        # commands after the config's packages and Android's own.
        export PATH="$HOME/.nix-profile/bin:''${XDG_STATE_HOME:-$HOME/.local/state}/nix/profile/bin:/nix/var/nix/profiles/default/bin:$PATH:/etc/nixup/bin"
      '') + ''
        export NIX_PROFILES="/nix/var/nix/profiles/default ''${XDG_STATE_HOME:-$HOME/.local/state}/nix/profile $HOME/.nix-profile"
      '' + lib.optionalString (!packageSet) ''
        # Where Nix finds its other commands (nix-channel runs nix-env): the module's, by these names.
        export NIX_BIN_DIR=/etc/nixup/bin
      '' + lib.optionalString cfg.channel.enable ''
        if [ -e "$HOME/.nix-defexpr/channels" ]; then
          export NIX_PATH="$HOME/.nix-defexpr/channels''${NIX_PATH:+:$NIX_PATH}"
        fi
      '' + ''
        # nix-env's own folder, made the first time a user logs in, as nix-env makes it (its channels), with the
        # config's nixpkgs in it (<nixpkgs>): nix-env -iA nixpkgs.htop works as with a channel of that name.
        if [ ! -e "${defexpr}" ] && mkdir -p "${defexpr}" 2> /dev/null; then
          if [ "$(/system/bin/id -u)" = 0 ]; then
            ln -s /nix/var/nix/profiles/per-user/root/channels "${defexpr}/channels"
          else
            ln -s "''${XDG_STATE_HOME:-$HOME/.local/state}/nix/profiles/channels" "${defexpr}/channels"
            ln -s /nix/var/nix/profiles/per-user/root/channels "${defexpr}/channels_root"
          fi
      '' + lib.optionalString nixpkgsOnPath "  echo 'import <nixpkgs>' > \"${defexpr}/nixpkgs.nix\"\n" + ''
        fi
      '';

      users.groups.nixbld = { };
      users.users = nixbldUsers;

      # Nix's daemon, for the users that log in (their Nix asks it, as the store is root's), as on NixOS. It makes its
      # socket itself (/nix/var/nix/daemon-socket/socket).
      systemd.services.nix-daemon = lib.mkIf (loginUsers != [ ]) {
        description = "Nix Daemon";
        wantedBy = [ "multi-user.target" ];
        # Its ssh, for the build machines, as on NixOS.
        path = lib.optional remoteBuilds pkgs.openssh;
        environment = cfg.envVars // { CURL_CA_BUNDLE = config.security.pki.caBundle; }
          // lib.optionalAttrs (!packageSet) { NIX_BIN_DIR = nixBin; };
        serviceConfig = {
          ExecStart = "${nixBin}/nix-daemon --daemon";
          # A build it runs goes on when it starts again, as Nix's own unit has it.
          KillMode = "process";
          Restart = "on-failure";
          RestartSec = 5;
          CPUSchedulingPolicy = cfg.daemonCPUSchedPolicy;
          IOSchedulingClass = cfg.daemonIOSchedClass;
          IOSchedulingPriority = cfg.daemonIOSchedPriority;
        };
        restartTriggers = [ nixConf registryJson ];
      };

      systemd.services.nix-gc = lib.mkIf cfg.gc.automatic {
        description = "Nix Garbage Collector";
        script = "exec ${nixBin}/nix-collect-garbage ${cfg.gc.options}";
        serviceConfig.Type = "oneshot";
        startAt = cfg.gc.dates;
        # Not run by a build, only at its times.
        restartIfChanged = false;
      };
      systemd.timers.nix-gc = lib.mkIf cfg.gc.automatic {
        timerConfig = { RandomizedDelaySec = cfg.gc.randomizedDelaySec; Persistent = cfg.gc.persistent; };
      };

      systemd.services.nix-optimise = lib.mkIf cfg.optimise.automatic {
        description = "Nix Store Optimiser";
        serviceConfig = {
          ExecStart = "${nixBin}/nix-store --optimise";
          Type = "oneshot";
          Nice = 19;
        };
        startAt = cfg.optimise.dates;
        restartIfChanged = false;
      };
      systemd.timers.nix-optimise = lib.mkIf cfg.optimise.automatic {
        timerConfig = { RandomizedDelaySec = cfg.optimise.randomizedDelaySec; Persistent = cfg.optimise.persistent; };
      };
    })
  ];
}
