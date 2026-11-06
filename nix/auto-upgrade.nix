# system.autoUpgrade, as NixOS's: the phone builds its config again on a schedule, as NixOS's nixos-upgrade timer runs
# `nixos-rebuild switch --upgrade`. Its timer is one of the engine's (Timers.kt), with NixOS's times (dates: a calendar
# event of systemd.time(7), randomizedDelaySec, fixedRandomDelay, persistent), named nixos-upgrade as on NixOS; at its time
# it runs the module's build.sh (build.sh update; upgrade = false: build.sh switch), as the app's Upgrade does:
#   - the config is the one the app's Config picked, or flake (a flake's address, or a flake.nix on the phone); a picked
#     config in a git clone (the app's Git) gets its repository's new commits first, as NixOS's flake gets its newest one;
#   - channel: the nixpkgs a configuration.nix alone gets (NixOS's channel URL), in place of the phone's nixos-unstable;
#   - runGarbageCollection: after an upgrade that worked, the Nix packages no build keeps go (build.sh gc);
#   - a phone's own conditions (android.autoUpgrade): on an unmetered network (Wi-Fi) only, unless told otherwise, and
#     never on a low battery; a time that comes while they do not hold is skipped, and the next one tried.
# Nixup never changes the phone's kernel, so a build needs no restart: allowReboot and rebootWindow are taken and do
# nothing; operation = "boot" (applied at the next start) is not on the phone. nixos-rebuild's flags are not either.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.system.autoUpgrade;
  phone = config.android.autoUpgrade;
