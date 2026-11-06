# Files in /etc, as NixOS's environment.etc: Nixup's view of the phone has an /etc of its own (Etc.kt writes it),
# where its services and shells look, and each build puts these there: a file's text, or a link to its source (a Nix
# package's file, or one the config makes), or with mode a copy owned by user and group. One the config no longer
# has goes. Android's own /etc (/system/etc) is not touched.
#
# Nixup writes some of that /etc itself: passwd and group (users.users), hosts (networking.hosts), resolv.conf (the
# network's DNS servers, or networking.nameservers), ssl/certs/ca-certificates.crt (Android's certificates), profile,
# zshenv and nixup/. A file of environment.etc cannot be one of those.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;

  etcModule = { name, config, options, ... }: {
    options = {
      enable = mkOption {
        type = types.bool;
        default = true;
        description = "Whether this /etc file is made.";
      };
      target = mkOption {
        type = types.str;
        description = "Its path under /etc (the attribute's name by default).";
      };
      text = mkOption {
        type = types.nullOr types.lines;
        default = null;
        description = "Its text.";
      };
      source = mkOption {
        type = types.path;
        description = "The file it is (a link to it, or with mode a copy of it).";
      };
      mode = mkOption {
        type = types.str;
        default = "symlink";
        example = "0600";
        description = "\"symlink\": a link to source (a file of its text); a mode: a copy, with that mode, owned by user and group.";
      };
      uid = mkOption {
        type = types.int;
        default = 0;
        description = "The copy's owner, as a number (with mode).";
      };
      gid = mkOption {
        type = types.int;
        default = 0;
        description = "The copy's group, as a number (with mode).";
      };
      user = mkOption {
        type = types.str;
        default = "+${toString config.uid}";
        description = "The copy's owner, a user of users.users (with mode; \"+<uid>\": that number).";
      };
      group = mkOption {
        type = types.str;
        default = "+${toString config.gid}";
        description = "The copy's group, a group of users.groups (with mode; \"+<gid>\": that number).";
      };
    };
    config = {
      target = lib.mkDefault name;
      # As on NixOS, for a module that reads it (a unit's restartTriggers); the phone writes the text itself, so
      # this is built only when something uses it.
      source = lib.mkIf (config.text != null) (
        let name' = "etc-" + lib.replaceStrings [ "/" ] [ "-" ] name; in
        lib.mkDerivedConfig options.text (pkgs.writeText name')
      );
    };
  };

  entries = lib.filter (entry: entry.enable) (lib.attrValues config.environment.etc);
  # The files Nixup writes itself, which an entry cannot be.
  own = [ "passwd" "group" "hosts" "resolv.conf" "ssl/certs/ca-certificates.crt" "profile" "zshenv" ];
  clean = target: lib.removePrefix "/" target;
  mine = entry: lib.elem (clean entry.target) own || lib.hasPrefix "nixup/" (clean entry.target) || clean entry.target == "nixup";
in
{
  options.environment.etc = mkOption {
    type = types.attrsOf (types.submodule etcModule);
    default = { };
    example = lib.literalExpression ''
      {
        "foo/settings.conf".text = "a = 1";
        "foo/server.yml".source = (pkgs.formats.yaml { }).generate "server.yml" { port = 8080; };
        "foo/secret".source = ./secret;   # a copy readable by foo alone:
        "foo/secret".mode = "0400";
        "foo/secret".user = "foo";
      }
    '';
    description = ''
      Files in Nixup's /etc, where its services and shells look, as NixOS's environment.etc: text, or a link to a
      source file, or with mode a copy owned by user and group. Android's own /etc is not touched.
    '';
  };

  config = {
    # For the engine: each file's target, and its text or source; a copy's mode and owner (a name, or "+<number>").
    system.build.etc = map (entry: { target = clean entry.target; inherit (entry) mode; }
      // (if entry.text != null then { inherit (entry) text; } else { source = "${entry.source}"; })
      // lib.optionalAttrs (entry.mode != "symlink") { inherit (entry) user group; }
    ) entries;

    assertions = map (entry: {
      assertion = !mine entry;
      message = "environment.etc.\"${entry.target}\": Nixup writes /etc/${clean entry.target} itself (users.users, networking.hosts, networking.nameservers, Android's certificates).";
    }) entries
    ++ map (entry: {
      assertion = entry.mode == "symlink" || entry.mode == "direct-symlink" || builtins.match "[0-7]{3,4}" entry.mode != null;
      message = "environment.etc.\"${entry.target}\".mode: \"symlink\", or a mode like \"0644\".";
    }) entries;
  };
}
