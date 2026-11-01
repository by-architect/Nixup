# The phone's build without flakes: turns a configuration.nix into the profile, the JSON the
# Magisk module applies, or with reposOnly into the repositories to fetch first:
#   nix-instantiate --eval --strict --json nix/profile.nix --argstr configFile "$PWD/config/configuration.nix"
# Only nixpkgs' lib is used, so evaluating it builds and downloads nothing. A flake does the same
# with seraph.lib.seraphSystem (see /flake.nix).
#
# The repositories are in the configuration itself, so the module first evaluates it with
# reposOnly = true: that needs no package names, and gives the repositories to fetch the indexes
# of (into reposDir, whose trees are pkgs.<repo>) before the whole configuration is evaluated.
{ configFile, reposDir ? "", reposOnly ? false, lib ? import <nixpkgs/lib> }:
let
  seraph = import ./eval.nix { inherit lib reposDir; modules = [ (/. + configFile) ]; };
in
seraph.config.system.build.${if reposOnly then "repos" else "profile"}
