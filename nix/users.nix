# The users of Seraph's view of the phone, as NixOS's users.users and users.groups: root, and users that log in over
# SSH (services.openssh) with a shell of their own. They live in the view's /etc/passwd and /etc/group (written by the
# engine, Etc.kt), not in Android: a user is a Linux uid there, so an Android app's own uid (like Termux's, u0_a355 =
# 10355) makes a user that shares the app's files, its storage access and its su grant.
#
# Android checks a process's groups for some of what it may do: network sockets need inet (3003), the shared storage
# external_storage (1077) or sdcard_rw (1015). users.groups has those, with Android's numbers, for extraGroups.
{ config, lib, ... }:
let
  inherit (lib) mkOption types;

  userModule = { name, config, ... }: {
    options = {
      name = mkOption {
        type = types.str;
        default = name;
        description = "The user's name.";
      };
      uid = mkOption {
        type = types.nullOr types.int;
        default = if name == "root" then 0 else null;
        example = 10355;
        description = ''
          The user's id. Every user but root needs one: an Android app's own uid (`pm list packages -U` shows them;
          u0_a355 is 10355) makes a user that shares that app's files and permissions.
        '';
      };
      isNormalUser = mkOption {
        type = types.bool;
        default = false;
        description = "A user that logs in, as on NixOS.";
      };
      isSystemUser = mkOption {
        type = types.bool;
        default = false;
        description = "A user for a service, as on NixOS.";
      };
      description = mkOption {
        type = types.str;
        default = "";
        example = "Niobe";
        description = "The user's full name.";
      };
      group = mkOption {
        type = types.str;
        default = name;
        description = "The user's own group (one of its name, with its uid as the gid, is made when users.groups has none).";
      };
      extraGroups = mkOption {
        type = types.listOf types.str;
        default = [ ];
        example = [ "inet" "external_storage" ];
        description = "More groups: inet for the network, external_storage or sdcard_rw for the shared storage (users.groups).";
      };
      home = mkOption {
        type = types.str;
        default = if name == "root" then "/data/adb/seraph/home" else "/var/empty";
        example = "/data/data/com.termux/files/home";
        description = "The user's home folder (root's: /data/adb/seraph/home).";
      };
      createHome = mkOption {
        type = types.bool;
        default = false;
        description = "Make the home folder, owned by the user, when it is not there.";
      };
      openssh = {
        authorizedKeys = {
          keys = mkOption {
            type = types.listOf types.singleLineStr;
            default = [ ];
            example = [ "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA… neo@laptop" ];
            description = "Public keys that may log in as this user, as on NixOS.";
          };
          keyFiles = mkOption {
            type = types.listOf (types.either types.path types.str);
            default = [ ];
            example = lib.literalExpression ''[ ./laptop.pub "/data/adb/seraph/secrets/ssh_keys" ]'';
            description = ''
              Files of public keys that may log in as this user: a file from the config's folder (read when the config
              is built, as on NixOS), or, as a string, the full path of one on the phone (read there when sshd starts).
            '';
          };
        };
        authorizedPrincipals = mkOption {
          type = types.listOf types.singleLineStr;
          default = [ ];
          description = "Certificate principals this user may log in with, as on NixOS: not on the phone yet.";
        };
      };
    };
  };

  groupModule = { name, ... }: {
    options = {
      name = mkOption {
        type = types.str;
        default = name;
        description = "The group's name.";
      };
      gid = mkOption {
        type = types.nullOr types.int;
        default = null;
        description = "The group's id.";
      };
      members = mkOption {
        type = types.listOf types.str;
        default = [ ];
        description = "Users in it besides those that name it in extraGroups.";
      };
    };
  };

  users = config.users.users;
  groups = config.users.groups;

  # A user's own group, when users.groups has none of that name: its uid as the gid.
  ownGroups = lib.mapAttrs' (_: user: lib.nameValuePair user.group { gid = user.uid; members = [ ]; })
    (lib.filterAttrs (_: user: user.uid != null && !(groups ? ${user.group})) users);
  allGroups = lib.mapAttrs (name: group: { inherit (group) gid members; }) groups // ownGroups;
  gidOf = name: allGroups.${name}.gid or null;
  members = name: lib.unique (allGroups.${name}.members
    ++ lib.attrNames (lib.filterAttrs (_: user: lib.elem name user.extraGroups) users));

  passwdLine = user: lib.concatStringsSep ":" [
    user.name "x" (toString user.uid) (toString (gidOf user.group)) user.description user.home "/etc/seraph/login"
  ];
  groupLine = name: group: "${name}:x:${toString group.gid}:${lib.concatStringsSep "," (members name)}";

  userList = lib.attrValues users;
  missingUid = map (user: user.name) (lib.filter (user: user.uid == null) userList);
  unknownGroups = lib.concatMap (user: map (group: "${user.name}: ${group}")
    (lib.filter (group: !(allGroups ? ${group})) ([ user.group ] ++ user.extraGroups))) userList;
