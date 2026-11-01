{
  description = "My phone, configured by Seraph";

  inputs.seraph.url = "github:by-architect/Seraph";

  outputs = { seraph, ... }: {
    # One phone. More phones are more names: each is built as <this folder>#<name>, on the phone
    # (the app's Config picks this flake.nix) or on a PC:
    #   nix run github:by-architect/Seraph -- switch --flake .#phone
    seraphConfigurations.phone = seraph.lib.seraphSystem {
      modules = [ ./configuration.nix ];
    };
  };
}
