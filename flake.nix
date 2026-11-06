{
  description = "Nixup: an Android phone configured like NixOS, from a configuration.nix";

  # nixpkgs: the phone's Nix packages (pkgs, for aarch64-linux, fetched from cache.nixos.org) and
  # its lib. A config's flake can give its own: inputs.nixup.inputs.nixpkgs.follows = "nixpkgs";
  inputs.nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }: {
    # As nixpkgs.lib.nixosSystem: the configuration's modules, evaluated with Nixup's options.
    # Its config.system.build.profile is what the phone applies. The repositories' package
    # names (pkgs.fdroid, ...) come from the phone's indexes, in reposDir (NIXUP_REPOS, with
    # --impure): they are the latest, not pinned by flake.lock.
    lib.nixupSystem =
      { modules, specialArgs ? { }, reposDir ? builtins.getEnv "NIXUP_REPOS" }:
      import ./nix/eval.nix { inherit (nixpkgs) lib; inherit nixpkgs modules specialArgs reposDir; };
    # Its name from when Nixup was called Seraph, so a flake made then builds as it is.
    lib.seraphSystem = self.lib.nixupSystem;

    # nix flake init -t github:by-architect/Nixup
    templates.default = {
      path = ./templates/default;
      description = "A phone's configuration.nix, as a flake";
    };

    # nixup-rebuild, which builds a phone's config on a PC and applies it on the phone, as
    # nixos-rebuild --target-host does: nix run github:by-architect/Nixup -- switch --flake .#phone
    # It runs from the flake's own files, with the adb (or ssh) and nix of the machine.
    apps = nixpkgs.lib.genAttrs [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ] (system: rec {
      nixup-rebuild = {
        type = "app";
        program = "${self}/rebuild/nixup-rebuild";
        meta.description = "Build a phone's Nixup config here and apply it on the phone";
      };
      # A Google Play account's token for the config (android.playstore.tokenFile), signed in here:
      # nix run github:by-architect/Nixup#nixup-play-login
      nixup-play-login = {
        type = "app";
        program = "${self}/rebuild/nixup-play-login";
        meta.description = "Sign in to Google Play here and get the account's token for a phone's Nixup config";
      };
      default = nixup-rebuild;
    });

    # The same as a package, built with the nixpkgs it is added to: pkgs.nixup-rebuild.
    overlays.default = final: prev: {
      nixup-rebuild = final.callPackage ./rebuild/package.nix { };
    };
  };
}
