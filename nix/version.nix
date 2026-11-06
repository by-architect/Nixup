# The system's version and where it was built from, as NixOS's misc/version.nix, misc/label.nix and system.name,
# system.configurationRevision and system.copySystemConfiguration have them, with their names and meanings:
#   - system.nixos.version, release, codeName, revision (the config's nixpkgs'), label and tags, which each generation
#     keeps and the app's Generations shows, as NixOS's boot menu shows a generation's label;
#   - system.configurationRevision: the git revision of the config's flake (self.rev or self.dirtyRev or null), kept
#     with the generation too;
#   - system.copySystemConfiguration: each generation keeps a copy of the configuration.nix it was built from, as
#     NixOS keeps it in /run/current-system/configuration.nix (one file alone, without what it imports; not with a
#     flake, as on NixOS, whose generations keep their flake.lock instead);
#   - /etc/os-release and /etc/lsb-release in Nixup's view, which programs read (fastfetch, neofetch, Python's
#     platform), with Nixup's name and ID_LIKE=nixos;
#   - nixos-version (module/system/bin), which tells them as NixOS's command does: --hash, --revision,
#     --configuration-revision, --json;
#   - system.build.toplevel: the profile (config.system.build.profile), as a tool written for NixOS asks for it.
{ config, lib, options, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.system.nixos;
  trivial = lib.trivial;

  # /etc/os-release and /etc/lsb-release as NixOS writes them (os-release(5)).
  needsEscaping = s: null != builtins.match "[a-zA-Z0-9]+" s;
  escapeIfNecessary = s: if needsEscaping s then s else ''"${lib.escape [ "$" "\"" "\\" "`" ] s}"'';
  attrsToText = attrs: lib.concatStringsSep "\n" (lib.mapAttrsToList (n: v: "${n}=${escapeIfNecessary (toString v)}") attrs) + "\n";
  isNixos = cfg.distroId == "nixos";
  osRelease = {
    NAME = cfg.distroName;
    ID = cfg.distroId;
    ID_LIKE = lib.optionalString (!isNixos) "nixos";
    VENDOR_NAME = cfg.vendorName;
    VERSION = "${cfg.release} (${cfg.codeName})";
    VERSION_CODENAME = lib.toLower cfg.codeName;
    VERSION_ID = cfg.release;
    BUILD_ID = cfg.version;
    PRETTY_NAME = "${cfg.distroName} ${cfg.release} (${cfg.codeName})";
    CPE_NAME = "cpe:/o:${cfg.vendorId}:${cfg.distroId}:${cfg.release}";
    LOGO = "nix-snowflake";
    HOME_URL = if isNixos then "https://nixos.org/" else "https://github.com/by-architect/Nixup";
    BUG_REPORT_URL = if isNixos then "https://github.com/NixOS/nixpkgs/issues" else "https://github.com/by-architect/Nixup/issues";
    IMAGE_ID = lib.optionalString (config.system.image.id != null) config.system.image.id;
    IMAGE_VERSION = lib.optionalString (config.system.image.version != null) config.system.image.version;
    VARIANT = lib.optionalString (cfg.variantName != null) cfg.variantName;
    VARIANT_ID = lib.optionalString (cfg.variant_id != null) cfg.variant_id;
    DEFAULT_HOSTNAME = cfg.distroId;
  } // cfg.extraOSReleaseArgs;
  lsbRelease = {
    LSB_VERSION = "${cfg.release} (${cfg.codeName})";
    DISTRIB_ID = cfg.distroId;
    DISTRIB_RELEASE = cfg.release;
    DISTRIB_CODENAME = lib.toLower cfg.codeName;
    DISTRIB_DESCRIPTION = "${cfg.distroName} ${cfg.release} (${cfg.codeName})";
  } // cfg.extraLSBReleaseArgs;
