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
# A user's password (hashedPassword, password, hashedPasswordFile, as on NixOS) is in the view's /etc/shadow, which
# sshd reads for a password login, with the day it expires (expires); its subordinate ids (subUidRanges,
# autoSubUidGidRange) in /etc/subuid and /etc/subgid. A user with enable = false is not there at all, and one with
# linger = false runs no units of its own (the phone has no logins that would start them).
#
# A user's packages (users.users.<name>.packages, and its Home Manager's: nix/home-manager.nix) are its profile, in
# /etc/profiles/per-user/<name> as on NixOS: on its PATH (its sessions', and its own units'), before
# environment.systemPackages.
{ config, lib, pkgs, ... }:
let
  inherit (lib) mkOption types;
  topConfig = config;
  secretFiles = import ./secret-files.nix { inherit lib; };

  # NixOS's subordinate ids (/etc/subuid, /etc/subgid): a range's start and count.
  subRange = kind: {
    options = {
      "start${kind}" = mkOption {
        type = types.int;
        description = "Start of the range of subordinate ${lib.toLower kind}s the user may use.";
      };
      count = mkOption {
        type = types.int;
        default = 1;
        description = "Count of subordinate ${lib.toLower kind}s.";
      };
    };
  };

  userModule = { name, config, ... }: {
    options = {
      enable = mkOption {
        type = types.bool;
        default = true;
        example = false;
        description = "Whether the user is there at all, as on NixOS (false: as if it were not in the config).";
      };
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
        default = if name == "root" then "/data/adb/nixup/home"
          else if config.isNormalUser then "${topConfig.users.defaultUserHome}/${name}" else "/var/empty";
        defaultText = lib.literalExpression ''"''${users.defaultUserHome}/''${name}" for a normal user, "/var/empty" else (root's: /data/adb/nixup/home)'';
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
          The user's shell, as on NixOS: a package (its shellPath, or bin/<name>; zsh, fish and xonsh need their
          programs.<shell>.enable, which writes the files they read) or a full path. null: Nixup's login
          (/etc/nixup/login), which starts users.defaultUserShell, for root, a normal user and one with useDefaultShell,
          and none (/system/bin/false) for any other (a service's), which logs in nowhere.
        '';
      };
      useDefaultShell = mkOption {
        type = types.bool;
        default = false;
        description = "Whether a system user logs in too, with users.defaultUserShell, as on NixOS.";
      };
      ignoreShellProgramCheck = mkOption {
        type = types.bool;
        default = false;
        description = ''
          Whether a shell (zsh, fish, xonsh) without its programs.<shell>.enable is fine, as on NixOS, which checks it:
          without it the shell lacks the Nix packages' folders on PATH.
        '';
      };
      packages = mkOption {
        type = types.listOf types.package;
        default = [ ];
        example = lib.literalExpression "[ pkgs.htop ]";
        description = "Nix packages for the user, as on NixOS: its profile, /etc/profiles/per-user/<name>, on its PATH.";
      };

      # Its password, as NixOS's options (the view's /etc/shadow, which the engine writes): sshd takes it when the user
      # logs in with a password (services.openssh.settings.PasswordAuthentication), and it is checked as NixOS checks
      # it. users.mutableUsers is false on the phone (nothing there changes a password), so at each build the user's
      # password is the one these give, as NixOS sets it then: hashedPasswordFile, else password, else hashedPassword
      # (initialPassword and initialHashedPassword are password and hashedPassword when those are not set).
      hashedPassword = mkOption {
        type = types.nullOr (types.passwdEntry types.str);
        default = null;
        example = "$y$j9T$...";
        description = ''
          The user's password, hashed (`mkpasswd`), as on NixOS. "" logs in without a password (not over ssh). null:
          no password login.
        '';
      };
      password = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = ''
          The user's password, as text, as on NixOS: the phone hashes it (SHA-512, as mkpasswd -m sha-512 does). It is in
          the profile, which root alone can read (on NixOS it is in Nix's store, which anyone can): for a password
          others may know, else hashedPassword or hashedPasswordFile.
        '';
      };
      hashedPasswordFile = mkOption {
        type = secretFiles.type;
        default = config.passwordFile;
        defaultText = lib.literalExpression "null";
        example = lib.literalExpression "./niobe.hash";
        description = ''
          A file with the user's hashed password (its one line, as `mkpasswd` writes it), as on NixOS, read at each
          build: a file from the config's folder (./niobe.hash), which each build puts on the phone readable by root
          alone, never in the profile or Nix's store; or, as a string, the full path of a file on the phone. One that is
          not there leaves the other options' password (the build says so).
        '';
      };
      passwordFile = mkOption {
        type = types.nullOr types.str;
        default = null;
        visible = false;
        description = "NixOS's old name of hashedPasswordFile.";
      };
      initialHashedPassword = mkOption {
        type = types.nullOr (types.passwdEntry types.str);
        default = null;
        description = "As on NixOS, with users.mutableUsers false (as on the phone): hashedPassword, when that is not set.";
      };
      initialPassword = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "As on NixOS, with users.mutableUsers false (as on the phone): password, when that is not set.";
      };
      expires = mkOption {
        type = types.nullOr (types.strMatching "[[:digit:]]{4}-[[:digit:]]{2}-[[:digit:]]{2}");
        default = null;
        example = "2027-01-31";
        description = "The day the user can no longer log in (YYYY-MM-DD), as on NixOS: sshd refuses it from then on. null: never.";
      };
      linger = mkOption {
        type = types.nullOr types.bool;
        default = null;
        example = false;
        description = ''
          Whether the user's own units (systemd.user, its Home Manager's) run without a login of it, as NixOS's
          lingering: the phone has no logins that would start them, so false is that they do not run at all. null
          (as on NixOS: not managed) and true: they run, from each build and the phone's start.
        '';
      };
      subUidRanges = mkOption {
        type = types.listOf (types.submodule (subRange "Uid"));
        default = [ ];
        example = [ { startUid = 100001; count = 65534; } ];
        description = "Subordinate user ids the user may use, as on NixOS: the view's /etc/subuid (newuidmap, for user namespaces).";
      };
      subGidRanges = mkOption {
        type = types.listOf (types.submodule (subRange "Gid"));
        default = [ ];
        example = [ { startGid = 100001; count = 65534; } ];
        description = "Subordinate group ids the user may use, as on NixOS: the view's /etc/subgid.";
      };
      autoSubUidGidRange = mkOption {
        type = types.bool;
        default = false;
        example = true;
        description = ''
          Give the user 65536 subordinate user and group ids of its own, as on NixOS (from 100000 on, kept on the
          phone); a normal user without ranges of its own gets them, as on NixOS.
        '';
      };
      cryptHomeLuks = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "As on NixOS, a home on an encrypted LUKS device: not on the phone.";
      };
      pamMount = mkOption {
        type = types.attrsOf types.str;
        default = { };
        description = "As on NixOS, pam_mount's settings for the user. Taken: sshd's PAM (nix/openssh.nix) has pam_unix alone.";
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
    config = lib.mkIf (config.isNormalUser && config.subUidRanges == [ ] && config.subGidRanges == [ ]) {
      autoSubUidGidRange = lib.mkDefault true;
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

  # (A user with enable = false is not there, as on NixOS.)
  users = lib.filterAttrs (_: user: user.enable) config.users.users;
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

  # A shell's program: a package's (its shellPath, or bin/<name>), or a full path, as NixOS's utils.toShellPath has it.
  shellPath = shell: if builtins.isString shell then shell
    else "${shell}${shell.passthru.shellPath or "/bin/${shell.meta.mainProgram or (lib.getName shell)}"}";
  # Its shell: one it names, Nixup's login (root's, a normal user's: it starts users.defaultUserShell), or none, as
  # NixOS gives a service's user.
  shellOf = user:
    if user.shell != null then shellPath user.shell
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
  # Each user's password as the engine sets it in the view's /etc/shadow, as NixOS's update-users-groups.pl does with
  # users.mutableUsers false: the file's (when it is on the phone), else the text's, hashed on the phone, else the hash;
  # none: "*", no password login (NixOS's "!" would lock the user out of sshd, which runs without PAM here).
  passwordFileOf = user: secretFiles.path topConfig "users.users.${user.name}.hashedPasswordFile" user.hashedPasswordFile;
  shadowOf = user:
    let
      password = if user.password != null then user.password else user.initialPassword;
      hash = if user.hashedPassword != null then user.hashedPassword else user.initialHashedPassword;
    in
    { inherit (user) name; uid = uidText user; }
    // lib.optionalAttrs (user.hashedPasswordFile != null) { file = passwordFileOf user; }
    // lib.optionalAttrs (password != null) { inherit password; }
    // lib.optionalAttrs (hash != null) { inherit hash; }
    // lib.optionalAttrs (user.expires != null) { inherit (user) expires; };
  withPassword = user: lib.any (option: user.${option} != null)
    [ "hashedPassword" "hashedPasswordFile" "password" "initialHashedPassword" "initialPassword" ];
  # NixOS's check of a hash (a subset of the Modular Crypt Format: $id$[options$]salt$hash).
  hashLooksRight = hash:
    let
      sep = "\\$";
      base64 = "[a-zA-Z0-9./]+";
      options = "[a-z0-9-]+(=[a-zA-Z0-9/+.-]+)?(,[a-z0-9-]+=[a-zA-Z0-9/+.-]+)*";
    in
    lib.elem hash [ "" "!" "!!" "*" ]
    || builtins.match (lib.concatStrings [ "^" sep "[a-z0-9]+(" sep options ")?" sep base64 sep base64 "(" sep base64 ")?$" ]) hash != null;
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
    defaultUserHome = mkOption {
      type = types.str;
      default = "/home";
      description = "Where normal users' homes are, as on NixOS: /home is Nixup's own folder in the view (/data/adb/nixup/users).";
    };
    manageLingering = mkOption {
      type = types.bool;
      default = true;
      description = "Whether users.users.<name>.linger is used, as on NixOS (the phone has no lingering otherwise: see linger).";
    };
    defaultUserShell = mkOption {
      type = types.nullOr (types.either types.package types.str);
      default = null;
      example = lib.literalExpression "pkgs.zsh";
      description = ''
        The shell of root, the normal users and those with useDefaultShell, when users.users.<name>.shell is not set,
        as on NixOS: a package or a full path, which Nixup's login (/etc/nixup/login) starts (programs.bash makes it
        bash, as on NixOS). null: bash with Nix packages, else Android's sh.
      '';
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
      # The shell Nixup's login starts (users.defaultUserShell), as its program; null: the profile's (nix/build.nix).
      defaultShell = if config.users.defaultUserShell == null then null else shellPath config.users.defaultUserShell;
      # The users of apps, which must be on the phone (or installed by the build).
      apps = map (user: { inherit (user) name; app = user.androidApp; }) (lib.filter (user: user.androidApp != null) userList);

      # The view's /etc/shadow: each user's password (see shadowOf); a user not here has none ("*").
      shadow = map shadowOf (lib.filter (user: hasUid user && (withPassword user || user.expires != null)) userList);
      # The view's /etc/subuid and /etc/subgid: each user's ranges, and whether it gets 65536 of its own (the engine
      # gives them, from 100000 on, and keeps them, as NixOS keeps /var/lib/nixos/auto-subuid-map).
      subIds = map (user: {
        inherit (user) name;
        uids = map (range: { start = range.startUid; inherit (range) count; }) user.subUidRanges;
        gids = map (range: { start = range.startGid; inherit (range) count; }) user.subGidRanges;
        auto = user.autoSubUidGidRange;
      }) (lib.filter (user: hasUid user && (user.autoSubUidGidRange || user.subUidRanges != [ ] || user.subGidRanges != [ ])) userList);
    };

    # A password file from the config's folder, which each build puts on the phone (see hashedPasswordFile).
    android.secrets = lib.mkMerge (map (user: secretFiles.secrets "users.users.${user.name}.hashedPasswordFile" user.hashedPasswordFile)
      (lib.filter (user: user.hashedPasswordFile != null) userList));

    # As NixOS warns of them.
    warnings = lib.concatMap (user:
      lib.optional (lib.length (lib.filter (option: user.${option} != null) [ "hashedPassword" "hashedPasswordFile" "password" ]) > 1) ''
        The user '${user.name}' has more than one of `hashedPassword`, `password` and `hashedPasswordFile` set: the
        last of hashedPassword -> password -> hashedPasswordFile is the one it gets (users.mutableUsers is false on
        the phone).''
      ++ lib.optional (user.hashedPassword != null && !hashLooksRight user.hashedPassword) ''
        The password hash of user "${user.name}" may be invalid. You must set a valid hash or the user will be locked
        out of their account. Please check the value of option `users.users."${user.name}".hashedPassword`.''
      ++ lib.optional (user.passwordFile != null)
        "The option `users.users.\"${user.name}\".passwordFile' has been renamed to `users.users.\"${user.name}\".hashedPasswordFile'."
    ) userList;

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
        assertion = lib.all (user: user.cryptHomeLuks == null) userList;
        message = "users.users.<name>.cryptHomeLuks: a home on an encrypted LUKS device is not on the phone (Android encrypts its storage itself).";
      }
      {
        assertion = lib.all (user: user.linger != null -> topConfig.users.manageLingering) userList;
        message = "users.users.<name>.linger is set, but users.manageLingering is false: set it to null, or users.manageLingering to true (as on NixOS).";
      }
    ] ++ map (user: {
      assertion = secretFiles.valid user.hashedPasswordFile;
      message = secretFiles.problem "users.users.${user.name}.hashedPasswordFile" user.hashedPasswordFile;
    }) userList ++ [
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