in
{
  options.system.autoUpgrade = {
    enable = mkOption {
      type = types.bool;
      default = false;
      description = ''
        Whether the phone builds its config again on a schedule, as NixOS's system.autoUpgrade: build.sh update (the
        app's Upgrade: everything at its newest), at dates.
      '';
    };

    operation = mkOption {
      type = types.enum [ "switch" "boot" ];
      default = "switch";
      description = ''
        As on NixOS: "switch" applies the new build at once. "boot" (applied at the next start) is not on the phone,
        where a build needs no restart.
      '';
    };

    flake = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "github:you/phone#logos";
      description = ''
        The flake to build, as on NixOS: an address (github:you/phone, with #name for one of its nixupConfigurations)
        or a flake.nix on the phone. null: the config the app's Config picked.
      '';
    };

    channel = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = "https://channels.nixos.org/nixos-26.05";
      description = ''
        The NixOS channel a configuration.nix alone gets its nixpkgs from, as on NixOS (its nixexprs.tar.xz), in place of
        the phone's nixos-unstable. Not with flake.
      '';
    };

    upgrade = mkOption {
      type = types.bool;
      default = true;
      description = ''
        As on NixOS: everything at its newest (a flake's inputs, the phone's nixpkgs, the repositories' indexes), as the
        app's Upgrade; false: the config built again as it is locked (flake.lock), as the app's Build.
      '';
    };

    flags = mkOption {
      type = types.listOf types.str;
      default = [ ];
      description = "nixos-rebuild's flags, as on NixOS: not on the phone, whose build takes none (they are left out, and the build says so).";
    };

    dates = mkOption {
      type = types.str;
      default = "04:40";
      example = "daily";
      description = "When the phone builds again, as on NixOS: a calendar event of systemd.time(7), in the phone's time zone.";
    };

    allowReboot = mkOption {
      type = types.bool;
      default = false;
      description = ''
        As on NixOS, a restart into a build with another kernel. Taken: Nixup never changes the phone's kernel, so a
        build needs no restart.
      '';
    };

    randomizedDelaySec = mkOption {
      type = types.str;
      default = "0";
      example = "45min";
      description = "A random delay before each build, up to this time span (systemd.time(7)), as on NixOS.";
    };

    fixedRandomDelay = mkOption {
      type = types.bool;
      default = false;
      description = "Whether the random delay is the same each time, as on NixOS.";
    };

    rebootWindow = mkOption {
      type = types.nullOr (types.submodule {
        options = {
          lower = mkOption { type = types.strMatching "[[:digit:]]{2}:[[:digit:]]{2}"; example = "01:00"; description = "Lower limit of the reboot window."; };
          upper = mkOption { type = types.strMatching "[[:digit:]]{2}:[[:digit:]]{2}"; example = "05:00"; description = "Upper limit of the reboot window."; };
        };
      });
      default = null;
      description = "As on NixOS, with allowReboot. Taken: a build on the phone needs no restart.";
    };

    persistent = mkOption {
      type = types.bool;
      default = true;
      description = ''
        As on NixOS: a time missed while the phone was off comes as soon as it is on again (one that came while
        android.autoUpgrade's conditions did not hold is skipped, as a systemd unit whose condition fails).
      '';
    };

    runGarbageCollection = mkOption {
      type = types.bool;
      default = false;
      description = "As on NixOS: after an upgrade that worked, the Nix packages no build keeps go (the app's Free up space).";
    };
  };

  # Nixup's own: what a phone needs besides a time.
  options.android.autoUpgrade = {
    onlyOnUnmetered = mkOption {
      type = types.bool;
      default = true;
      description = ''
        Whether system.autoUpgrade builds only on an unmetered network (Wi-Fi), as the app's update check does: an upgrade
        can download a lot. A time that comes on mobile data is skipped.
      '';
    };

    onlyWhileCharging = mkOption {
      type = types.bool;
      default = false;
      description = "Whether system.autoUpgrade builds only while the phone charges. A time that comes otherwise is skipped.";
    };
  };

  config = {
    # The timer, for the engine (see build.nix): its times as a systemd timer's, and what it runs.
    system.build.autoUpgrade = lib.optionalAttrs cfg.enable {
      nixos-upgrade = {
        unit = "nixos-upgrade";
        description = "NixOS Upgrade";
        option = "system.autoUpgrade";
        wanted = true;
        onCalendar = [ cfg.dates ];
        randomizedDelay = cfg.randomizedDelaySec;
        job = {
          script = "build.sh";
          args = [ (if cfg.upgrade || cfg.channel != null then "update" else "switch") ]
            ++ lib.optional (cfg.flake != null) "--source=${cfg.flake}"
            ++ lib.optional (cfg.flake == null) "--pull"
            ++ lib.optional (cfg.channel != null) "--channel=${lib.removeSuffix "/" cfg.channel}/nixexprs.tar.xz"
            ++ lib.optional cfg.runGarbageCollection "--gc";
          what = "the upgrade";
          unmetered = phone.onlyOnUnmetered;
          charging = phone.onlyWhileCharging;
        };
      } // lib.optionalAttrs cfg.persistent { persistent = true; }
        // lib.optionalAttrs cfg.fixedRandomDelay { fixedRandomDelay = true; };
    };

    assertions = lib.optionals cfg.enable [
      {
        assertion = cfg.channel == null || cfg.flake == null;
        message = "The options 'system.autoUpgrade.channel' and 'system.autoUpgrade.flake' cannot both be set.";
      }
      {
        assertion = cfg.operation == "switch";
        message = "system.autoUpgrade.operation: \"boot\" (applied at the next start) is not on the phone, where a build needs no restart: use \"switch\".";
      }
      {
        assertion = !(config.systemd.timers ? nixos-upgrade);
        message = "systemd.timers.nixos-upgrade: that name is system.autoUpgrade's timer's.";
      }
    ];

    warnings = lib.optional (cfg.enable && cfg.flags != [ ])
      "system.autoUpgrade.flags: nixos-rebuild's flags are not on the phone (${lib.concatStringsSep " " cfg.flags}): left out."
      ++ lib.optional (cfg.enable && cfg.allowReboot)
      "system.autoUpgrade.allowReboot: Nixup never changes the phone's kernel, so a build needs no restart: the phone never restarts for one.";
  };
}
