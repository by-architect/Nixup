{
  description = "My phone, configured by Seraph";

  inputs.seraph.url = "github:by-architect/Seraph";

  outputs = { seraph, ... }: {
    # One phone. More phones are more names: each is built as <this folder>#<name>.
    seraphConfigurations.phone = seraph.lib.seraphSystem {
      modules = [ ./configuration.nix ];
    };
  };
}
