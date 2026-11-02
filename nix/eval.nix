# Evaluates a Seraph configuration, Seraph's options and the user's modules, as
# nixpkgs.lib.nixosSystem does for NixOS. The result's config.system.build.profile is what the
# phone applies (see build.nix).
#
# nixpkgs is nixpkgs' source (a path, or a flake's input), whose packages for the phone are pkgs
# (see nixpkgs.nix); without it pkgs has the repositories' apps only, which is all a config that
# uses no Nix package needs, and all config.system.build.repos ever needs.
#
# reposDir holds, for each repository the phone has an index of, <name>/tree.json: the tree of
# its package names, seen by the configuration as pkgs.<name>.
#
# Its modules are of the class "seraph", as NixOS's are "nixos": a module that says it is another
# class's (_class = "nixos";) is refused by name, before any of its options are looked at.
{ lib, modules, nixpkgs ? null, reposDir ? "", specialArgs ? { } }:
let
  hasTree = name: type: type == "directory" && builtins.pathExists "${reposDir}/${name}/tree.json";
  repoTrees =
    if reposDir == "" || !builtins.pathExists reposDir then { }
    else lib.mapAttrs (name: _: lib.importJSON "${reposDir}/${name}/tree.json") (lib.filterAttrs hasTree (builtins.readDir reposDir));
in
lib.evalModules {
  class = "seraph";
  modules = [ ./options.nix ] ++ modules;
  specialArgs = { _seraph = { inherit nixpkgs repoTrees; }; } // specialArgs;
}
