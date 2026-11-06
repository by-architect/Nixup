# The users of Nixup's view of the phone, as NixOS's users.users and users.groups: root, and users that log in over
# SSH (services.openssh) with a shell of their own. They live in the view's /etc/passwd and /etc/group (written by the
# engine, Etc.kt), not in Android: a user is a Linux uid there, so an Android app's own uid makes a user that shares
# the app's files, its storage access and its su grant. androidApp names the app and the engine looks its uid up on
# the phone at each build (an app installed again gets another one): "dev.nixup.manager" makes a user of Nixup's own
# app, which root solutions let use su once Nixup may, so the user needs no other app.
#
# A normal user's home is /home/<name>, as on NixOS: in the view (where its sessions and the services run) /home is
# Nixup's own /data/adb/nixup/users, there from the start of the phone on, before it is first unlocked (an app's
# own folders are locked until then).
#
# A user without uid or androidApp (a service's user: isSystemUser, as NixOS's service modules make them, or a unit's
# DynamicUser; or a normal user) gets one on the phone from 400 to 999, as NixOS gives its system users theirs, and
# keeps it (Etc.kt): Android lets those use the network (as it lets uids below 10000, and apps that may; NixOS's
# normal users' 1000 and up are Android's own there). The shared storage, which Android's /storage lets only apps
# and root open, a service's user reaches in its unit's view when it is in media_rw (or sdcard_rw, external_storage:
# see nix/units.nix). A group without a gid gets one the same way.
#
# Android checks a process's groups for some of what it may do: network sockets need inet (3003) on some phones, the
# shared storage's folders media_rw (1023). users.groups has those, with Android's numbers, for extraGroups.
#
# A user's packages (users.users.<name>.packages, and its Home Manager's: nix/home-manager.nix) are its profile, in
# /etc/profiles/per-user/<name> as on NixOS: on its PATH (its sessions', and its own units'), before
# environment.systemPackages.
{ config, lib, pkgs, ... }:
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
          The user's id. Every user but root needs one, or androidApp: an Android app's own uid (`pm list packages -U`
          shows them; u0_a355 is 10355) makes a user that shares that app's files and permissions.
        '';
      };
      androidApp = mkOption {
        type = types.nullOr types.str;
        default = null;
        example = "dev.nixup.manager";
        # Nixup's app from when it was called Seraph, which the install replaced with this one.
        apply = app: if app == "dev.seraph.manager" then "dev.nixup.manager" else app;
        description = ''
          The Android app whose own uid the user has, looked up on the phone at each build, in place of uid (which
          changes when the app is installed again). "dev.nixup.manager" is Nixup's own app: the user may use su as
          Nixup may, and needs no other app.
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
        description = "More groups: inet for the network, media_rw for the shared storage (a service's user; users.groups).";
      };
      home = mkOption {
        type = types.str;
        default = if name == "root" then "/data/adb/nixup/home" else if config.isNormalUser then "/home/${name}" else "/var/empty";
        defaultText = lib.literalExpression ''"/home/''${name}" for a normal user, "/var/empty" else (root's: /data/adb/nixup/home)'';
        example = "/data/data/com.termux/files/home";
        description = ''
          The user's home folder: a normal user's is /home/<name>, Nixup's own (/data/adb/nixup/users/<name>), there
          from the start of the phone on (root's: /data/adb/nixup/home).
        '';
      };
      createHome = mkOption {
        type = types.bool;
        default = config.isNormalUser;
        defaultText = lib.literalExpression "isNormalUser";
        description = "Make the home folder, owned by the user, when it is not there (a normal user's is made, as on NixOS).";
      };
      homeMode = mkOption {
        type = types.strMatching "[0-7]{1,5}";
        default = "700";
        description = "The home folder's mode, when createHome makes it, as on NixOS.";
      };
      shell = mkOption {
        type = types.nullOr (types.either types.package types.str);
        default = null;
        example = lib.literalExpression "pkgs.bashInteractive";
        description = ''
          The user's shell, as on NixOS: a package (its shellPath, or bin/<name>) or a full path. null: Nixup's
          login shell (/etc/nixup/login: environment.systemPackages on PATH) for root, a normal user and one with
          useDefaultShell, and none (/system/bin/false) for any other (a service's), which logs in nowhere.
        '';
      };
      useDefaultShell = mkOption {
        type = types.bool;
        default = false;
        description = "Whether a system user gets Nixup's login shell too, as on NixOS it gets users.defaultUserShell.";
      };
      packages = mkOption {
        type = types.listOf types.package;
        default = [ ];
        example = lib.literalExpression "[ pkgs.htop ]";
        description = "Nix packages for the user, as on NixOS: its profile, /etc/profiles/per-user/<name>, on its PATH.";
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
            example = lib.literalExpression ''[ ./laptop.pub "/data/adb/nixup/secrets/ssh_keys" ]'';
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

  # A user's uid as /etc/passwd has it: an app's, or a service user's (given on the phone), as the engine fills them
  # in there (Etc.kt).
  allocated = user: user.uid == null && user.androidApp == null && (user.isSystemUser || user.isNormalUser);
  hasUid = user: user.uid != null || user.androidApp != null || allocated user;
  uidText = user:
    if user.androidApp != null then "@app:${user.androidApp}@"
    else if user.uid != null then toString user.uid
    else "@id:u:${user.name}@";
  gidText = name: group: if group.gid != null then toString group.gid else "@id:g:${name}@";

  # A user's own group, when users.groups has none of that name: its uid as the gid (a service user's: one given as a
  # group's, the same number when it can be).
  ownGroups = lib.mapAttrs' (_: user: lib.nameValuePair user.group {
    gid = if allocated user then "@id:g:${user.group}@" else uidText user;
    members = [ ];
  }) (lib.filterAttrs (_: user: hasUid user && !(groups ? ${user.group})) users);
  # The units' users that users.users has not (a DynamicUser), with their groups.
  unitAccounts = config.system.build.unitAccounts;
  unitUsers = lib.unique (map (user: { inherit (user) name group; }) (lib.filter (user: !user.declared) unitAccounts));
  unitGroups = lib.listToAttrs (map (user: lib.nameValuePair user.group { gid = "@id:g:${user.group}@"; members = [ ]; })
    (lib.filter (user: !(groups ? ${user.group}) && !(ownGroups ? ${user.group})) unitUsers));
  # A group a unit names (SupplementaryGroups) that the view has not: one of its own, as NixOS has its system groups.
  extraGroups = lib.genAttrs (lib.filter (group: !(groups ? ${group})) (lib.unique (lib.concatMap (user: user.groups) unitAccounts)))
    (group: { gid = "@id:g:${group}@"; members = [ ]; });
  allGroups = extraGroups // unitGroups // lib.mapAttrs (name: group: { gid = gidText name group; inherit (group) members; }) groups // ownGroups;
  gidOf = name: allGroups.${name}.gid or null;
  members = name: lib.unique (allGroups.${name}.members
    ++ lib.attrNames (lib.filterAttrs (_: user: lib.elem name user.extraGroups) users)
    ++ map (user: user.name) (lib.filter (user: lib.elem name (user.groups ++ [ "inet" ])) unitAccounts));

  # Its shell: one it names, Nixup's login shell (root's, a normal user's), or none, as NixOS gives a service's user.
  shellOf = user:
    if user.shell != null then
      (if builtins.isString user.shell then user.shell
       else "${user.shell}${user.shell.passthru.shellPath or "/bin/${user.shell.meta.mainProgram or (lib.getName user.shell)}"}")
    else if user.name == "root" || user.isNormalUser || user.useDefaultShell then "/etc/nixup/login"
    else "/system/bin/false";
  passwdLine = user: lib.concatStringsSep ":" [
    user.name "x" (uidText user) (toString (gidOf user.group)) user.description user.home (shellOf user)
  ];
  unitPasswdLine = user: "${user.name}:x:@id:u:${user.name}@:${toString (gidOf user.group)}::/var/empty:/system/bin/false";
  groupLine = name: group: "${name}:x:${toString group.gid}:${lib.concatStringsSep "," (members name)}";

  userList = lib.attrValues users;
  # A user's packages in one folder, its profile: made as nix-env makes one, by Nix itself (nixpkgs' buildEnv would need
  # Perl on the phone), the first of two with the same file winning, as system.path is (nix/nixos-compat.nix).
  profileOf = user:
    let outputsOf = package: map (output: package.${output}.outPath or package.outPath) (package.meta.outputsToInstall or [ "out" ]); in
    derivation {
      name = "user-environment";
      system = "builtin";
      builder = "builtin:buildenv";
      manifest = builtins.toFile "env-manifest.nix" "[ ]";
      derivations = lib.imap1 (priority: package: [ "true" priority (builtins.length (outputsOf package)) ] ++ outputsOf package) user.packages;
      preferLocalBuild = true;
      allowSubstitutes = false;
    };
  missingUid = map (user: user.name) (lib.filter (user: !hasUid user) userList);
  bothUids = map (user: user.name) (lib.filter (user: user.uid != null && user.androidApp != null && user.name != "root") userList);
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

    # Android's groups that decide what a process may do (system/core's android_filesystem_config.h), and NixOS's
    # names for its devices' groups as Android's (its sound devices are audio's, its GPU graphics').
    users.groups = lib.mapAttrs (_: gid: { gid = lib.mkDefault gid; }) {
      audio = 1005;
      video = 1003;
      render = 1003;
      input = 1004;
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
      nogroup = 9999;
      sshd = 74;
    };

    # NixOS's nobody, as Android's own (AID_NOBODY: 9999, which may use the network, as NixOS's 65534 may not here).
    users.users.nobody = {
      uid = lib.mkDefault 9999;
      group = lib.mkDefault "nogroup";
      isSystemUser = lib.mkDefault true;
      description = lib.mkDefault "Unprivileged account (don't use!)";
    };

    # Each user's profile, as NixOS makes /etc/profiles/per-user/<name>.
    environment.etc = lib.mapAttrs' (_: user: lib.nameValuePair "profiles/per-user/${user.name}" { source = profileOf user; })
      (lib.filterAttrs (_: user: user.packages != [ ]) users);

    # The view's /etc/passwd and /etc/group (the engine writes them), and the homes to make.
    system.build.users = {
      passwd = lib.concatMapStrings (user: passwdLine user + "\n")
        (lib.sortOn (user: if user.uid != null then user.uid else 1000000) (lib.filter hasUid userList))
        + lib.concatMapStrings (user: unitPasswdLine user + "\n") unitUsers
        + "sshd:x:74:74:sshd privilege separation:/var/empty:/system/bin/false\n";
      group = lib.concatStrings (lib.mapAttrsToList (name: group: groupLine name group + "\n") allGroups);
      # An app's uid as text the engine fills in ("@app:<package>@"), a service user's ("@id:u:<name>@"), else a
      # number.
      homes = map (user: { path = user.home; uid = uidText user; gid = toString (gidOf user.group); mode = user.homeMode; })
        (lib.filter (user: user.createHome && hasUid user) userList);
      # The users of apps, which must be on the phone (or installed by the build).
      apps = map (user: { inherit (user) name; app = user.androidApp; }) (lib.filter (user: user.androidApp != null) userList);
    };

    assertions = [
      {
        assertion = missingUid == [ ];
        message = "users.users: ${lib.concatStringsSep ", " missingUid} needs a uid, or androidApp (an Android app's own uid: \"dev.nixup.manager\", Nixup's, may use su as Nixup may), or isSystemUser or isNormalUser (the phone gives it one).";
      }
      {
        assertion = bothUids == [ ];
        message = "users.users: ${lib.concatStringsSep ", " bothUids} has both uid and androidApp: give one.";
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
