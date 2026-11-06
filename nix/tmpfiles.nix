# Folders and files the services need, as NixOS's systemd.tmpfiles (tmpfiles.d(5)): each build and each start of the
# phone makes them (the engine, Tmpfiles.kt), before the units start, as systemd-tmpfiles does at boot. On the phone
# they are in Nixup's view, under its own /var, /run, /etc and /home: a rule elsewhere (Android's own folders) stops
# the build. A rule's age (cleaning old files) is taken and nothing is done with it.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;
  cfg = config.systemd.tmpfiles;

  entryModule = {
    options = {
      type = mkOption {
        type = types.str;
        default = "";
        description = "The rule's type (the attribute's name by default).";
      };
      mode = mkOption {
        type = types.str;
        default = "-";
        description = "Its mode (\"-\": the default).";
      };
      user = mkOption {
        type = types.str;
        default = "-";
        description = "Its owner (\"-\": root).";
      };
      group = mkOption {
        type = types.str;
        default = "-";
        description = "Its group (\"-\": root).";
      };
      age = mkOption {
        type = types.str;
        default = "-";
        description = "When to clean it (taken: the phone cleans nothing).";
      };
      argument = mkOption {
        type = types.str;
        default = "";
        description = "Its argument (a file's text, a link's target, ...).";
      };
    };
  };

  # NixOS's settings.<name>.<path>.<type>, as the lines of tmpfiles.d.
  fromSettings = lib.concatLists (lib.mapAttrsToList (_: paths: lib.concatLists (lib.mapAttrsToList (path: kinds:
    lib.mapAttrsToList (kind: entry:
      let type = if entry.type != "" then entry.type else kind; in
      lib.concatStringsSep " " [ type (builtins.toJSON path) entry.mode entry.user entry.group entry.age ]
      + lib.optionalString (entry.argument != "") " ${entry.argument}"
    ) kinds
  ) paths)) cfg.settings);

  lines = lib.filter (line: line != "" && !lib.hasPrefix "#" line) (map lib.trim (cfg.rules ++ fromSettings));
  # The paths the rules name (quoted or not), for the check below.
  pathOf = line:
    let words = builtins.match "[^ \t]+[ \t]+(\"[^\"]*\"|'[^']*'|[^ \t]+).*" line; in
    if words == null then "" else lib.removePrefix "'" (lib.removeSuffix "'"
      (lib.removePrefix "\"" (lib.removeSuffix "\"" (builtins.elemAt words 0))));
  inView = path: lib.any (dir: path == dir || lib.hasPrefix "${dir}/" path) [ "/var" "/run" "/etc" "/home" "/tmp" ]
    || lib.hasPrefix "%" path;
  outside = lib.filter (line: !inView (pathOf line)) lines;
in
{
  options.systemd.tmpfiles = {
    rules = mkOption {
      type = types.listOf types.str;
      default = [ ];
      example = [ "d /var/lib/foo 0750 foo foo - -" ];
      description = ''
        Rules of tmpfiles.d(5), as on NixOS: folders, files and links each build and start of the phone makes in
        Nixup's view (its /var, /run, /etc and /home), before the services start.
      '';
    };
    settings = mkOption {
      type = types.attrsOf (types.attrsOf (types.attrsOf (types.submodule entryModule)));
      default = { };
      example = { foo."/var/lib/foo".d = { mode = "0750"; user = "foo"; group = "foo"; }; };
      description = "The same as attributes, as on NixOS: settings.<name>.<path>.<type>.";
    };
    packages = mkOption {
      type = types.listOf types.package;
      default = [ ];
      description = "Packages whose lib/tmpfiles.d rules apply, as on NixOS: not on the phone (it does not build to read them).";
    };
  };

  config = {
    system.build.tmpfiles = lines;
    assertions = [
      {
        assertion = outside == [ ];
        message = "systemd.tmpfiles: ${lib.concatStringsSep "; " outside}: on the phone a rule is for Nixup's own /var, /run, /etc or /home, not Android's folders.";
      }
      {
        assertion = cfg.packages == [ ];
        message = "systemd.tmpfiles.packages: not on the phone (its rules come from the packages' files, which it does not read): give them as systemd.tmpfiles.rules.";
      }
    ];
  };
}
