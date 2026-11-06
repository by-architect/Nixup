# This phone's config as a flake, as /etc/nixos/flake.nix is for NixOS: Nixup's options come
# from github:by-architect/Nixup, at the version flake.lock pins (a build with update moves
# it to the newest). With this flake.nix picked in the app's Config, a build reads it;
# configuration.nix is the config either way.
{
  description = "Logos, configured by Nixup";

  inputs.nixup.url = "github:by-architect/Nixup";

  outputs = { nixup, ... }: {
    # Named after the phone (its device name): with more phones here, each builds its own.
    nixupConfigurations.Logos = nixup.lib.nixupSystem {
      modules = [ ./configuration.nix ];
    };
  };
}
