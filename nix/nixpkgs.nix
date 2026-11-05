# Nix packages, as NixOS's nixpkgs module gives them: pkgs is nixpkgs for the phone's processor
# (aarch64-linux), with nixpkgs.config and nixpkgs.overlays, as on NixOS. The repositories' apps
# (pkgs.fdroid, pkgs.<repo>) are added on top. The phone fetches the packages a config uses from
# cache.nixos.org (nothing is built on it) and runs them where /nix exists: in its services' view
# (nix/units.nix).
#
# nixpkgs itself comes from a flake's input (seraph's own, or the config's: inputs.seraph.inputs
# .nixpkgs.follows = "nixpkgs";), or, for a configuration.nix alone, from the copy of nixos-unstable
# the phone keeps, as a NixOS channel. A config that uses no Nix package never needs it.
{ config, lib, _seraph, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.nixpkgs;

  # The repositories' apps over nixpkgs; a repository named like a package of nixpkgs would hide
  # it, which is an error when either is used.
  repos = final: prev: lib.mapAttrs (name: tree:
    if prev ? ${name}
    then throw "android.repos.${name}: nixpkgs has a package named ${name} (pkgs.${name}), which the repository would hide: name it otherwise."
    else tree
  ) _seraph.repoTrees;

  overlayType = lib.mkOptionType {
    name = "nixpkgs-overlay";
    description = "nixpkgs overlay";
    check = lib.isFunction;
    merge = lib.mergeOneOption;
  };
in
{
  options.nixpkgs = {
    config = mkOption {
      type = types.attrsOf types.anything;
      default = { };
      example = { allowUnfree = true; };
      description = "nixpkgs' own settings, as NixOS's nixpkgs.config (allowUnfree, ...).";
    };

    overlays = mkOption {
      type = types.listOf overlayType;
      default = [ ];
      example = lib.literalExpression "[ (final: prev: { foo = prev.foo.override { … }; }) ]";
      description = ''
        Overlays over nixpkgs, as NixOS's nixpkgs.overlays. A package an overlay changes is built
        from source, which the phone does not do: only what cache.nixos.org has can be used.
      '';
    };

    hostPlatform = mkOption {
      type = types.str;
      default = "aarch64-linux";
      description = "The phone's processor, as NixOS's nixpkgs.hostPlatform: Seraph runs on aarch64-linux.";
    };
  };

  config = {
    # nixpkgs' pkgs.formats, as modules use it, writes YAML, JSON and TOML without Python (nix/formats.nix); the
    # packages are nixpkgs' own, so cache.nixos.org has them.
    _module.args.pkgs =
      if _seraph.nixpkgs == null then _seraph.repoTrees
      else
        let
          pkgs = import _seraph.nixpkgs {
            system = cfg.hostPlatform;
            inherit (cfg) config;
            overlays = [ repos ] ++ cfg.overlays;
          };
        in
        pkgs // { formats = import ./formats.nix { inherit lib pkgs; inherit (pkgs) formats; }; };

    assertions = [
      {
        assertion = cfg.hostPlatform == "aarch64-linux";
        message = "nixpkgs.hostPlatform: Seraph runs on 64-bit ARM phones (aarch64-linux), not ${cfg.hostPlatform}.";
      }
    ];
  };
}
