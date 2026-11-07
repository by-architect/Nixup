# The phone's build without flakes: turns a configuration.nix into the profile, the JSON the
# Magisk module applies, or with reposOnly into the repositories to fetch first:
#   nix-instantiate --eval --strict --json --readonly-mode nix/profile.nix --argstr configFile "$PWD/config/configuration.nix"
# Evaluating it builds and downloads nothing: --readonly-mode works out the Nix packages' store
# paths without writing to a store, and the phone fetches them after. A flake does the same with
# nixup.lib.nixupSystem (see /flake.nix).
#
# The repositories are in the configuration itself, so the module first evaluates it with
# reposOnly = true: that needs no package names, and gives the repositories to fetch the indexes
# of (into reposDir, whose trees are pkgs.<repo>) before the whole configuration is evaluated, and
# whether it needs nixpkgs at all (the phone's copy of nixos-unstable, given as nixpkgs).
#
# lib is the given nixpkgs' own, as a flake's is its nixpkgs input's: NixOS's modules and Home Manager are evaluated
# with the lib of their release (nixpkgs' lib packed in the module, <nixpkgs/lib>, is for a first look, which has no
# nixpkgs yet).
{ configFile, reposDir ? "", reposOnly ? false, part ? "", nixpkgs ? ""
, lib ? if nixpkgs != "" then import (/. + nixpkgs + "/lib") else import <nixpkgs/lib> }:
let
  nixup = import ./eval.nix {
    inherit lib reposDir;
    nixpkgs = if nixpkgs == "" then null else /. + nixpkgs;
    modules = [ (/. + configFile) ];
  };
in
# part: another of config.system.build's, like secretsToDeliver.
nixup.config.system.build.${if part != "" then part else if reposOnly then "repos" else "profile"}
