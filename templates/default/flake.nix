{
  description = "My phone, configured by Seraph";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    seraph.url = "github:by-architect/Seraph";
    seraph.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { seraph, ... }: {
    # One phone. More phones are more names: each is built as <this folder>#<name>.
    seraphConfigurations.phone = seraph.lib.seraphSystem {
      modules = [ ./configuration.nix ];
    };
  };
}
