# The options that take a secret file (services.tailscale.authKeyFile, services.syncthing.cert, ...).
# Given a file from the config's folder, as a path (./tailscale.key, ../keys/syncthing.pem), each
# build puts it on the phone, in /data/adb/seraph/secrets, readable by root alone, and the program
# reads it there: it never goes into the profile or Nix's store (on NixOS a path there would be
# copied into the store, which every user can read). Given a string, it is a file already on the
# phone, by its full path. Seraph keeps the files it puts there in android.secrets (nix/secrets.nix),
# so one that leaves the config goes from the phone too.
{ lib }:
{
  # The option's type: a path, or a string (as a NixOS module's *File takes them).
  type = lib.types.nullOr (lib.types.either lib.types.path lib.types.str);

  # The secret Seraph keeps for option [name] given [value]: none for a file already on the phone.
  secrets = name: value: lib.optionalAttrs (builtins.isPath value) { ${name}.file = value; };

  # Where the program reads it on the phone.
  path = config: name: value: if builtins.isPath value then config.android.secrets.${name}.path else value;

  # Whether [value] is one Seraph can take: none, a file from the config's folder, or a full path.
  valid = value: value == null || builtins.isPath value || lib.hasPrefix "/" value;

  # What to say when it is not: a relative string, which is neither.
  problem = name: value:
    "${name}: \"${toString value}\" is not a full path on the phone; a file from the config's folder is a path, written without quotes, like ./${baseNameOf (toString value)}";
}
