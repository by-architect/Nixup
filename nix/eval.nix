# Evaluates a Seraph configuration, Seraph's options and the user's modules, as
# nixpkgs.lib.nixosSystem does for NixOS. The result's config.system.build.profile is what the
# phone applies (see build.nix).
#
# reposDir holds, for each repository the phone has an index of, <name>/tree.json: the tree of
# its package names, seen by the configuration as pkgs.<name>. Without it pkgs is empty, which is
# all config.system.build.repos needs.
#
# Its modules are of the class "seraph", as NixOS's are "nixos": a module that says it is another
# class's (_class = "nixos";) is refused by name, before any of its options are looked at.
{ lib, modules, reposDir ? "", specialArgs ? { } }:
let
  hasTree = name: type: type == "directory" && builtins.pathExists "${reposDir}/${name}/tree.json";
  pkgs =
    if reposDir == "" || !builtins.pathExists reposDir then { }
    else lib.mapAttrs (name: _: lib.importJSON "${reposDir}/${name}/tree.json") (lib.filterAttrs hasTree (builtins.readDir reposDir));
in
lib.evalModules {
  class = "seraph";
  modules = [ ./options.nix ] ++ modules;
  specialArgs = { inherit pkgs; } // specialArgs;
}