in
{
  options.users = {
    users = mkOption {
      type = types.attrsOf (types.submodule userModule);
      default = { };
      description = ''
        The view's users, as NixOS's users.users: root, and users that log in over SSH (services.openssh) with the
        shell the app's terminal has (environment.systemPackages on PATH).
      '';
    };
    groups = mkOption {
      type = types.attrsOf (types.submodule groupModule);
      default = { };
      description = "The view's groups, as NixOS's users.groups; Android's own (inet, external_storage, ...) are there with its numbers.";
    };
  };

  config = {
    users.users.root.description = lib.mkDefault "root";

    # Android's groups that decide what a process may do (system/core's android_filesystem_config.h).
    users.groups = lib.mapAttrs (_: gid: { gid = lib.mkDefault gid; }) {
      root = 0;
      sdcard_rw = 1015;
      media_rw = 1023;
      external_storage = 1077;
      shell = 2000;
      inet = 3003;
      net_raw = 3004;
      net_admin = 3005;
      readproc = 3009;
      wakelock = 3010;
      everybody = 9997;
      sshd = 74;
    };

    # The view's /etc/passwd and /etc/group (the engine writes them), and the homes to make.
    system.build.users = {
      passwd = lib.concatMapStrings (user: passwdLine user + "\n") (lib.sortOn (user: user.uid) (lib.filter (user: user.uid != null) userList))
        + "sshd:x:74:74:sshd privilege separation:/var/empty:/system/bin/false\n";
      group = lib.concatStrings (lib.mapAttrsToList (name: group: groupLine name group + "\n")
        (lib.filterAttrs (_: group: group.gid != null) allGroups));
      homes = map (user: { path = user.home; inherit (user) uid; gid = gidOf user.group; })
        (lib.filter (user: user.createHome && user.uid != null) userList);
    };

    assertions = [
      {
        assertion = missingUid == [ ];
        message = "users.users: ${lib.concatStringsSep ", " missingUid} needs a uid (an Android app's own, like 10355 for u0_a355, shares that app's files).";
      }
      {
        assertion = unknownGroups == [ ];
        message = "users.users: no group named ${lib.concatStringsSep "; " unknownGroups} in users.groups.";
      }
      {
        assertion = lib.all (user: user.openssh.authorizedPrincipals == [ ]) userList;
        message = "users.users.<name>.openssh.authorizedPrincipals: not on the phone yet.";
      }
      {
        assertion = lib.all (user: lib.all (file: builtins.isPath file || lib.hasPrefix "/" file) user.openssh.authorizedKeys.keyFiles) userList;
        message = "users.users.<name>.openssh.authorizedKeys.keyFiles: a file from the config's folder is a path, written without quotes (./laptop.pub); a string is a full path on the phone.";
      }
    ];
  };
}
