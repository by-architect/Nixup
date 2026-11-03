# This phone's config as a flake, as /etc/nixos/flake.nix is for NixOS: Seraph's options come
# from github:by-architect/Seraph, at the version flake.lock pins (a build with update moves
# it to the newest). With this flake.nix picked in the app's Config, a build reads it;
# configuration.nix is the config either way.
{
  description = "Logos, configured by Seraph";

  inputs.seraph.url = "github:by-architect/Seraph";

  outputs = { seraph, ... }: {
    # Named after the phone (its device name): with more phones here, each builds its own.
    seraphConfigurations.Logos = seraph.lib.seraphSystem {
      modules = [ ./configuration.nix ];
    };
  };
}