in
{
  # NixOS's older names, which modules still use.
  imports = [
    (lib.mkRenamedOptionModule [ "system" "nixosVersion" ] [ "system" "nixos" "version" ])
    (lib.mkRenamedOptionModule [ "system" "nixosVersionSuffix" ] [ "system" "nixos" "versionSuffix" ])
    (lib.mkRenamedOptionModule [ "system" "nixosRevision" ] [ "system" "nixos" "revision" ])
    (lib.mkRenamedOptionModule [ "system" "nixosLabel" ] [ "system" "nixos" "label" ])
  ];

  options.system = {
    nixos = {
      version = mkOption {
        type = types.str;
        description = "The full version, as on NixOS: release and versionSuffix (of the config's nixpkgs).";
      };
      release = mkOption {
        readOnly = true;
        type = types.str;
        default = trivial.release;
        description = "The release of the config's nixpkgs, as on NixOS (like \"26.05\").";
      };
      versionSuffix = mkOption {
        internal = true;
        type = types.str;
        default = trivial.versionSuffix;
        description = "The version's suffix (like \".20261013.a033e66\"), as on NixOS.";
      };
      revision = mkOption {
        internal = true;
        type = types.nullOr types.str;
        default = trivial.revisionWithDefault null;
        description = "The git revision of the config's nixpkgs, as on NixOS (null when not known).";
      };
      codeName = mkOption {
        readOnly = true;
        type = types.str;
        default = trivial.codeName;
        description = "The release's code name, as on NixOS.";
      };
      distroId = mkOption {
        internal = true;
        type = types.str;
        default = "nixup";
        description = "The distribution's id in /etc/os-release, as on NixOS (where it is \"nixos\"); Nixup's here, with ID_LIKE=nixos.";
      };
      distroName = mkOption {
        internal = true;
        type = types.str;
        default = "Nixup";
        description = "The distribution's name in /etc/os-release, as on NixOS (where it is \"NixOS\").";
      };
      variant_id = mkOption {
        type = types.nullOr (types.strMatching "^[a-z0-9._-]+$");
        default = null;
        description = "A variant's id in /etc/os-release, as on NixOS.";
      };
      variantName = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "A variant's name in /etc/os-release, as on NixOS.";
      };
      vendorId = mkOption {
        internal = true;
        type = types.str;
        default = "nixos";
        description = "The vendor's id in /etc/os-release, as on NixOS.";
      };
      vendorName = mkOption {
        internal = true;
        type = types.str;
        default = "NixOS";
        description = "The vendor's name in /etc/os-release, as on NixOS.";
      };
      extraOSReleaseArgs = mkOption {
        internal = true;
        type = types.attrsOf types.str;
        default = { };
        description = "More /etc/os-release lines, as on NixOS.";
      };
      extraLSBReleaseArgs = mkOption {
        internal = true;
        type = types.attrsOf types.str;
        default = { };
        description = "More /etc/lsb-release lines, as on NixOS.";
      };
      label = mkOption {
        type = types.strMatching "[a-zA-Z0-9:_\\.-]*";
        description = ''
          The generation's name, as on NixOS (where the boot menu shows it): system.nixos.tags, then the version, joined
          with "-" (letters, digits and : _ . -). The app's Generations shows it.
        '';
      };
      tags = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "travel" ];
        description = "Words before the version in system.nixos.label, as on NixOS.";
      };
    };

    image = {
      id = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "IMAGE_ID in /etc/os-release, as on NixOS.";
      };
      version = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "IMAGE_VERSION in /etc/os-release, as on NixOS.";
      };
    };

    stateVersion = mkOption {
      type = types.str;
      default = cfg.release;
      defaultText = lib.literalExpression "config.system.nixos.release";
      description = ''
        The NixOS release whose defaults NixOS's modules keep (a database's version), as on NixOS: set it to the
        release of the first build and leave it. Not set, it is the config's nixpkgs' release (NixOS warns then; a
        phone with only apps and settings has nothing it changes).
      '';
    };

    configurationRevision = mkOption {
      type = types.nullOr types.str;
      default = null;
      example = lib.literalExpression "self.rev or self.dirtyRev or null";
      description = ''
        The git revision of the config's flake, as on NixOS: each generation keeps it, and the app's Generations and
        nixos-version --configuration-revision show it.
      '';
    };

    copySystemConfiguration = mkOption {
      type = types.bool;
      default = false;
      description = ''
        Whether each generation keeps a copy of the configuration.nix it was built from, as NixOS keeps it in
        /run/current-system/configuration.nix (that file alone, without what it imports). Not with a flake, as on
        NixOS: a flake's generations keep its flake.lock instead.
      '';
    };

    name = mkOption {
      type = types.str;
      default = if config.networking.hostName == "" then "unnamed" else config.networking.hostName;
      defaultText = lib.literalExpression ''if config.networking.hostName == "" then "unnamed" else config.networking.hostName'';
      description = "The system's name in what its build makes, as on NixOS.";
    };
  };

  config = {
    system.nixos.version = lib.mkDefault (cfg.release + cfg.versionSuffix);
    system.nixos.label = lib.mkDefault (lib.maybeEnv "NIXOS_LABEL"
      (lib.concatStringsSep "-" ((lib.sort (x: y: x < y) cfg.tags) ++ [ (lib.maybeEnv "NIXOS_LABEL_VERSION" cfg.version) ])));

    # As NixOS's: what a tool written for NixOS builds (nixos-rebuild's system.build.toplevel) is the profile here.
    system.build.toplevel = config.system.build.profile;

    # What the phone keeps with each generation and nixos-version tells (Etc.kt writes /etc/nixup/version).
    system.build.version = {
      inherit (cfg) version release codeName revision label;
      inherit (config.system) configurationRevision copySystemConfiguration;
    };

    environment.etc."os-release".text = attrsToText osRelease;
    environment.etc."lsb-release".text = attrsToText lsbRelease;

    assertions = [
      {
        assertion = builtins.match "[0-9]{2}\\.[0-9]{2}" config.system.stateVersion != null;
        message = ''
          ${config.system.stateVersion} is an invalid value for 'system.stateVersion'; it must be in the format "YY.MM",
          which corresponds to a prior release of NixOS. Leave it at the release of your first build (or take it out:
          then it is your nixpkgs' release, "${cfg.release}").
        '';
      }
      {
        assertion = config.system.copySystemConfiguration -> !lib.inPureEvalMode;
        message = "system.copySystemConfiguration is not supported with flakes";
      }
    ];
  };
}
