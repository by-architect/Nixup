# What NixOS says of its Nix store's upkeep and of builds on other machines, on the phone:
#   - nix.optimise: Seraph's store made smaller at its dates, as NixOS's nix-optimise timer runs `nix-store --optimise`
#     (files that are the same, kept once); like NixOS's (ConditionACPower), only while the phone charges, else skipped.
#     Its timer is one of the engine's (Timers.kt), named nix-optimise as on NixOS.
#   - nix.buildMachines and nix.distributedBuilds: a package cache.nixos.org has not got (an overlay's, an unfree one) is
#     built on those machines over ssh, as Nix's remote builds do, and copied to the phone, where the build otherwise
#     stops at it (the phone builds no package from its source). The files a config makes are still built on the phone.
#     Nix's ssh is nixpkgs' openssh, fetched with the packages; a machine's sshKey is a file on the phone, readable by
#     root (in /data/adb/seraph/secrets, say), and its publicHostKey (or root's known_hosts in /data/adb/seraph/home/.ssh)
#     is how the phone knows it.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.nix;

  # A line of Nix's machines file (nix.conf's builders), as NixOS writes /etc/nix/machines.
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
  builders = lib.concatMapStrings (machine: machineLine machine + "\n") cfg.buildMachines;
  badMachines = map (machine: machine.hostName) (lib.filter (machine: machine.system == null && machine.systems == [ ]) cfg.buildMachines);
  badKeys = map (machine: machine.hostName) (lib.filter (machine: machine.sshKey != null
    && (!lib.hasPrefix "/" machine.sshKey || lib.hasPrefix builtins.storeDir machine.sshKey)) cfg.buildMachines);
in
{
  options.nix = {
    optimise = {
      automatic = mkOption {
        type = types.bool;
        default = false;
        description = "Whether Seraph's store is made smaller at dates (files that are the same, kept once), as NixOS's nix.optimise.automatic; only while the phone charges.";
      };
      dates = mkOption {
        type = types.either types.singleLineStr (types.listOf types.str);
        default = [ "03:45" ];
        apply = lib.toList;
        description = "When, as on NixOS: calendar events of systemd.time(7), in the phone's time zone.";
      };
      randomizedDelaySec = mkOption {
        type = types.singleLineStr;
        default = "1800";
        example = "45min";
        description = "A random delay before each run, up to this time span, as on NixOS.";
      };
      persistent = mkOption {
        type = types.bool;
        default = true;
        description = "As on NixOS: a time missed while the phone was off comes as soon as it is on again (one that came while it was not charging is skipped).";
      };
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
            example = "/data/adb/seraph/secrets/builder_ed25519";
            description = "The private key to log in with, as on NixOS: a file on the phone, readable by root, not in Nix's store.";
          };
          maxJobs = mkOption { type = types.int; default = 1; description = "How many builds it takes at once, as on NixOS."; };
          speedFactor = mkOption { type = types.int; default = 1; description = "How fast it is next to the others, as on NixOS."; };
          mandatoryFeatures = mkOption { type = types.listOf types.str; default = [ ]; example = [ "big-parallel" ]; description = "As on NixOS."; };
          supportedFeatures = mkOption { type = types.listOf types.str; default = [ ]; example = [ "kvm" "big-parallel" ]; description = "As on NixOS."; };
          publicHostKey = mkOption {
            type = types.nullOr types.str;
            default = null;
            description = "Its ssh host key, base64 (base64 -w0 /etc/ssh/ssh_host_ed25519_key.pub), as on NixOS; null: root's known_hosts (/data/adb/seraph/home/.ssh).";
          };
        };
      });
      default = [ ];
      description = ''
        Machines that build what cache.nixos.org has not got, as NixOS's nix.buildMachines (with nix.distributedBuilds):
        the phone sends them the build over ssh and gets the package back.
      '';
    };

    distributedBuilds = mkOption {
      type = types.bool;
      default = false;
      description = "Whether the phone has nix.buildMachines build what cache.nixos.org has not got, as on NixOS; else such a package stops the build.";
    };
  };

  config = {
    # The timer, for the engine (see build.nix).
    system.build.nixOptimise = lib.optionalAttrs cfg.optimise.automatic {
      nix-optimise = {
        unit = "nix-optimise";
        description = "Nix Store Optimiser";
        option = "nix.optimise";
        wanted = true;
        onCalendar = cfg.optimise.dates;
        randomizedDelay = cfg.optimise.randomizedDelaySec;
        job = {
          script = "build.sh";
          args = [ "optimise" ];
          what = "making the store smaller";
          unmetered = false;
          charging = true;
        };
      } // lib.optionalAttrs cfg.optimise.persistent { persistent = true; };
    };

    # For the phone's builds (fetch_packages in common.sh): the machines, and the ssh Nix runs to reach them.
    system.build.nixBuilders =
      if cfg.distributedBuilds && cfg.buildMachines != [ ] then {
        machines = builders;
        ssh = builtins.unsafeDiscardStringContext "${pkgs.openssh}/bin";
        sshPath = "${pkgs.openssh}";
      } else null;

    # As NixOS has it, for programs in Seraph's view that read it.
    environment.etc."nix/machines" = lib.mkIf (cfg.buildMachines != [ ]) { text = builders; };

    assertions = [
      {
        assertion = badMachines == [ ];
        message = "nix.buildMachines: give each a system (or systems), like \"aarch64-linux\": ${lib.concatStringsSep ", " badMachines}.";
      }
      {
        assertion = badKeys == [ ];
        message = "nix.buildMachines: sshKey is the full path of a file on the phone, not in Nix's store: ${lib.concatStringsSep ", " badKeys}.";
      }
      {
        assertion = !(config.systemd.timers ? nix-optimise);
        message = "systemd.timers.nix-optimise: that name is nix.optimise's timer's.";
      }
    ];

    warnings = lib.optional (cfg.distributedBuilds && cfg.buildMachines == [ ])
      "nix.distributedBuilds: there are no nix.buildMachines, so a package cache.nixos.org has not got still stops the build."
      ++ lib.optional (cfg.distributedBuilds && cfg.buildMachines != [ ] && !lib.any (machine:
        lib.elem config.nixpkgs.hostPlatform (lib.optional (machine.system != null) machine.system ++ machine.systems)) cfg.buildMachines)
      "nix.buildMachines: none builds for ${config.nixpkgs.hostPlatform}, the phone's packages' system.";
  };
}
