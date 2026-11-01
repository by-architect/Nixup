{
  description = "Seraph: an Android phone configured like NixOS, from a configuration.nix";

  # Only nixpkgs' lib is used: evaluating a configuration builds and downloads nothing else.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }: {
    # As nixpkgs.lib.nixosSystem: the configuration's modules, evaluated with Seraph's options.
    # Its config.system.build.profile is what the phone applies. The repositories' package
    # names (pkgs.fdroid, ...) come from the phone's indexes, in reposDir (SERAPH_REPOS, with
    # --impure): they are the latest, not pinned by flake.lock.
    lib.seraphSystem =
      { modules, specialArgs ? { }, reposDir ? builtins.getEnv "SERAPH_REPOS" }:
      import ./nix/eval.nix { inherit (nixpkgs) lib; inherit modules specialArgs reposDir; };

    # nix flake init -t github:by-architect/Seraph
    templates.default = {
      path = ./templates/default;
      description = "A phone's configuration.nix, as a flake";
    };
  };
}
