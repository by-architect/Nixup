{
  description = "Seraph: an Android phone configured like NixOS, from a configuration.nix";

  # Only nixpkgs' lib is used, from nix-community's copy of it alone (a few MB, where nixpkgs is
  # 45): evaluating a configuration builds and downloads nothing else.
  inputs.nixpkgs-lib.url = "github:nix-community/nixpkgs.lib";

  outputs = { self, nixpkgs-lib }: {
    # As nixpkgs.lib.nixosSystem: the configuration's modules, evaluated with Seraph's options.
    # Its config.system.build.profile is what the phone applies. The repositories' package
    # names (pkgs.fdroid, ...) come from the phone's indexes, in reposDir (SERAPH_REPOS, with
    # --impure): they are the latest, not pinned by flake.lock.
    lib.seraphSystem =
      { modules, specialArgs ? { }, reposDir ? builtins.getEnv "SERAPH_REPOS" }:
      import ./nix/eval.nix { inherit (nixpkgs-lib) lib; inherit modules specialArgs reposDir; };

    # nix flake init -t github:by-architect/Seraph
    templates.default = {
      path = ./templates/default;
      description = "A phone's configuration.nix, as a flake";
    };

    # seraph-rebuild, which builds a phone's config on a PC and applies it on the phone, as
    # nixos-rebuild --target-host does: nix run github:by-architect/Seraph -- switch --flake .#phone
    # It runs from the flake's own files, with the adb (or ssh) and nix of the machine.
    apps = nixpkgs-lib.lib.genAttrs [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ] (system: rec {
      seraph-rebuild = {
        type = "app";
        program = "${self}/rebuild/seraph-rebuild";
        meta.description = "Build a phone's Seraph config here and apply it on the phone";
      };
      default = seraph-rebuild;
    });

    # The same as a package, built with the nixpkgs it is added to: pkgs.seraph-rebuild.
    overlays.default = final: prev: {
      seraph-rebuild = final.callPackage ./rebuild/package.nix { };
    };
  };
}
